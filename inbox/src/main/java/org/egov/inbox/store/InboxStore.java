package org.egov.inbox.store;

import java.util.List;
import java.util.Map;

/**
 * Where a module's inbox documents live. A module's configuration names its store; every store
 * answers the same four questions for an {@link InboxQuery}, with documents in the same shape (the
 * business object the UI reads, including {@code currentProcessInstance} and {@code auditDetails}).
 */
public interface InboxStore {

    /** The name a module configuration uses in {@code store}, upper case. */
    String name();

    /** One page of documents, in the query's sort order. */
    List<Map<String, Object>> search(InboxQuery query);

    /** Number of documents matching the query. */
    Integer count(InboxQuery query);

    /** Number of matching documents per current workflow state id. */
    Map<String, Integer> countByState(InboxQuery query);

    /** Number of matching documents last modified between {@code from} and {@code to} (epoch millis, inclusive). */
    Integer countModifiedBetween(InboxQuery query, long from, long to);
}
