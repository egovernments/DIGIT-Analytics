package org.egov.inbox.item;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.egov.inbox.store.TransactionSettings;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Stores items in {@link InboxItemTable} in the item tenant's schema, creating the table there on the
 * tenant's first item. Its own small pool (the Postgres store's is read-only).
 *
 * <p>The tenant's schema must exist (the tenant's services create it); the inbox never creates
 * schemas. Table creation takes a transaction-scoped advisory lock per schema, so two consumers
 * creating the same tenant's table at once don't collide.
 */
@Slf4j
public class InboxItemWriter implements DisposableBean {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final int statementTimeoutMillis;
    /** Tenants whose table this process has created or found; filled only after the creating transaction commits. */
    private final Set<String> tablesReady = ConcurrentHashMap.newKeySet();

    public InboxItemWriter(String url, String username, String password, int maximumPoolSize,
                           int statementTimeoutMillis, ObjectMapper mapper) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("inbox-item-writer");
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(maximumPoolSize);
        // Connect on first use: an unreachable database fails item writes, not the service's start.
        hikari.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(hikari);
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.mapper = mapper;
        this.statementTimeoutMillis = statementTimeoutMillis;
    }

    /**
     * Inserts the item, or replaces the stored one unless that one was modified later.
     *
     * @return true when the row was written, false when a newer one was kept
     */
    public boolean write(InboxItem item) {
        item.validate();
        String schema = item.tenantId();
        Object[] params = params(item);
        Integer written = transaction.execute(status -> {
            TransactionSettings.apply(jdbc, statementTimeoutMillis, schema);
            if (!tablesReady.contains(schema)) {
                createTable(schema);
            }
            return jdbc.update(InboxItemTable.UPSERT, params);
        });
        return written != null && written > 0;
    }

    private void createTable(String schema) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = ?)", Boolean.class, schema);
        if (!Boolean.TRUE.equals(exists)) {
            throw new MissingTenantSchemaException(schema);
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "inbox_item:" + schema);
        InboxItemTable.DDL.forEach(jdbc::execute);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tablesReady.add(schema);
                log.info("Inbox item table ready in schema {}", schema);
            }
        });
    }

    private Object[] params(InboxItem item) {
        return new Object[] {
                item.inboxModule(), item.module(), item.businessService(), item.businessId(), item.state(),
                json(item.assignees() == null ? List.of() : item.assignees()),
                json(item.attributes() == null ? Map.of() : item.attributes()),
                json(item.data()),
                item.createdTime(), item.lastModifiedTime() };
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new InboxItem.InvalidInboxItemException("value cannot be written as JSON: " + e.getOriginalMessage());
        }
    }

    @Override
    public void destroy() {
        dataSource.close();
    }

    /** The item's tenant has no schema (yet): retrying may succeed once the tenant's services create it. */
    public static class MissingTenantSchemaException extends RuntimeException {
        public MissingTenantSchemaException(String schema) {
            super("Schema of tenant " + schema + " does not exist");
        }
    }
}
