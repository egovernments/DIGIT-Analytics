package org.egov.inbox.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egov.inbox.web.model.V2.InboxQueryConfiguration;
import org.egov.inbox.web.model.V2.SearchParam;
import org.egov.inbox.web.model.V2.SortParam;
import org.egov.inbox.web.model.workflow.ProcessInstanceSearchCriteria;
import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Postgres store against a real database: two tenant schemas with the same relation, read through
 * a user that may only select. Rows hold their tenant in the document, so a leak shows.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresInboxStoreTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static PostgresInboxStore store;
    private static PostgresInboxStore sharedSchemaStore;

    @BeforeAll
    static void database() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            for (String schema : List.of("STUDIO", "STUDIOB")) {
                s.execute("CREATE SCHEMA \"" + schema + "\"");
                s.execute("CREATE TABLE \"" + schema + "\".inbox_application (application_number text, business_service text, module text,"
                        + " workflow_status text, assignees jsonb, applicant_name text, created_time bigint, last_modified_time bigint, data jsonb)");
            }
            insert(s, "STUDIO", "APP-1", "show", "PENDING_APPROVAL", "[\"u-emp\"]", "Ram_Kumar", 1000, 5000);
            insert(s, "STUDIO", "APP-2", "show", "PENDING_VERIFICATION", "[]", "Sita 100%", 2000, 6000);
            insert(s, "STUDIO", "APP-3", "show", "APPROVED", "[]", "Ravi", 3000, 7000);
            insert(s, "STUDIO", "APP-4", "other", "PENDING_APPROVAL", "[\"u-emp\"]", "RamXKumar", 4000, 8000);
            insert(s, "STUDIOB", "APP-1", "show", "PENDING_APPROVAL", "[\"u-emp\"]", "Ram_Kumar", 1000, 5000);

            s.execute("CREATE TABLE public.inbox_shared (application_number text, business_service text, module text, tenant_id text,"
                    + " workflow_status text, assignees jsonb, applicant_name text, created_time bigint, last_modified_time bigint, data jsonb)");
            s.execute("INSERT INTO public.inbox_shared VALUES ('A','show','Demo','pb','PENDING_APPROVAL','[]','x',1,1,'{\"applicationNumber\":\"A\"}'),"
                    + " ('B','show','Demo','mh','PENDING_APPROVAL','[]','x',2,2,'{\"applicationNumber\":\"B\"}')");

            // Like Studio's application table: timestamp without time zone, written in UTC.
            s.execute("CREATE TABLE \"STUDIO\".inbox_ts (application_number text, workflow_status text, created_at timestamp, updated_at timestamp, data jsonb)");
            s.execute("CREATE INDEX idx_ts_updated_at ON \"STUDIO\".inbox_ts (updated_at)");
            s.execute("INSERT INTO \"STUDIO\".inbox_ts VALUES"
                    + " ('TS-1','PENDING_APPROVAL','2026-11-01 00:00:00','2026-11-10 00:00:00','{\"applicationNumber\":\"TS-1\"}'),"
                    + " ('TS-2','PENDING_APPROVAL','2026-11-05 00:00:00','2026-11-11 12:00:00','{\"applicationNumber\":\"TS-2\"}')");

            s.execute("CREATE ROLE inbox_reader LOGIN PASSWORD 'reader'");
            s.execute("GRANT USAGE ON SCHEMA \"STUDIO\", \"STUDIOB\", public TO inbox_reader");
            s.execute("GRANT SELECT ON \"STUDIO\".inbox_application, \"STUDIOB\".inbox_application, public.inbox_shared, \"STUDIO\".inbox_ts TO inbox_reader");
        }
        store = new PostgresInboxStore(POSTGRES.getJdbcUrl(), "inbox_reader", "reader", 2, true, 5000, new ObjectMapper());
        sharedSchemaStore = new PostgresInboxStore(POSTGRES.getJdbcUrl(), "inbox_reader", "reader", 2, false, 5000, new ObjectMapper());
    }

    @AfterAll
    static void close() {
        store.destroy();
        sharedSchemaStore.destroy();
    }

    private static void insert(Statement s, String tenant, String number, String businessService, String state, String assignees,
                               String applicant, long created, long modified) throws Exception {
        s.execute("INSERT INTO \"" + tenant + "\".inbox_application VALUES ('" + number + "','" + businessService + "','Demo','" + state
                + "','" + assignees + "','" + applicant + "'," + created + "," + modified
                + ",'{\"applicationNumber\":\"" + number + "\",\"tenant\":\"" + tenant + "\",\"auditDetails\":{\"createdTime\":" + created + "}}')");
    }

    @Test
    void searchReturnsTheTenantsMatchingDocumentsNewestFirst() {
        List<Map<String, Object>> docs = store.search(query("STUDIO", criteria(), process("PENDING_APPROVAL", "PENDING_VERIFICATION"), 0, 10));
        assertEquals(List.of("APP-2", "APP-1"), numbers(docs));
        assertEquals("STUDIO", docs.get(0).get("tenant"));
        assertEquals(Map.of("createdTime", 2000), docs.get(0).get("auditDetails"));
    }

    @Test
    void searchPagesAndTakesTheRequestedOrder() {
        assertEquals(List.of("APP-1"), numbers(store.search(query("STUDIO", criteria(), process("PENDING_APPROVAL", "PENDING_VERIFICATION"), 1, 1))));
        Map<String, Object> ascending = criteria();
        ascending.put("sortOrder", "ASC");
        assertEquals(List.of("APP-1", "APP-2"), numbers(store.search(query("STUDIO", ascending, process("PENDING_APPROVAL", "PENDING_VERIFICATION"), 0, 10))));
    }

    @Test
    void countsMatchTheSearch() {
        InboxQuery q = query("STUDIO", criteria(), process("PENDING_APPROVAL", "PENDING_VERIFICATION", "APPROVED"), 0, 10);
        assertEquals(3, store.count(q));
        assertEquals(Map.of("PENDING_APPROVAL", 1, "PENDING_VERIFICATION", 1, "APPROVED", 1), store.countByState(q));
        assertEquals(2, store.countModifiedBetween(q, 5500, 7000));
    }

    @Test
    void anotherTenantSeesOnlyItsOwnRows() {
        List<Map<String, Object>> docs = store.search(query("STUDIOB", criteria(), process("PENDING_APPROVAL", "PENDING_VERIFICATION"), 0, 10));
        assertEquals(List.of("APP-1"), numbers(docs));
        assertEquals("STUDIOB", docs.get(0).get("tenant"));
    }

    @Test
    void assigneeIsLookedUpInTheAssigneeList() {
        ProcessInstanceSearchCriteria p = process("PENDING_APPROVAL", "PENDING_VERIFICATION");
        p.setAssignee("u-emp");
        assertEquals(List.of("APP-1"), numbers(store.search(query("STUDIO", criteria(), p, 0, 10))));
        p.setAssignee("someone-else");
        assertEquals(0, store.count(query("STUDIO", criteria(), p, 0, 10)));
    }

    @Test
    void wildcardMatchesLikeCharactersLiterally() {
        Map<String, Object> c = criteria();
        c.put("applicantName", "100%");
        assertEquals(List.of("APP-2"), numbers(store.search(query("STUDIO", c, process("PENDING_APPROVAL", "PENDING_VERIFICATION"), 0, 10))));
        Map<String, Object> underscore = new HashMap<>();
        underscore.put("applicantName", "m_K");
        assertEquals(List.of("APP-1"), numbers(store.search(query("STUDIO", underscore, process("PENDING_APPROVAL"), 0, 10))));
    }

    @Test
    void wildcardListMeansEveryValueMustMatch() {
        Map<String, Object> c = new HashMap<>();
        c.put("applicantName", List.of("Ram", "Kumar"));
        assertEquals(List.of("APP-4", "APP-1"), numbers(store.search(query("STUDIO", c, process("PENDING_APPROVAL"), 0, 10))));
        c.put("applicantName", List.of("Ram", "Sita"));
        assertEquals(0, store.count(query("STUDIO", c, process("PENDING_APPROVAL"), 0, 10)));
    }

    @Test
    void rangeAndListCriteria() {
        Map<String, Object> c = new HashMap<>();
        c.put("fromDate", 1500);
        c.put("toDate", 3500);
        assertEquals(List.of("APP-3", "APP-2"), numbers(store.search(query("STUDIO", c, process("PENDING_VERIFICATION", "APPROVED", "PENDING_APPROVAL"), 0, 10))));
        Map<String, Object> numbersIn = new HashMap<>();
        numbersIn.put("applicationNumber", List.of("APP-1", "APP-3"));
        assertEquals(List.of("APP-3", "APP-1"), numbers(store.search(query("STUDIO", numbersIn, process("APPROVED", "PENDING_APPROVAL"), 0, 10))));
        numbersIn.put("applicationNumber", List.of());
        assertEquals(0, store.count(query("STUDIO", numbersIn, process("APPROVED", "PENDING_APPROVAL"), 0, 10)));
    }

    @Test
    void valuesCannotChangeTheQuery() {
        Map<String, Object> c = criteria();
        c.put("applicationNumber", "APP-1' OR '1'='1");
        assertEquals(0, store.count(query("STUDIO", c, process("PENDING_APPROVAL"), 0, 10)));
        c.put("applicationNumber", Map.of("$ne", "x"));
        assertEquals("INVALID_SEARCH_CRITERIA", code(() -> store.count(query("STUDIO", c, process("PENDING_APPROVAL"), 0, 10))));
    }

    @Test
    void aTenantThatIsNotAPlainNameIsRefused() {
        assertEquals("INVALID_TENANT_ID", code(() -> store.count(query("STUDIO\"; DROP SCHEMA \"STUDIOB\" CASCADE; --", criteria(), process("PENDING_APPROVAL"), 0, 10))));
        assertEquals("INVALID_TENANT_ID", code(() -> store.count(query(null, criteria(), process("PENDING_APPROVAL"), 0, 10))));
        assertEquals(1, store.count(query("STUDIOB", criteria(), process("PENDING_APPROVAL"), 0, 10)));
    }

    @Test
    void aTenantWithoutSchemaFindsNoRelation() {
        assertThrows(BadSqlGrammarException.class, () -> store.count(query("NOSUCHTENANT", criteria(), process("PENDING_APPROVAL"), 0, 10)));
    }

    @Test
    void aCriterionTheConfigurationDoesNotAllowIsRefused() {
        Map<String, Object> c = criteria();
        c.put("mobileNumber", "9999999999");
        CustomException e = assertThrows(CustomException.class, () -> store.count(query("STUDIO", c, process("PENDING_APPROVAL"), 0, 10)));
        assertEquals("INVALID_SEARCH_CRITERIA", e.getCode());
        assertEquals("Search criteria 'mobileNumber' is not allowed for module studio", e.getMessage());
    }

    @Test
    void configurationNamesMustBePlainIdentifiers() {
        InboxQueryConfiguration badIndex = config();
        badIndex.setIndex("inbox_application; DROP TABLE x");
        assertEquals("INBOX_STORE_CONFIG_INVALID", code(() -> store.count(new InboxQuery(badIndex, "STUDIO", criteria(), process("PENDING_APPROVAL"), 0, 10))));
        InboxQueryConfiguration otherSchema = config();
        otherSchema.setIndex("\"STUDIOB\".inbox_application");
        assertEquals("INBOX_STORE_CONFIG_INVALID", code(() -> store.count(new InboxQuery(otherSchema, "STUDIO", criteria(), process("PENDING_APPROVAL"), 0, 10))));
        InboxQueryConfiguration qualified = config();
        qualified.setIndex("studiob.inbox_application");
        assertEquals("INBOX_STORE_CONFIG_INVALID", code(() -> store.count(new InboxQuery(qualified, "STUDIO", criteria(), process("PENDING_APPROVAL"), 0, 10))));
        InboxQueryConfiguration badSort = config();
        badSort.setSortParam(SortParam.builder().path("created_time DESC, (SELECT 1)").order(SortParam.Order.ASC).build());
        assertEquals("INBOX_STORE_CONFIG_INVALID", code(() -> store.search(new InboxQuery(badSort, "STUDIO", criteria(), process("PENDING_APPROVAL"), 0, 10))));
    }

    @Test
    void withoutSchemaPerTenantTheTenantIsAColumn() {
        InboxQueryConfiguration shared = config();
        shared.setIndex("public.inbox_shared");
        InboxQuery q = new InboxQuery(shared, "pb", criteria(), processOf("pb", "PENDING_APPROVAL"), 0, 10);
        assertEquals("INBOX_STORE_CONFIG_INVALID", code(() -> sharedSchemaStore.count(q)));

        List<SearchParam> withTenant = new ArrayList<>(shared.getAllowedSearchCriteria());
        withTenant.add(SearchParam.builder().name("tenantId").path("tenant_id").build());
        shared.setAllowedSearchCriteria(withTenant);
        assertEquals(List.of("A"), numbers(sharedSchemaStore.search(q)));
    }

    // 2026-11-01T00:00Z, 2026-11-05T00:00Z, 2026-11-10T00:00Z, 2026-11-11T12:00Z
    private static final long NOV_1 = 1793491200000L, NOV_5 = 1793836800000L, NOV_10 = 1794268800000L, NOV_11_NOON = 1794398400000L;

    @Test
    void epochMillisAreComparedWithUtcTimestampColumns() {
        InboxQueryConfiguration ts = timestampConfig();
        ProcessInstanceSearchCriteria p = process("PENDING_APPROVAL");
        assertEquals(List.of("TS-2", "TS-1"), numbers(store.search(new InboxQuery(ts, "STUDIO", new HashMap<>(), p, 0, 10))));

        Map<String, Object> from = new HashMap<>();
        from.put("fromDate", NOV_5);
        assertEquals(List.of("TS-2"), numbers(store.search(new InboxQuery(ts, "STUDIO", from, p, 0, 10))));
        from.put("fromDate", NOV_5 + 1);
        assertEquals(0, store.count(new InboxQuery(ts, "STUDIO", from, p, 0, 10)));

        Map<String, Object> to = new HashMap<>();
        to.put("toDate", NOV_1);
        assertEquals(List.of("TS-1"), numbers(store.search(new InboxQuery(ts, "STUDIO", to, p, 0, 10))));

        InboxQuery all = new InboxQuery(ts, "STUDIO", new HashMap<>(), p, 0, 10);
        assertEquals(2, store.countModifiedBetween(all, NOV_10, NOV_11_NOON));
        assertEquals(1, store.countModifiedBetween(all, NOV_10 + 1, NOV_11_NOON));
        assertEquals(1, store.countModifiedBetween(all, NOV_10, NOV_11_NOON - 1));
    }

    @Test
    void aTimestampCriterionTakesEpochMillis() {
        Map<String, Object> c = new HashMap<>();
        c.put("fromDate", "2026-11-05");
        assertEquals("INVALID_SEARCH_CRITERIA", code(() -> store.count(new InboxQuery(timestampConfig(), "STUDIO", c, process("PENDING_APPROVAL"), 0, 10))));
    }

    @Test
    void theTimestampColumnsIndexIsUsable() throws Exception {
        PostgresInboxQueryBuilder.Sql sql = new PostgresInboxQueryBuilder(true)
                .countModifiedBetween(new InboxQuery(timestampConfig(), "STUDIO", new HashMap<>(), process("PENDING_APPROVAL"), 0, 10), NOV_10, NOV_11_NOON);
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("SET search_path TO \"STUDIO\"");
            s.execute("SET enable_seqscan = off");
            try (java.sql.PreparedStatement explain = c.prepareStatement("EXPLAIN " + sql.text())) {
                for (int i = 0; i < sql.params().size(); i++) {
                    explain.setObject(i + 1, sql.params().get(i));
                }
                StringBuilder plan = new StringBuilder();
                try (java.sql.ResultSet rs = explain.executeQuery()) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
                org.junit.jupiter.api.Assertions.assertTrue(plan.toString().contains("idx_ts_updated_at"), plan.toString());
            }
        }
    }

    private static InboxQueryConfiguration timestampConfig() {
        return InboxQueryConfiguration.builder()
                .module("studio").store("POSTGRES").index("inbox_ts")
                .statePath("workflow_status").lastModifiedPath("updated_at").documentPath("data")
                .timestampColumns(List.of("created_at", "updated_at"))
                .sortParam(SortParam.builder().path("created_at").order(SortParam.Order.DESC).build())
                .allowedSearchCriteria(List.of(
                        SearchParam.builder().name("status").path("workflow_status").build(),
                        SearchParam.builder().name("fromDate").path("created_at").operator(SearchParam.Operator.GTE).build(),
                        SearchParam.builder().name("toDate").path("created_at").operator(SearchParam.Operator.LTE).build()))
                .build();
    }

    private static InboxQueryConfiguration config() {
        return InboxQueryConfiguration.builder()
                .module("studio").store("POSTGRES").index("inbox_application")
                .statePath("workflow_status").lastModifiedPath("last_modified_time").documentPath("data")
                .sortParam(SortParam.builder().path("created_time").order(SortParam.Order.DESC).build())
                .allowedSearchCriteria(new ArrayList<>(List.of(
                        SearchParam.builder().name("status").path("workflow_status").build(),
                        SearchParam.builder().name("applicationNumber").path("application_number").build(),
                        SearchParam.builder().name("module").path("module").operator(SearchParam.Operator.EQUAL).build(),
                        SearchParam.builder().name("businessService").path("business_service").build(),
                        SearchParam.builder().name("assignee").path("assignees").operator(SearchParam.Operator.CONTAINS).build(),
                        SearchParam.builder().name("applicantName").path("applicant_name").operator(SearchParam.Operator.WILDCARD).build(),
                        SearchParam.builder().name("fromDate").path("created_time").operator(SearchParam.Operator.GTE).build(),
                        SearchParam.builder().name("toDate").path("created_time").operator(SearchParam.Operator.LTE).build())))
                .build();
    }

    private static InboxQuery query(String tenant, Map<String, Object> criteria, ProcessInstanceSearchCriteria process, int offset, int limit) {
        return new InboxQuery(config(), tenant, criteria, process, offset, limit);
    }

    private static Map<String, Object> criteria() {
        Map<String, Object> c = new HashMap<>();
        c.put("businessService", "show");
        c.put("module", "Demo");
        return c;
    }

    private static ProcessInstanceSearchCriteria process(String... states) {
        return processOf(null, states);
    }

    private static ProcessInstanceSearchCriteria processOf(String tenant, String... states) {
        ProcessInstanceSearchCriteria p = new ProcessInstanceSearchCriteria();
        p.setTenantId(tenant);
        p.setStatus(List.of(states));
        return p;
    }

    private static List<String> numbers(List<Map<String, Object>> docs) {
        return docs.stream().map(d -> (String) d.get("applicationNumber")).toList();
    }

    private static String code(Runnable call) {
        return assertThrows(CustomException.class, call::run).getCode();
    }
}
