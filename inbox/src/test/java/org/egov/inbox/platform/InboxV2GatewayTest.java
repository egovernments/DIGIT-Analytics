package org.egov.inbox.platform;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.egov.InboxApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Inbox v2 on DIGIT 3.0, end to end: caller and tenant from Kong's headers, the configuration from
 * MDMS v2 records, states from workflow v3 process definitions, documents from a Studio tenant's view
 * in Postgres (read by a user that may only read the view).
 *
 * <p>No enc-client or v1 bean is replaced here: the context starts only because on 3.0 they are not
 * created at startup (they would call the 2.9 MDMS search API and egov-user).
 */
@SpringBootTest(classes = InboxApplication.class, properties = {
        "inbox.platform=3.0",
        "egov.mdms.search.endpoint=/mdms-v2/v2",
        "workflow.businessservice.search.path=/workflow/v3/process/definition",
        "inbox.postgres-store.username=inbox_reader",
        "inbox.postgres-store.password=reader",
        "inbox.postgres-store.schema-per-tenant=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class InboxV2GatewayTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("inbox.postgres-store.url", POSTGRES::getJdbcUrl);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RestTemplate restTemplate;

    private final List<String> coreCalls = new ArrayList<>();

    @BeforeAll
    static void studioTenants() throws Exception {
        String schemaSql;
        try (InputStream in = InboxV2GatewayTest.class.getClassLoader().getResourceAsStream("v3/studio-schema.sql")) {
            schemaSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE inbox_reader LOGIN PASSWORD 'reader'");
            for (String tenant : List.of("STUDIO", "STUDIOB")) {
                s.execute(schemaSql.replace("{schema}", tenant));
            }
            // Times like public-service writes them: timestamp without time zone, in UTC.
            String now = "(now() AT TIME ZONE 'UTC')";
            s.execute("INSERT INTO \"STUDIO\".application VALUES"
                    + " ('APP-1','Demo','show','ACTIVE','PENDING_APPROVAL','{\"k\":1}','{\"businessService\":\"Demo.show\",\"assignees\":[\"u-emp\"]}',"
                    + now + " - interval '6 days', " + now + " - interval '2 days 1 hour'),"
                    + " ('APP-2','Demo','show','ACTIVE','PENDING_VERIFICATION',null,'{\"businessService\":\"Demo.show\",\"assignees\":null}',"
                    + now + " - interval '4 days 1 hour', " + now + " - interval '1 day'),"
                    + " ('APP-3','Demo','show','ACTIVE','APPROVED',null,'{\"businessService\":\"Demo.show\"}',"
                    + now + " - interval '3 days', " + now + " - interval '3 days')");
            s.execute("INSERT INTO \"STUDIOB\".application VALUES"
                    + " ('APP-1','Demo','show','ACTIVE','PENDING_APPROVAL',null,'{\"businessService\":\"Demo.show\"}',"
                    + now + " - interval '1 day', " + now + " - interval '1 day')");
        }
    }

    @BeforeEach
    void coreServices() throws Exception {
        coreCalls.clear();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith("http://mdms.test/mdms-v2/v2?")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(record(read("v3/mdms-inbox-records.json")));
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith("http://workflow.test/workflow/v3/process/definition?")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(record(read("v3/workflow-process-definitions.json")));
    }

    @Test
    void anEmployeeSeesTheApplicationsInTheStatesTheyCanActOn() throws Exception {
        JsonNode response = search("STUDIO", "DEMO_SHOW_APPROVER,STUDIO_EMPLOYEE", Map.of());

        assertEquals(List.of("APP-1", "APP-2"), numbers(response));
        assertEquals(2, response.get("totalCount").asInt());
        assertEquals(Map.of("PENDING_APPROVAL", "PENDINGAPPROVAL:1", "PENDING_VERIFICATION", "PENDINGVERIFICATION:1"), statusMap(response));
        // Process SLA 10 days: nearing = last modified in the last 6 days.
        assertEquals(2, response.get("nearingSlaCount").asInt());
        // APP-1: state SLA 5 days, 2 days 1 hour in the state. APP-2: no state SLA, process SLA 10 days, 4 days 1 hour old.
        assertEquals(3, response.at("/items/0/businessObject/serviceSla").asInt());
        assertEquals(6, response.at("/items/1/businessObject/serviceSla").asInt());
        assertEquals("PENDING_APPROVAL", response.at("/items/0/ProcessInstance/state/state").asText());
        assertEquals("Demo.show", response.at("/items/0/ProcessInstance/businessService").asText());

        assertEquals(List.of(
                "GET http://mdms.test/mdms-v2/v2?schemaCode=INBOX.InboxQueryConfiguration&module=public-services&isActive=true&limit=10 X-Tenant-ID=STUDIO X-Client-ID=inbox",
                "GET http://workflow.test/workflow/v3/process/definition?processCode=Demo.show X-Tenant-ID=STUDIO X-Client-ID=inbox"),
                coreCalls.stream().distinct().sorted().toList());
    }

    @Test
    void assignedToMeUsesTheAssigneeList() throws Exception {
        JsonNode response = search("STUDIO", "DEMO_SHOW_APPROVER", Map.of("assignee", "u-emp"));
        assertEquals(List.of("APP-1"), numbers(response));
        assertEquals("u-emp", response.at("/items/0/ProcessInstance/assignes/0/uuid").asText());
    }

    @Test
    void rolesComeFromTheTokenNotTheBody() throws Exception {
        // Only the verifier role: actionable on PENDING_VERIFICATION alone (on both versions).
        assertEquals(List.of("APP-2"), numbers(search("STUDIO", "DEMO_SHOW_VERIFIER", Map.of())));
        // A role that acts on nothing: empty inbox, although the body claims SUPERUSER.
        JsonNode none = search("STUDIO", "STUDIO_EMPLOYEE", Map.of());
        assertEquals(List.of(), numbers(none));
        assertEquals(0, none.get("totalCount").asInt());
    }

    @Test
    void theTenantComesFromTheTokenNotTheBody() throws Exception {
        JsonNode response = search("STUDIOB", "DEMO_SHOW_APPROVER", Map.of());
        assertEquals(List.of("APP-1"), numbers(response));
        assertEquals(1, response.get("totalCount").asInt());
        coreCalls.forEach(call -> assertEquals(true, call.contains("X-Tenant-ID=STUDIOB"), call));
    }

    @Test
    void aRequestWithoutGatewayHeadersIsRefused() throws Exception {
        MvcResult result = mvc.perform(post("/v2/_search").contentType(MediaType.APPLICATION_JSON).content(body(Map.of()))).andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("TENANT_HEADER_MISSING", JSON.readTree(result.getResponse().getContentAsString()).at("/Errors/0/code").asText());
    }

    private JsonNode search(String tenant, String roles, Map<String, Object> extraCriteria) throws Exception {
        MvcResult result = mvc.perform(post("/v2/_search")
                        .header("X-Tenant-ID", tenant)
                        .header("X-User-ID", "u-emp")
                        .header("X-JWT-realm-roles", roles)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(extraCriteria)))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(), content);
        return JSON.readTree(content);
    }

    /** What the Studio UI sends, with a body tenant and caller that must both be ignored. */
    private static String body(Map<String, Object> extraCriteria) throws Exception {
        Map<String, Object> criteria = new TreeMap<>(Map.of("businessService", "show", "module", "Demo", "sortOrder", "ASC"));
        criteria.putAll(extraCriteria);
        Map<String, Object> request = Map.of(
                "RequestInfo", Map.of("userInfo", Map.of("uuid", "someone-else", "tenantId", "OTHER",
                        "roles", List.of(Map.of("code", "SUPERUSER", "tenantId", "OTHER"), Map.of("code", "DEMO_SHOW_APPROVER", "tenantId", "OTHER")))),
                "inbox", Map.of("tenantId", "OTHER", "limit", 10, "offset", 0,
                        "processSearchCriteria", Map.of("businessService", List.of("Demo.show"), "moduleName", "public-services", "tenantId", "OTHER"),
                        "moduleSearchCriteria", criteria));
        return JSON.writeValueAsString(request);
    }

    private ResponseCreator record(JsonNode answer) {
        return request -> {
            coreCalls.add(request.getMethod() + " " + request.getURI() + " X-Tenant-ID=" + request.getHeaders().getFirst("X-Tenant-ID")
                    + " X-Client-ID=" + request.getHeaders().getFirst("X-Client-ID"));
            return withSuccess(answer.toString(), MediaType.APPLICATION_JSON).createResponse(request);
        };
    }

    private static List<String> numbers(JsonNode response) {
        List<String> numbers = new ArrayList<>();
        response.get("items").forEach(item -> numbers.add(item.at("/businessObject/applicationNumber").asText()));
        return numbers;
    }

    private static Map<String, String> statusMap(JsonNode response) {
        Map<String, String> map = new TreeMap<>();
        response.get("statusMap").forEach(s -> map.put(s.get("statusid").asText(), s.get("applicationstatus").asText() + ":" + s.get("count").asInt()));
        return map;
    }

    private static JsonNode read(String resource) throws Exception {
        try (InputStream in = InboxV2GatewayTest.class.getClassLoader().getResourceAsStream(resource)) {
            return JSON.readTree(in);
        }
    }
}
