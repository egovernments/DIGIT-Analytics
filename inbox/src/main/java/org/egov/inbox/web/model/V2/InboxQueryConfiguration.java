package org.egov.inbox.web.model.V2;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class InboxQueryConfiguration {

    public static final String DEFAULT_STORE = "ELASTICSEARCH";
    public static final String DEFAULT_STATE_PATH = "Data.currentProcessInstance.state.uuid.keyword";
    public static final String DEFAULT_LAST_MODIFIED_PATH = "Data.auditDetails.lastModifiedTime";

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

    @JsonProperty("allowedSearchCriteria")
    private List<SearchParam> allowedSearchCriteria;

    @JsonProperty("sortBy")
    private SortParam sortParam;

    @JsonProperty("sourceFilterPathList")
    private List<String> sourceFilterPathList;

    public String getStore() {
        return store == null || store.isBlank() ? DEFAULT_STORE : store;
    }

    public String getStatePath() {
        return statePath == null || statePath.isBlank() ? DEFAULT_STATE_PATH : statePath;
    }

    public String getLastModifiedPath() {
        return lastModifiedPath == null || lastModifiedPath.isBlank() ? DEFAULT_LAST_MODIFIED_PATH : lastModifiedPath;
    }

}
