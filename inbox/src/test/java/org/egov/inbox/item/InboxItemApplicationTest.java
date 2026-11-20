package org.egov.inbox.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.egov.InboxApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The whole inbox application on DIGIT 3.0 with the item consumer on: its listener is registered and
 * running at startup, although the 3.0 platform creates most beans only on first use.
 */
@SpringBootTest(classes = InboxApplication.class, properties = {
        "inbox.platform=3.0",
        "egov.mdms.search.endpoint=/mdms-v2/v2",
        "workflow.businessservice.search.path=/workflow/v3/process/definition",
        "inbox.postgres-store.schema-per-tenant=true",
        "inbox.item.consumer.enabled=true"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class InboxItemApplicationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inbox.postgres-store.url", POSTGRES::getJdbcUrl);
        registry.add("inbox.postgres-store.username", POSTGRES::getUsername);
        registry.add("inbox.postgres-store.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Test
    void theItemListenerRunsInTheApplication() {
        List<MessageListenerContainer> containers = List.copyOf(listeners.getListenerContainers());
        assertEquals(1, containers.size(), "item listener containers");
        assertTrue(containers.get(0).isRunning(), "item listener running");
    }
}
