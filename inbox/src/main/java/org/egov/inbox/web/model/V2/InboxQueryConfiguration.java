package org.egov.inbox.web.model.V2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;
import java.util.Locale;

import org.egov.inbox.item.InboxItemTable;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class InboxQueryConfiguration {

    public static final String DEFAULT_STORE = "ELASTICSEARCH";
    public static final String DEFAULT_STATE_PATH = "Data.currentProcessInstance.state.uuid.keyword";
    public static final String DEFAULT_LAST_MODIFIED_PATH = "Data.auditDetails.lastModifiedTime";
    public static final String DEFAULT_DOCUMENT_PATH = "data";
    public static final String POSTGRES_STORE = "POSTGRES";

    @JsonProperty("module")
    private String module;

    /** Where the module's inbox documents live: ELASTICSEARCH (default) or another registered store. */
    @JsonProperty("store")
    private String store;

    /** Elasticsearch index, or the store's relation. */
    @JsonProperty("index")
    private String index;

    /** Field holding the current workflow state id, for the count per state. */
    @JsonProperty("statePath")
    private String statePath;

    /** Field holding the last-modified time in epoch millis, for the nearing-SLA count. */
    @JsonProperty("lastModifiedPath")
    private String lastModifiedPath;

    /** Postgres store: the jsonb column holding the document returned as the business object. */
    @JsonProperty("documentPath")
    private String documentPath;

    /**
     * Postgres store: columns of type timestamp (without time zone, holding UTC). Epoch-millis values
     * compared with them are converted in SQL, so the column's own index is used.
     */
    @JsonProperty("timestampColumns")
    private List<String> timestampColumns;

    @JsonProperty("allowedSearchCriteria")
    private List<SearchParam> allowedSearchCriteria;

    @JsonProperty("sortBy")
    private SortParam sortParam;

    @JsonProperty("sourceFilterPathList")
    private List<String> sourceFilterPathList;

    public String getStore() {
        return store == null || store.isBlank() ? DEFAULT_STORE : store;
    }

    /**
     * The configured state field, else the default of the store: {@code state} for the inbox's own
     * table, the Elasticsearch path for Elasticsearch, none for another Postgres relation (whose
     * columns only its configuration knows).
     */
    public String getStatePath() {
        if (statePath != null && !statePath.isBlank()) {
            return statePath;
        }
        return readsInboxItems() ? "state" : isPostgres() ? null : DEFAULT_STATE_PATH;
    }

    public String getDocumentPath() {
        return documentPath == null || documentPath.isBlank() ? DEFAULT_DOCUMENT_PATH : documentPath;
    }

    /** As {@link #getStatePath()}: {@code updated_at} for the inbox's own table. */
    public String getLastModifiedPath() {
        if (lastModifiedPath != null && !lastModifiedPath.isBlank()) {
            return lastModifiedPath;
        }
        return readsInboxItems() ? "updated_at" : isPostgres() ? null : DEFAULT_LAST_MODIFIED_PATH;
    }

    /** The configured timestamp columns, else those of the inbox's own table. */
    public List<String> getTimestampColumns() {
        if (timestampColumns == null && readsInboxItems()) {
            return List.of("created_at", "updated_at");
        }
        return timestampColumns;
    }

    /** The configured sort, else newest first for the inbox's own table. */
    public SortParam getSortParam() {
        if (sortParam == null && readsInboxItems()) {
            return SortParam.builder().path("created_at").order(SortParam.Order.DESC).build();
        }
        return sortParam;
    }

    private boolean isPostgres() {
        return POSTGRES_STORE.equals(getStore().toUpperCase(Locale.ROOT));
    }

    /** The Postgres store reading the inbox's own table, whose columns the inbox knows. */
    private boolean readsInboxItems() {
        return isPostgres() && InboxItemTable.NAME.equals(index);
    }

}
