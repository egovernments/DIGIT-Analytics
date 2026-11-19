package org.egov.inbox.item;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads the items products send ({@code inbox.item.topic}) and stores each one. A message that is not
 * a valid item goes to the dead-letter topic at once; a failed write is retried first (see
 * {@link InboxItemConfiguration}).
 */
@Slf4j
public class InboxItemConsumer {

    private final InboxItemWriter writer;
    private final ObjectMapper mapper;

    public InboxItemConsumer(InboxItemWriter writer, ObjectMapper mapper) {
        this.writer = writer;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${inbox.item.topic}", groupId = "${inbox.item.consumer-group}",
            containerFactory = InboxItemConfiguration.LISTENER_FACTORY)
    public void consume(ConsumerRecord<String, String> record) {
        InboxItem item = parse(record.value());
        boolean written = writer.write(item);
        if (log.isDebugEnabled()) {
            log.debug("Inbox item {} {} of tenant {}: {}", item.inboxModule(), item.businessId(), item.tenantId(),
                    written ? "stored" : "kept the newer stored one");
        }
    }

    InboxItem parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InboxItem.InvalidInboxItemException("empty message");
        }
        try {
            return mapper.readValue(value, InboxItem.class);
        } catch (JsonProcessingException e) {
            throw new InboxItem.InvalidInboxItemException("message is not an inbox item: " + e.getOriginalMessage());
        }
    }
}
