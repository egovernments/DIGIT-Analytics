package org.egov.inbox.v2;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.egov.InboxApplication;
import org.egov.encryption.config.DecryptionPolicyConfiguration;
import org.egov.encryption.config.EncryptionPolicyConfiguration;
import org.egov.encryption.masking.MaskingService;
import org.egov.inbox.service.ElasticSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pins what inbox v2 does today, before the store layer is added: for each scenario in
 * {@code v2/scenarios}, the Elasticsearch queries it sends (in order), the MDMS and workflow calls it
 * makes, and the answer it gives. MDMS, workflow and Elasticsearch are mocked on the RestTemplate.
 *
 * <p>Run with {@code -Dgolden.write=true} to (re)write {@code src/test/resources/v2/golden}.
 */
@SpringBootTest(classes = InboxApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InboxV2ParityTest {

    private static final String MDMS = "http://mdms.test";
    private static final String WORKFLOW = "http://workflow.test";
    private static final String ES = "http://es.test/";
    private static final Path GOLDEN_DIR = Paths.get("src/test/resources/v2/golden");
    private static final Pattern NOW = Pattern.compile("@now-(\\d+)d(\\d+)h");
    private static final long HOUR = 60L * 60 * 1000;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    // enc-client loads its policies from MDMS at startup with its own client; v2 doesn't use them.
    @MockBean
    private EncryptionPolicyConfiguration encryptionPolicyConfiguration;
    @MockBean
    private DecryptionPolicyConfiguration decryptionPolicyConfiguration;
    @MockBean
    private MaskingService maskingService;
    // v1 (water/sewerage) looks up its system user in egov-user at startup; v2 doesn't use it.
    @MockBean
    private ElasticSearchService v1ElasticSearchService;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer server;
    private final List<ObjectNode> esCalls = new ArrayList<>();
    private final TreeSet<String> otherCalls = new TreeSet<>();
    private long now;
    private final Map<Long, String> fixtureTimes = new HashMap<>();

    @BeforeEach
    void backends() throws IOException {
        now = System.currentTimeMillis();
        esCalls.clear();
        otherCalls.clear();
        fixtureTimes.clear();
        JsonNode es = resolveTimes(read("v2/fixtures/es-responses.json"));

        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(MDMS)))
                .andRespond(recordOther(read("v2/fixtures/mdms-inbox.json")));
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(WORKFLOW)))
                .andRespond(recordOther(read("v2/fixtures/workflow-business-services.json")));
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(ES)))
                .andRespond(request -> {
                    String url = request.getURI().toString();
                    JsonNode body = JSON.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    ObjectNode call = JSON.createObjectNode();
                    call.put("url", url);
                    call.set("body", normalizeEsBody(body));
                    esCalls.add(call);
                    String answer;
                    if (url.endsWith("/_count")) {
                        answer = body.toString().contains("\"Data.auditDetails.lastModifiedTime\":{") ? "nearingSlaCount" : "count";
                    } else {
                        answer = body.has("aggs") ? "aggregations" : "hits";
                    }
                    return withSuccess(es.get(answer).toString(), MediaType.APPLICATION_JSON).createResponse(request);
                });
    }

    static Stream<String> scenarios() throws IOException {
        try (Stream<Path> files = Files.list(Paths.get("src/test/resources/v2/scenarios"))) {
            return files.map(p -> p.getFileName().toString().replace(".json", "")).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void matchesGolden(String scenario) throws Exception {
        JsonNode spec = read("v2/scenarios/" + scenario + ".json");
        MvcResult result = mvc.perform(post(spec.get("endpoint").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spec.get("request").toString()))
                .andReturn();

        ObjectNode actual = JSON.createObjectNode();
        actual.put("description", spec.get("description").asText());
        actual.put("status", result.getResponse().getStatus());
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        actual.set("response", normalizeResponse(content.isEmpty() ? JSON.nullNode() : JSON.readTree(content)));
        actual.set("elasticsearchCalls", JSON.valueToTree(esCalls));
        actual.set("otherCalls", JSON.valueToTree(otherCalls));

        Path golden = GOLDEN_DIR.resolve(scenario + ".json");
        if (Boolean.getBoolean("golden.write")) {
            Files.createDirectories(GOLDEN_DIR);
            Files.writeString(golden, JSON.writeValueAsString(JSON.treeToValue(actual, Object.class)) + "\n");
            return;
        }
        if (!Files.exists(golden)) {
            fail("No golden file " + golden + "; run with -Dgolden.write=true and review it");
        }
        assertEquals(JSON.readTree(golden.toFile()), JSON.readTree(JSON.writeValueAsString(actual)),
                () -> "Differs from " + golden + ". Actual:\n" + pretty(actual));
    }

    /** Answers with a fixture and records "METHOD url body" (RequestInfo dropped: it only echoes the caller). */
    private ResponseCreator recordOther(JsonNode fixture) {
        return request -> {
            String body = ((MockClientHttpRequest) request).getBodyAsString();
            JsonNode tree = body.isEmpty() ? JSON.nullNode() : JSON.readTree(body);
            if (tree instanceof ObjectNode o) {
                o.remove("RequestInfo");
            }
            otherCalls.add(request.getMethod() + " " + request.getURI() + " " + JSON.writeValueAsString(sorted(tree)).replaceAll("\\s+", ""));
            return withSuccess(fixture.toString(), MediaType.APPLICATION_JSON).createResponse(request);
        };
    }

    /**
     * The nearing-SLA range depends on the clock: keep only its width. Clause order inside bool.must
     * has no meaning to Elasticsearch, so sort it.
     */
    private JsonNode normalizeEsBody(JsonNode body) {
        JsonNode copy = body.deepCopy();
        JsonNode must = copy.path("query").path("bool").path("must");
        if (must instanceof ArrayNode clauses) {
            for (JsonNode clause : clauses) {
                JsonNode range = clause.path("range").path("Data.auditDetails.lastModifiedTime");
                if (range instanceof ObjectNode r && r.has("gte") && r.has("lte")) {
                    long width = r.get("lte").asLong() - r.get("gte").asLong();
                    if (Math.abs(r.get("lte").asLong() - now) > 60_000) {
                        fail("nearing-SLA range does not end at the current time: " + r);
                    }
                    r.removeAll();
                    r.put("lte", "<now>");
                    r.put("gte", "<now - " + width + " ms>");
                }
            }
            List<JsonNode> list = new ArrayList<>();
            clauses.forEach(list::add);
            list.sort(Comparator.comparing(JsonNode::toString));
            clauses.removeAll();
            list.forEach(clauses::add);
        }
        return copy;
    }

    /** Fixture times go back to their "@now-..." form; statusMap is built from a HashMap: order it by statusid. */
    private JsonNode normalizeResponse(JsonNode response) {
        response = restoreTimes(response);
        JsonNode statusMap = response.path("statusMap");
        if (statusMap instanceof ArrayNode entries) {
            List<JsonNode> list = new ArrayList<>();
            entries.forEach(list::add);
            list.sort(Comparator.comparing(n -> n.path("statusid").asText()));
            entries.removeAll();
            list.forEach(entries::add);
        }
        return response;
    }

    private JsonNode resolveTimes(JsonNode node) {
        if (node.isTextual()) {
            Matcher m = NOW.matcher(node.asText());
            if (m.matches()) {
                long ago = Long.parseLong(m.group(1)) * 24 * HOUR + Long.parseLong(m.group(2)) * HOUR;
                fixtureTimes.put(now - ago, node.asText());
                return JsonNodeFactory.instance.numberNode(now - ago);
            }
            return node;
        }
        if (node instanceof ObjectNode o) {
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                e.setValue(resolveTimes(e.getValue()));
            }
        } else if (node instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                a.set(i, resolveTimes(a.get(i)));
            }
        }
        return node;
    }

    private JsonNode restoreTimes(JsonNode node) {
        if (node.isIntegralNumber() && fixtureTimes.containsKey(node.asLong())) {
            return JsonNodeFactory.instance.textNode(fixtureTimes.get(node.asLong()));
        }
        if (node instanceof ObjectNode o) {
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                e.setValue(restoreTimes(e.getValue()));
            }
        } else if (node instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                a.set(i, restoreTimes(a.get(i)));
            }
        }
        return node;
    }

    private static Object sorted(JsonNode tree) throws IOException {
        return JSON.treeToValue(tree, Object.class);
    }

    private static String pretty(JsonNode node) {
        try {
            return JSON.writeValueAsString(JSON.treeToValue(node, Object.class));
        } catch (IOException e) {
            return node.toString();
        }
    }

    private static JsonNode read(String resource) throws IOException {
        try (InputStream in = InboxV2ParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing test resource " + resource);
            }
            return JSON.readTree(in);
        }
    }
}
