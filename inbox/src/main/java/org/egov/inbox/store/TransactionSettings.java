package org.egov.inbox.store;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Settings for the current transaction only, in one round trip and with bound values:
 * {@code set_config(.., true)} is {@code SET LOCAL}, and {@code quote_ident} makes the tenant's schema
 * an identifier on the server, so no SQL text is built from the tenant id.
 */
public final class TransactionSettings {

    private static final String TIMEOUT = "SELECT set_config('statement_timeout', ?, true)";
    private static final String TIMEOUT_AND_SCHEMA = "SELECT set_config('statement_timeout', ?, true),"
            + " set_config('search_path', quote_ident(?), true)";

    private TransactionSettings() {
    }

    /**
     * Sets the statement timeout and, when {@code schema} is not null, the search path to that schema,
     * until the surrounding transaction ends.
     */
    public static void apply(JdbcTemplate jdbc, int statementTimeoutMillis, String schema) {
        String timeout = String.valueOf(statementTimeoutMillis);
        if (schema == null) {
            jdbc.queryForRowSet(TIMEOUT, timeout);
        } else {
            jdbc.queryForRowSet(TIMEOUT_AND_SCHEMA, timeout, schema);
        }
    }
}
