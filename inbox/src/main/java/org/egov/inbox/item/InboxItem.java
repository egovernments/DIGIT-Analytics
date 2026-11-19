package org.egov.inbox.item;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One business object as a product sends it to the inbox (topic {@code inbox.item.topic}), to be kept
 * in the inbox's own table for the Postgres store.
 *
 * <ul>
 *   <li>{@code tenantId}: the tenant; its schema holds the row.</li>
 *   <li>{@code inboxModule}: the module of the product's {@code INBOX.InboxQueryConfiguration}; searches
 *       of that configuration see only its own items.</li>
 *   <li>{@code module}, {@code businessService}, {@code businessId}: what the item is; the business id
 *       is unique within the inbox module.</li>
 *   <li>{@code state}: the current workflow state id; {@code assignees}: user ids it is assigned to.</li>
 *   <li>{@code attributes}: other values the configuration may search on ({@code attributes.<key>}).</li>
 *   <li>{@code data}: the document the inbox returns as the business object.</li>
 *   <li>{@code createdTime}, {@code lastModifiedTime}: epoch millis; an item older than the stored one
 *       is ignored, so a late or replayed message cannot move an item back.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboxItem(String tenantId,
                        String inboxModule,
                        String module,
                        String businessService,
                        String businessId,
                        String state,
                        List<String> assignees,
                        Map<String, Object> attributes,
                        Map<String, Object> data,
                        Long createdTime,
                        Long lastModifiedTime) {

    /** Tenant ids are schema names (as in the Postgres store). */
    static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_]{1,63}");

    /** Refuses an item the inbox cannot store or find again. */
    void validate() {
        if (tenantId == null || !TENANT.matcher(tenantId).matches()) {
            throw new InvalidInboxItemException("tenantId '" + tenantId + "' is not a schema name");
        }
        required("inboxModule", inboxModule);
        required("module", module);
        required("businessId", businessId);
        if (data == null) {
            throw new InvalidInboxItemException("data is required");
        }
        if (createdTime == null || lastModifiedTime == null) {
            throw new InvalidInboxItemException("createdTime and lastModifiedTime are required");
        }
        if (assignees != null && assignees.stream().anyMatch(a -> a == null || a.isBlank())) {
            throw new InvalidInboxItemException("assignees must not hold blank ids");
        }
    }

    private static void required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidInboxItemException(name + " is required");
        }
    }

    /** An item that will never be stored, however often it is retried. */
    public static class InvalidInboxItemException extends RuntimeException {
        public InvalidInboxItemException(String message) {
            super(message);
        }
    }
}
