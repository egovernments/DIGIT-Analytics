package org.egov.inbox.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.egov.InboxApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The whole inbox application on DIGIT 3.0 with the item consumer on, against a broker that does not
 * create topics on first use (as on 3.0 clusters): the listener runs, the item and dead-letter topics
 * are created at startup although the 3.0 platform creates most beans only on first use, an invalid
 * item is dead-lettered without holding up the next one, and an item waits out a database outage
 * instead of being dead-lettered.
 */
@SpringBootTest(classes = InboxApplication.class, properties = {
        "inbox.platform=3.0",
        "egov.mdms.search.endpoint=/mdms-v2/v2",
        "workflow.businessservice.search.path=/workflow/v3/process/definition",
        "inbox.postgres-store.schema-per-tenant=true",
        "inbox.item.consumer.enabled=true",
        "inbox.item.database-retry.initial-interval-millis=100",
        "inbox.item.database-retry.max-interval-millis=200"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class InboxItemApplicationTest {

    private static final String TENANT = "ITEMAPP";
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inbox.postgres-store.url", POSTGRES::getJdbcUrl);
        registry.add("inbox.postgres-store.username", POSTGRES::getUsername);
        registry.add("inbox.postgres-store.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void tenantSchema() throws SQLException {
        try (Connection connection = connection()) {
            connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"" + TENANT + "\"");
        }
    }

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @MockitoSpyBean
    private InboxItemWriter writer;

    @Value("${inbox.item.topic}")
    private String topic;

    @Value("${inbox.item.dead-letter-topic}")
    private String deadLetterTopic;

    @Test
    void theItemListenerRunsInTheApplication() {
        List<MessageListenerContainer> containers = List.copyOf(listeners.getListenerContainers());
        assertEquals(1, containers.size(), "item listener containers");
        assertTrue(containers.get(0).isRunning(), "item listener running");
    }

    @Test
    void theItemAndDeadLetterTopicsAreCreatedAtStartup() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            String broker = String.valueOf(admin.describeCluster().nodes().get().iterator().next().id());
            ConfigResource brokerConfig = new ConfigResource(ConfigResource.Type.BROKER, broker);
            assertEquals("false", admin.describeConfigs(List.of(brokerConfig)).all().get().get(brokerConfig)
                    .get("auto.create.topics.enable").value(), "broker creates topics on first use");
            Set<String> topics = admin.listTopics().names().get();
            assertTrue(topics.contains(topic), "item topic exists: " + topics);
            assertTrue(topics.contains(deadLetterTopic), "dead-letter topic exists: " + topics);
        }
    }

    @Test
    void anInvalidItemIsDeadLetteredAndTheNextOneIsStored() throws Exception {
        String badKey = "bad-" + UUID.randomUUID();
        String businessId = "APP-" + UUID.randomUUID();
        send(badKey, "not an inbox item");
        send(businessId, item(businessId));

        waitFor(() -> stored(businessId), "item after the invalid one stored");
        waitFor(() -> deadLettered(badKey), "invalid item dead-lettered");
    }

    @Test
    void anItemWaitsOutADatabaseOutage() throws Exception {
        String businessId = "APP-" + UUID.randomUUID();
        // More failures than inbox.item.retries (2): the database is unreachable for several attempts.
        CannotCreateTransactionException unreachable = new CannotCreateTransactionException(
                "Could not open JDBC Connection for transaction",
                new SQLTransientConnectionException("inbox-item-writer - Connection is not available", "08001"));
        doThrow(unreachable).doThrow(unreachable).doThrow(unreachable).doThrow(unreachable).doThrow(unreachable)
                .doCallRealMethod()
                .when(writer).write(argThat(item -> item != null && businessId.equals(item.businessId())));

        send(businessId, item(businessId));

        waitFor(() -> stored(businessId), "item stored once the database is back");
        assertTrue(!deadLettered(businessId), "item not dead-lettered during the outage");
    }

    private void send(String key, String value) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }

    private static String item(String businessId) {
        long now = System.currentTimeMillis();
        return """
                {"tenantId":"%s","inboxModule":"studio","module":"studio","businessService":"BS","businessId":"%s",
                 "state":"S1","assignees":[],"attributes":{},"data":{"applicationNumber":"%s"},
                 "createdTime":%d,"lastModifiedTime":%d}
                """.formatted(TENANT, businessId, businessId, now, now);
    }

    private static boolean stored(String businessId) {
        try (Connection connection = connection()) {
            var statement = connection.prepareStatement(
                    "SELECT count(*) FROM \"" + TENANT + "\".inbox_item WHERE business_id = ?");
            statement.setString(1, businessId);
            var rows = statement.executeQuery();
            return rows.next() && rows.getInt(1) == 1;
        } catch (SQLException e) {
            return false; // the table is created with the tenant's first item
        }
    }

    private boolean deadLettered(String key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(deadLetterTopic));
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records.stream().anyMatch(record -> key.equals(record.key()));
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting: " + what);
    }
}
