package org.egov.inbox.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.egov.inbox.store.InboxQuery;
import org.egov.inbox.store.PostgresInboxStore;
import org.egov.inbox.web.model.V2.InboxQueryConfiguration;
import org.egov.inbox.web.model.V2.SearchParam;
import org.egov.inbox.web.model.V2.SortParam;
import org.egov.inbox.web.model.workflow.ProcessInstanceSearchCriteria;
import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Items written into the inbox's own table and read back through the Postgres store, against a real
 * database. The inbox's user may use and create in the tenant schemas, nothing more (no CREATE on the
 * database, so it could not create a schema).
 */
@Testcontainers(disabledWithoutDocker = true)
class InboxItemWriterTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    // 2026-11-01T00:00Z, 2026-11-05T00:00Z, 2026-11-10T00:00Z
    private static final long NOV_1 = 1793491200000L, NOV_5 = 1793836800000L, NOV_10 = 1794268800000L;

    private static InboxItemWriter writer;
    private static PostgresInboxStore store;

    @BeforeAll
    static void database() throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE inbox_user LOGIN PASSWORD 'inbox'");
            for (String schema : List.of("STUDIO", "STUDIOB", "RACE")) {
                s.execute("CREATE SCHEMA \"" + schema + "\"");
                s.execute("GRANT USAGE, CREATE ON SCHEMA \"" + schema + "\" TO inbox_user");
            }
        }
        writer = new InboxItemWriter(POSTGRES.getJdbcUrl(), "inbox_user", "inbox", 4, 5000, new ObjectMapper());
        store = new PostgresInboxStore(POSTGRES.getJdbcUrl(), "inbox_user", "inbox", 2, true, 5000, new ObjectMapper());

        writer.write(item("STUDIO", "public-services", "APP-1", "PENDING", List.of("u-emp"), NOV_1, NOV_10, Map.of("serviceCode", "TL")));
        writer.write(item("STUDIO", "public-services", "APP-2", "APPROVED", List.of(), NOV_5, NOV_5, Map.of("serviceCode", "PT")));
        writer.write(item("STUDIO", "other-product", "APP-1", "PENDING", List.of("u-emp"), NOV_1, NOV_1, Map.of()));
        writer.write(item("STUDIOB", "public-services", "APP-9", "PENDING", null, NOV_1, NOV_1, null));
    }

    @AfterAll
    static void close() {
        writer.destroy();
        store.destroy();
    }

    @Test
    void theTableIsCreatedInEachTenantsSchemaOnly() throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT table_schema FROM information_schema.tables WHERE table_name = 'inbox_item' ORDER BY 1")) {
            List<String> schemas = new ArrayList<>();
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
            assertTrue(schemas.containsAll(List.of("STUDIO", "STUDIOB")), schemas.toString());
            assertFalse(schemas.contains("public"), schemas.toString());
        }
    }

    @Test
    void aSearchSeesItsTenantAndItsModuleOnly() {
        assertEquals(List.of("STUDIO/public-services/APP-2", "STUDIO/public-services/APP-1"),
                ids(store.search(query("STUDIO", "public-services", new HashMap<>(), process("PENDING", "APPROVED")))));
        assertEquals(List.of("STUDIO/other-product/APP-1"),
                ids(store.search(query("STUDIO", "other-product", new HashMap<>(), process("PENDING", "APPROVED")))));
        assertEquals(List.of("STUDIOB/public-services/APP-9"),
                ids(store.search(query("STUDIOB", "public-services", new HashMap<>(), process("PENDING")))));
    }

    @Test
    void countsAssigneesAttributesAndTimesWorkOnTheTable() {
        InboxQuery all = query("STUDIO", "public-services", new HashMap<>(), process("PENDING", "APPROVED"));
        assertEquals(2, store.count(all));
        assertEquals(Map.of("PENDING", 1, "APPROVED", 1), store.countByState(all));
        assertEquals(1, store.countModifiedBetween(all, NOV_10, NOV_10));

        ProcessInstanceSearchCriteria mine = process("PENDING", "APPROVED");
        mine.setAssignee("u-emp");
        assertEquals(List.of("STUDIO/public-services/APP-1"), ids(store.search(query("STUDIO", "public-services", new HashMap<>(), mine))));

        Map<String, Object> byAttribute = new HashMap<>();
        byAttribute.put("serviceCode", "PT");
        assertEquals(List.of("STUDIO/public-services/APP-2"), ids(store.search(query("STUDIO", "public-services", byAttribute, process("PENDING", "APPROVED")))));

        Map<String, Object> from = new HashMap<>();
        from.put("fromDate", NOV_5);
        assertEquals(List.of("STUDIO/public-services/APP-2"), ids(store.search(query("STUDIO", "public-services", from, process("PENDING", "APPROVED")))));
    }

    @Test
    void aNewerItemReplacesTheStoredOneAndAnOlderOneIsIgnored() {
        writer.write(item("RACE", "public-services", "R-1", "PENDING", List.of("a"), NOV_1, NOV_5, null));
        assertTrue(writer.write(item("RACE", "public-services", "R-1", "APPROVED", List.of("b"), NOV_1, NOV_10, null)));
        assertFalse(writer.write(item("RACE", "public-services", "R-1", "PENDING", List.of("a"), NOV_1, NOV_5, null)));
        assertTrue(writer.write(item("RACE", "public-services", "R-1", "APPROVED", List.of("b"), NOV_1, NOV_10, null)), "a replay of the stored item is written again");

        List<Map<String, Object>> docs = store.search(query("RACE", "public-services", new HashMap<>(), process("PENDING", "APPROVED")));
        assertEquals(1, docs.size());
        assertEquals("APPROVED", docs.get(0).get("state"));
    }

    @Test
    void itemsCreatedAtTheSameTimePageInTheirIdOrder() {
        for (String id : List.of("T-2", "T-3", "T-1")) {
            writer.write(item("STUDIOB", "ties", id, "PENDING", List.of(), NOV_5, NOV_5, null));
        }
        List<String> pages = new ArrayList<>();
        for (int offset = 0; offset < 3; offset++) {
            InboxQuery page = new InboxQuery(config("ties"), "STUDIOB", new HashMap<>(), process("PENDING"), offset, 1);
            pages.addAll(ids(store.search(page)));
        }
        assertEquals(List.of("STUDIOB/ties/T-3", "STUDIOB/ties/T-2", "STUDIOB/ties/T-1"), pages);
    }

    @Test
    void manyFirstItemsOfANewTenantAtOnceCreateTheTableOnce() throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA \"PARALLEL\"");
            s.execute("GRANT USAGE, CREATE ON SCHEMA \"PARALLEL\" TO inbox_user");
        }
        InboxItemWriter fresh = new InboxItemWriter(POSTGRES.getJdbcUrl(), "inbox_user", "inbox", 4, 5000, new ObjectMapper());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> writes = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String id = "P-" + i;
                writes.add(() -> fresh.write(item("PARALLEL", "public-services", id, "PENDING", List.of(), NOV_1, NOV_1, null)));
            }
            for (Future<Boolean> f : pool.invokeAll(writes)) {
                assertTrue(f.get());
            }
        } finally {
            pool.shutdown();
            fresh.destroy();
        }
        assertEquals(8, store.count(query("PARALLEL", "public-services", new HashMap<>(), process("PENDING"))));
    }

    @Test
    void aTenantWithoutSchemaIsNotCreated() throws Exception {
        assertThrows(InboxItemWriter.MissingTenantSchemaException.class,
                () -> writer.write(item("NOSCHEMA", "public-services", "X-1", "PENDING", List.of(), NOV_1, NOV_1, null)));
        try (Connection c = admin(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_namespace WHERE nspname = 'NOSCHEMA'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void invalidItemsAreRefusedBeforeTheDatabase() {
        assertThrows(InboxItem.InvalidInboxItemException.class,
                () -> writer.write(item("STUDIO\"; DROP SCHEMA \"STUDIOB\" CASCADE; --", "public-services", "X", "PENDING", List.of(), NOV_1, NOV_1, null)));
        assertThrows(InboxItem.InvalidInboxItemException.class,
                () -> writer.write(item("STUDIO", " ", "X", "PENDING", List.of(), NOV_1, NOV_1, null)));
        assertThrows(InboxItem.InvalidInboxItemException.class,
                () -> writer.write(item("STUDIO", "public-services", null, "PENDING", List.of(), NOV_1, NOV_1, null)));
        assertThrows(InboxItem.InvalidInboxItemException.class,
                () -> writer.write(new InboxItem("STUDIO", "public-services", "Demo", "show", "X", "PENDING", List.of(), null, null, NOV_1, NOV_1)));
        assertThrows(InboxItem.InvalidInboxItemException.class,
                () -> writer.write(item("STUDIO", "public-services", "X", "PENDING", List.of(), NOV_1, null, null)));
        assertThrows(InboxItem.InvalidInboxItemException.class,
                () -> writer.write(item("STUDIO", "public-services", "X", "PENDING", java.util.Arrays.asList("u", null), NOV_1, NOV_1, null)));
        assertEquals(1, store.count(query("STUDIOB", "public-services", new HashMap<>(), process("PENDING"))));
    }

    @Test
    void aSearchOfTheInboxTableNeedsTheConfigurationsModule() {
        InboxQueryConfiguration noModule = config(null);
        CustomException e = assertThrows(CustomException.class,
                () -> store.count(new InboxQuery(noModule, "STUDIO", new HashMap<>(), process("PENDING"), 0, 10)));
        assertEquals("INBOX_STORE_CONFIG_INVALID", e.getCode());
    }

    @Test
    void containsNeedsAColumnAndFieldKeysMustBePlainNames() {
        InboxQueryConfiguration containsOnField = config("public-services");
        containsOnField.setAllowedSearchCriteria(List.of(
                SearchParam.builder().name("tag").path("attributes.tags").operator(SearchParam.Operator.CONTAINS).build()));
        Map<String, Object> tag = new HashMap<>();
        tag.put("tag", "x");
        assertEquals("INBOX_STORE_CONFIG_INVALID", assertThrows(CustomException.class,
                () -> store.count(new InboxQuery(containsOnField, "STUDIO", tag, null, 0, 10))).getCode());

        InboxQueryConfiguration badKey = config("public-services");
        badKey.setAllowedSearchCriteria(List.of(
                SearchParam.builder().name("tag").path("attributes.x' OR '1'='1").build()));
        assertEquals("INBOX_STORE_CONFIG_INVALID", assertThrows(CustomException.class,
                () -> store.count(new InboxQuery(badKey, "STUDIO", tag, null, 0, 10))).getCode());
    }

    private static InboxItem item(String tenant, String inboxModule, String id, String state, List<String> assignees,
                                  long created, Long modified, Map<String, Object> attributes) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", tenant + "/" + inboxModule + "/" + id);
        data.put("state", state);
        return new InboxItem(tenant, inboxModule, "Demo", "show", id, state, assignees, attributes, data, created, modified);
    }

    private static InboxQueryConfiguration config(String module) {
        return InboxQueryConfiguration.builder()
                .module(module).store("POSTGRES").index(InboxItemTable.NAME)
                .statePath("state").lastModifiedPath("updated_at").documentPath("data")
                .timestampColumns(List.of("created_at", "updated_at"))
                .sortParam(SortParam.builder().path("created_at").order(SortParam.Order.DESC).build())
                .allowedSearchCriteria(List.of(
                        SearchParam.builder().name("status").path("state").build(),
                        SearchParam.builder().name("assignee").path("assignees").operator(SearchParam.Operator.CONTAINS).build(),
                        SearchParam.builder().name("serviceCode").path("attributes.serviceCode").build(),
                        SearchParam.builder().name("fromDate").path("created_at").operator(SearchParam.Operator.GTE).build()))
                .build();
    }

    private static InboxQuery query(String tenant, String module, Map<String, Object> criteria, ProcessInstanceSearchCriteria process) {
        return new InboxQuery(config(module), tenant, criteria, process, 0, 10);
    }

    private static ProcessInstanceSearchCriteria process(String... states) {
        ProcessInstanceSearchCriteria p = new ProcessInstanceSearchCriteria();
        p.setStatus(List.of(states));
        return p;
    }

    private static List<String> ids(List<Map<String, Object>> docs) {
        return docs.stream().map(d -> (String) d.get("id")).toList();
    }

    private static Connection admin() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
