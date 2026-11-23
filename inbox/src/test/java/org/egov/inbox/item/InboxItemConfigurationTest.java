package org.egov.inbox.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Which failed writes wait for the database (retried until it is back) and which are dead-lettered. */
class InboxItemConfigurationTest {

    @Test
    void noConnectionFromThePoolMeansTheDatabaseIsUnavailable() {
        assertTrue(InboxItemConfiguration.databaseUnavailable(listener(new CannotCreateTransactionException(
                "Could not open JDBC Connection for transaction",
                new SQLTransientConnectionException("Connection is not available, request timed out")))));
    }

    @Test
    void aLostConnectionOrAServerShuttingDownMeansTheDatabaseIsUnavailable() {
        assertTrue(InboxItemConfiguration.databaseUnavailable(listener(new DataAccessResourceFailureException(
                "I/O error", new SQLException("An I/O error occurred while sending to the backend.", "08006")))));
        assertTrue(InboxItemConfiguration.databaseUnavailable(listener(new DataAccessResourceFailureException(
                "shutdown", new SQLException("terminating connection due to administrator command", "57P01")))));
        assertTrue(InboxItemConfiguration.databaseUnavailable(listener(new DataAccessResourceFailureException(
                "starting", new SQLException("the database system is starting up", "57P03")))));
    }

    @Test
    void otherFailuresAreNotTheDatabaseBeingUnavailable() {
        assertFalse(InboxItemConfiguration.databaseUnavailable(listener(new InboxItem.InvalidInboxItemException("bad"))));
        assertFalse(InboxItemConfiguration.databaseUnavailable(listener(new InboxItemWriter.MissingTenantSchemaException("T"))));
        assertFalse(InboxItemConfiguration.databaseUnavailable(listener(new BadSqlGrammarException(
                "upsert", "INSERT ...", new SQLException("relation does not exist", "42P01")))));
        assertFalse(InboxItemConfiguration.databaseUnavailable(listener(new DataAccessResourceFailureException(
                "timeout", new SQLException("canceling statement due to statement timeout", "57014")))));
    }

    private static Exception listener(Exception cause) {
        return new ListenerExecutionFailedException("Listener method threw", cause);
    }
}
