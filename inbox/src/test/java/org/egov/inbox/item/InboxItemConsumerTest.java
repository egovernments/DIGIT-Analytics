package org.egov.inbox.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The consumer end to end: items sent to a real Kafka topic land in the tenant's table; a message
 * that is not an item goes to the dead-letter topic and does not hold up the ones after it. The
 * environment's own serializer settings (DIGIT charts set JSON ones) do not change how items are read.
 * The broker creates no topics on first use, so the inbox's own topic creation is exercised too.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = InboxItemConfiguration.class, properties = {
        "inbox.item.consumer.enabled=true",
        "inbox.item.topic=inbox-item-upsert",
        "inbox.item.consumer-group=inbox-item-test",
        "inbox.item.dead-letter-topic=inbox-item-upsert-dlt",
        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer"})
@ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
class InboxItemConsumerTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    // Like clusters that don't create a topic on first use: the inbox must create its own topics.
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
    static void schema() throws Exception {
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA \"STUDIO\"");
        }
    }

    @Test
    void itemsAreStoredAndABadMessageGoesToTheDeadLetterTopic() throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()), new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>("inbox-item-upsert", "STUDIO:APP-1", item("APP-1", "PENDING", 1000))).get();
            producer.send(new ProducerRecord<>("inbox-item-upsert", "STUDIO:BAD", "{not json")).get();
            producer.send(new ProducerRecord<>("inbox-item-upsert", "STUDIO:APP-2", item("APP-2", "PENDING", 1000))).get();
            producer.send(new ProducerRecord<>("inbox-item-upsert", "STUDIO:APP-1", item("APP-1", "APPROVED", 2000))).get();
        }

        long deadline = System.currentTimeMillis() + 60_000;
        Map<String, String> states = Map.of();
        while (System.currentTimeMillis() < deadline) {
            states = states();
            if (states.size() == 2 && "APPROVED".equals(states.get("APP-1"))) {
                break;
            }
            Thread.sleep(500);
        }
        assertEquals(Map.of("APP-1", "APPROVED", "APP-2", "PENDING"), states);

        List<String> deadLetters = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("inbox-item-upsert-dlt"));
            long until = System.currentTimeMillis() + 30_000;
            while (deadLetters.isEmpty() && System.currentTimeMillis() < until) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    deadLetters.add(r.key() + "=" + r.value());
                }
            }
        }
        assertEquals(List.of("STUDIO:BAD={not json"), deadLetters);
    }

    private static String item(String id, String state, long modified) {
        return "{\"tenantId\":\"STUDIO\",\"inboxModule\":\"public-services\",\"module\":\"Demo\",\"businessService\":\"show\","
                + "\"businessId\":\"" + id + "\",\"state\":\"" + state + "\",\"assignees\":[],"
                + "\"data\":{\"applicationNumber\":\"" + id + "\"},\"createdTime\":1000,\"lastModifiedTime\":" + modified
                + ",\"unknownField\":true}";
    }

    private static Map<String, String> states() throws Exception {
        try (Connection c = db(); Statement s = c.createStatement()) {
            ResultSet tables = s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'STUDIO' AND table_name = 'inbox_item'");
            tables.next();
            if (tables.getInt(1) == 0) {
                return Map.of();
            }
            Map<String, String> states = new java.util.TreeMap<>();
            try (ResultSet rs = s.executeQuery("SELECT business_id, state FROM \"STUDIO\".inbox_item")) {
                while (rs.next()) {
                    states.put(rs.getString(1), rs.getString(2));
                }
            }
            assertTrue(states.size() <= 2);
            return states;
        }
    }

    private static Connection db() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
