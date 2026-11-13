package org.egov.inbox.store;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Inbox documents in a Postgres relation, typically a view the owning service keeps in the shape the
 * inbox reads (columns for the criteria, a jsonb document column).
 *
 * <p>Present only when {@code inbox.postgres-store.url} is set. It has its own small connection pool,
 * meant for a read-only database user, and is not a Spring DataSource bean (the service's main
 * datasource is unchanged). Every query runs in a read-only transaction with a statement timeout; with
 * {@code schema-per-tenant} the request's tenant is the schema ({@code SET LOCAL search_path}).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "inbox.postgres-store", name = "url")
public class PostgresInboxStore implements InboxStore, DisposableBean {

    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_]{1,63}");
    private static final TypeReference<LinkedHashMap<String, Object>> DOCUMENT = new TypeReference<>() {
    };

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final PostgresInboxQueryBuilder sql;
    private final boolean schemaPerTenant;
    private final int statementTimeoutMillis;
    private final ObjectMapper mapper;

    public PostgresInboxStore(@Value("${inbox.postgres-store.url}") String url,
                              @Value("${inbox.postgres-store.username:}") String username,
                              @Value("${inbox.postgres-store.password:}") String password,
                              @Value("${inbox.postgres-store.maximum-pool-size:5}") int maximumPoolSize,
                              @Value("${inbox.postgres-store.schema-per-tenant:false}") boolean schemaPerTenant,
                              @Value("${inbox.postgres-store.statement-timeout-millis:10000}") int statementTimeoutMillis,
                              ObjectMapper mapper) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("inbox-postgres-store");
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(maximumPoolSize);
        hikari.setReadOnly(true);
        // Connect on first use: an unreachable database fails inbox searches, not the service's start.
        hikari.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(hikari);
        this.jdbc = new JdbcTemplate(dataSource);
        this.readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.readOnly.setReadOnly(true);
        this.sql = new PostgresInboxQueryBuilder(schemaPerTenant);
        this.schemaPerTenant = schemaPerTenant;
        this.statementTimeoutMillis = statementTimeoutMillis;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "POSTGRES";
    }

    @Override
    public List<Map<String, Object>> search(InboxQuery query) {
        PostgresInboxQueryBuilder.Sql statement = sql.search(query);
        return run(query, jdbc -> jdbc.query(statement.text(), (rs, i) -> document(rs.getString(1)), statement.params().toArray()));
    }

    @Override
    public Integer count(InboxQuery query) {
        PostgresInboxQueryBuilder.Sql statement = sql.count(query);
        return run(query, jdbc -> jdbc.queryForObject(statement.text(), Long.class, statement.params().toArray()).intValue());
    }

    @Override
    public Map<String, Integer> countByState(InboxQuery query) {
        PostgresInboxQueryBuilder.Sql statement = sql.countByState(query);
        return run(query, jdbc -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            jdbc.query(statement.text(), rs -> {
                counts.put(rs.getString(1), (int) rs.getLong(2));
            }, statement.params().toArray());
            return counts;
        });
    }

    @Override
    public Integer countModifiedBetween(InboxQuery query, long from, long to) {
        PostgresInboxQueryBuilder.Sql statement = sql.countModifiedBetween(query, from, to);
        return run(query, jdbc -> jdbc.queryForObject(statement.text(), Long.class, statement.params().toArray()).intValue());
    }

    /** Runs the work in a read-only transaction, in the tenant's schema when schema-per-tenant. */
    private <T> T run(InboxQuery query, Function<JdbcTemplate, T> work) {
        String schema = schemaPerTenant ? tenantSchema(query.tenantId()) : null;
        return readOnly.execute(status -> {
            // SET cannot take bind parameters: the timeout is an int and the schema matched TENANT.
            jdbc.execute("SET LOCAL statement_timeout = " + statementTimeoutMillis);
            if (schema != null) {
                jdbc.execute("SET LOCAL search_path TO \"" + schema + "\"");
            }
            return work.apply(jdbc);
        });
    }

    private static String tenantSchema(String tenantId) {
        if (tenantId == null || !TENANT.matcher(tenantId).matches()) {
            throw new CustomException("INVALID_TENANT_ID", "Tenant '" + tenantId + "' cannot be used as a schema name");
        }
        return tenantId;
    }

    private Map<String, Object> document(String json) {
        if (json == null) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, DOCUMENT);
        } catch (Exception e) {
            throw new CustomException("INBOX_DOCUMENT_INVALID", "Inbox document is not a JSON object");
        }
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}
