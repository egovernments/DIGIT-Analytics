package org.egov.inbox.repository.builder.V2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egov.inbox.store.InboxQuery;
import org.egov.inbox.web.model.V2.InboxQueryConfiguration;
import org.egov.inbox.web.model.V2.SearchParam;
import org.egov.inbox.web.model.V2.SortParam;
import org.egov.inbox.web.model.workflow.ProcessInstanceSearchCriteria;
import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

/** The two Elasticsearch fields that used to be fixed now come from the module's configuration. */
class InboxQueryBuilderTest {

    private final InboxQueryBuilder builder = new InboxQueryBuilder();

    @Test
    void statusCountAggregatesOnTheDefaultStateField() {
        Map<String, Object> query = builder.getStatusCountQuery(query(config(null, null)));
        assertEquals("Data.currentProcessInstance.state.uuid.keyword",
                JsonPath.read(query, "$.aggs.statusCount.terms.field"));
    }

    @Test
    void statusCountAggregatesOnTheConfiguredStateField() {
        Map<String, Object> query = builder.getStatusCountQuery(query(config("Data.workflowStatus.keyword", null)));
        assertEquals("Data.workflowStatus.keyword", JsonPath.read(query, "$.aggs.statusCount.terms.field"));
    }

    @Test
    void statusCountKeepsTheRequestCriteria() {
        Map<String, Object> query = builder.getStatusCountQuery(query(config(null, null)));
        assertEquals(List.of("s1"),
                JsonPath.read(query, "$.query.bool.must[?(@.terms)].terms['Data.currentProcessInstance.state.uuid.keyword'][0]"));
    }

    @Test
    void nearingSlaRangesOnTheDefaultLastModifiedField() {
        Map<String, Object> query = builder.getNearingSlaCountQuery(query(config(null, null)), 100L, 200L);
        assertEquals(List.of(100L), JsonPath.read(query, "$.query.bool.must[*].range['Data.auditDetails.lastModifiedTime'].gte"));
        assertEquals(List.of(200L), JsonPath.read(query, "$.query.bool.must[*].range['Data.auditDetails.lastModifiedTime'].lte"));
    }

    @Test
    void nearingSlaRangesOnTheConfiguredLastModifiedField() {
        Map<String, Object> query = builder.getNearingSlaCountQuery(query(config(null, "Data.updatedAt")), 100L, 200L);
        assertEquals(List.of(100L), JsonPath.read(query, "$.query.bool.must[*].range['Data.updatedAt'].gte"));
    }

    private static InboxQueryConfiguration config(String statePath, String lastModifiedPath) {
        return InboxQueryConfiguration.builder()
                .module("m").index("i")
                .statePath(statePath).lastModifiedPath(lastModifiedPath)
                .sortParam(SortParam.builder().path("Data.auditDetails.createdTime").order(SortParam.Order.DESC).build())
                .allowedSearchCriteria(List.of(SearchParam.builder().name("status").path("Data.currentProcessInstance.state.uuid.keyword").build()))
                .build();
    }

    private static InboxQuery query(InboxQueryConfiguration config) {
        ProcessInstanceSearchCriteria process = new ProcessInstanceSearchCriteria();
        process.setStatus(List.of("s1"));
        return new InboxQuery(config, "dev", new HashMap<>(), process, 0, 10);
    }
}
