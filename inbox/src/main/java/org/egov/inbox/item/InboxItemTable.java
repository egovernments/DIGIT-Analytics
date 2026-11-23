package org.egov.inbox.item;

import java.util.List;

/**
 * The inbox's own table, one per tenant schema. The inbox creates it on the first item of a tenant;
 * products never write it directly.
 *
 * <p>Times are {@code timestamp} without time zone holding UTC, which is how the Postgres store
 * compares epoch-millis criteria ({@code timestampColumns}). A later change to the table is added here
 * as another idempotent statement ({@code ADD COLUMN IF NOT EXISTS}, ...), since every existing
 * tenant runs the list again after a restart.
 *
 * <p>Indexes, for the searches an inbox UI sends (module and business service always given):
 * {@code inbox_item_search_idx} finds and counts a service's items by state;
 * {@code inbox_item_page_idx} reads a service's items newest first, so a page stops after its rows
 * instead of sorting every match. {@code attributes.<key>} and WILDCARD criteria have no index: a
 * product that searches on them adds one for its keys (an expression index, or pg_trgm).
 */
public final class InboxItemTable {

    /** The relation a module configuration names in {@code index} to read the inbox's own items. */
    public static final String NAME = "inbox_item";

    /** The column scoping a search to its configuration's module. */
    public static final String INBOX_MODULE_COLUMN = "inbox_module";

    /** The business object's id, unique within an inbox module. */
    public static final String BUSINESS_ID_COLUMN = "business_id";

    static final List<String> DDL = List.of(
            "CREATE TABLE IF NOT EXISTS " + NAME + " ("
                    + "inbox_module text NOT NULL, "
                    + "module text NOT NULL, "
                    + "business_service text, "
                    + "business_id text NOT NULL, "
                    + "state text, "
                    + "assignees jsonb NOT NULL DEFAULT '[]'::jsonb, "
                    + "attributes jsonb NOT NULL DEFAULT '{}'::jsonb, "
                    + "data jsonb NOT NULL, "
                    + "created_at timestamp NOT NULL, "
                    + "updated_at timestamp NOT NULL, "
                    + "PRIMARY KEY (inbox_module, business_id))",
            "CREATE INDEX IF NOT EXISTS inbox_item_search_idx ON " + NAME
                    + " (inbox_module, module, business_service, state, created_at DESC)",
            "CREATE INDEX IF NOT EXISTS inbox_item_page_idx ON " + NAME
                    + " (inbox_module, module, business_service, created_at DESC, business_id DESC)",
            "CREATE INDEX IF NOT EXISTS inbox_item_updated_idx ON " + NAME + " (inbox_module, updated_at)",
            "CREATE INDEX IF NOT EXISTS inbox_item_assignees_idx ON " + NAME + " USING gin (assignees jsonb_path_ops)");

    static final String UPSERT = "INSERT INTO " + NAME
            + " (inbox_module, module, business_service, business_id, state, assignees, attributes, data, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,"
            + " to_timestamp(? / 1000.0) AT TIME ZONE 'UTC', to_timestamp(? / 1000.0) AT TIME ZONE 'UTC')"
            + " ON CONFLICT (inbox_module, business_id) DO UPDATE SET"
            + " module = EXCLUDED.module, business_service = EXCLUDED.business_service, state = EXCLUDED.state,"
            + " assignees = EXCLUDED.assignees, attributes = EXCLUDED.attributes, data = EXCLUDED.data,"
            + " created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at"
            + " WHERE " + NAME + ".updated_at <= EXCLUDED.updated_at";

    private InboxItemTable() {
    }
}
