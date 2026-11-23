package org.egov.inbox.item;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * The item consumer, on only when {@code inbox.item.consumer.enabled=true} (deployments that read only
 * Elasticsearch need neither Kafka nor a writable database). It writes through the Postgres store's
 * database settings ({@code inbox.postgres-store.*}).
 *
 * <p>Messages are plain JSON strings, whatever serializers the environment configures for other
 * services. A message that is not a valid item goes to {@code inbox.item.dead-letter-topic} at once
 * (only logged when that is empty) and the next one is read. While the database cannot be reached, the
 * write is retried until it can, waiting {@code inbox.item.database-retry.initial-interval-millis}
 * (doubling up to {@code inbox.item.database-retry.max-interval-millis}) between tries: the items wait
 * in the topic instead of being dead-lettered by an outage. Any other failed write is retried
 * {@code inbox.item.retries} times, one second apart, then dead-lettered.
 */
@Slf4j
@EnableKafka
@Configuration
@ConditionalOnProperty(prefix = "inbox.item.consumer", name = "enabled", havingValue = "true")
public class InboxItemConfiguration {

    static final String LISTENER_FACTORY = "inboxItemListenerFactory";

    @Bean
    public InboxItemWriter inboxItemWriter(@Value("${inbox.postgres-store.url}") String url,
                                           @Value("${inbox.postgres-store.username:}") String username,
                                           @Value("${inbox.postgres-store.password:}") String password,
                                           @Value("${inbox.item.maximum-pool-size:3}") int maximumPoolSize,
                                           @Value("${inbox.postgres-store.statement-timeout-millis:10000}") int statementTimeoutMillis,
                                           ObjectMapper mapper) {
        return new InboxItemWriter(url, username, password, maximumPoolSize, statementTimeoutMillis, mapper);
    }

    /**
     * The item and dead-letter topics, created at startup when missing (with the broker's default
     * partitions and replicas): a cluster that doesn't create topics on first use would otherwise make
     * every dead-lettering fail, and the failed item would be read again and again, holding up the
     * items after it. An unreachable broker is logged and does not stop the start.
     */
    @Bean
    public KafkaAdmin.NewTopics inboxItemTopics(@Value("${inbox.item.topic}") String topic,
                                                @Value("${inbox.item.dead-letter-topic:}") String deadLetterTopic) {
        List<NewTopic> topics = new ArrayList<>();
        topics.add(TopicBuilder.name(topic).build());
        if (deadLetterTopic != null && !deadLetterTopic.isBlank()) {
            topics.add(TopicBuilder.name(deadLetterTopic).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    @Bean
    public InboxItemConsumer inboxItemConsumer(InboxItemWriter writer, ObjectMapper mapper) {
        return new InboxItemConsumer(writer, mapper);
    }

    @Bean(LISTENER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> inboxItemListenerFactory(
            KafkaProperties kafka,
            @Value("${inbox.item.dead-letter-topic:}") String deadLetterTopic,
            @Value("${inbox.item.retries:2}") long retries,
            @Value("${inbox.item.concurrency:1}") int concurrency,
            @Value("${inbox.item.database-retry.initial-interval-millis:1000}") long databaseRetryInitialMillis,
            @Value("${inbox.item.database-retry.max-interval-millis:30000}") long databaseRetryMaxMillis) {
        Map<String, Object> consumer = kafka.buildConsumerProperties();
        // A new consumer group starts from the oldest message, so items sent before the first start are stored.
        consumer.put("auto.offset.reset", "earliest");
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer, new StringDeserializer(), new StringDeserializer()));
        factory.setConcurrency(concurrency);
        DefaultErrorHandler errors = new DefaultErrorHandler(recoverer(kafka, deadLetterTopic), new FixedBackOff(1000L, retries));
        errors.addNotRetryableExceptions(InboxItem.InvalidInboxItemException.class);
        BackOff untilDatabaseIsBack = untilDatabaseIsBack(databaseRetryInitialMillis, databaseRetryMaxMillis);
        // null: the fixed back-off above. The tracked attempts restart when the failure changes, so an item
        // that waited out an outage still gets its few retries if it then fails for another reason.
        errors.setBackOffFunction((record, e) -> databaseUnavailable(e) ? untilDatabaseIsBack : null);
        factory.setCommonErrorHandler(errors);
        return factory;
    }

    private static ConsumerRecordRecoverer recoverer(KafkaProperties kafka, String deadLetterTopic) {
        if (deadLetterTopic == null || deadLetterTopic.isBlank()) {
            return (record, e) -> log.error("Inbox item dropped (topic {}, partition {}, offset {}, key {}): {}",
                    record.topic(), record.partition(), record.offset(), record.key(), cause(e));
        }
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                kafka.buildProducerProperties(), new StringSerializer(), new StringSerializer()));
        return new DeadLetterPublishingRecoverer(template, (record, e) -> {
            log.error("Inbox item sent to {} (topic {}, partition {}, offset {}, key {}): {}", deadLetterTopic,
                    record.topic(), record.partition(), record.offset(), record.key(), cause(e));
            // Any partition: the dead-letter topic need not have as many partitions as the item topic.
            return new TopicPartition(deadLetterTopic, -1);
        });
    }

    /** Retries without end, doubling the wait up to the maximum (well under the consumer's max.poll.interval.ms). */
    private static BackOff untilDatabaseIsBack(long initialMillis, long maxMillis) {
        ExponentialBackOff backOff = new ExponentialBackOff(initialMillis, 2.0);
        backOff.setMaxInterval(maxMillis);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        return backOff;
    }

    /**
     * Whether the write failed because the database could not be reached (no connection from the pool,
     * a lost connection, a server shutting down or starting): the item itself is fine and will be
     * written once the database is back. SQL states: class 08 connection exceptions, 57P01-57P03.
     */
    static boolean databaseUnavailable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof CannotCreateTransactionException || cause instanceof CannotGetJdbcConnectionException
                    || cause instanceof SQLTransientConnectionException || cause instanceof SQLRecoverableException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || sql.getSQLState().matches("57P0[123]"))) {
                return true;
            }
        }
        return false;
    }

    /** The reason itself, not the listener's wrapper around it. */
    private static String cause(Exception e) {
        return NestedExceptionUtils.getMostSpecificCause(e).getMessage();
    }
}
