package org.egov.inbox.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** What the process definitions client keeps, and what it asks workflow for again. */
class ProcessDefinitionsClientTest {

    private static final String URL = "http://workflow.test/workflow/v3/process/definition?processCode=Demo.show";
    private static final String DEFINITION = "{\"definitions\":[{\"code\":\"Demo.show\",\"sla\":10,"
            + "\"states\":[{\"code\":\"PENDING\",\"type\":\"INTERMEDIATE\",\"actions\":[{\"roles\":[\"APPROVER\"]}]}]}]}";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

    private ProcessDefinitionsClient client(long expiryMinutes, int capacity) {
        return new ProcessDefinitionsClient(restTemplate, "http://workflow.test", "/workflow/v3/process/definition", expiryMinutes, capacity);
    }

    @Test
    void aProcessIsReadOncePerTenant() {
        server.expect(ExpectedCount.times(2), requestTo(URL)).andRespond(withSuccess(DEFINITION, MediaType.APPLICATION_JSON));
        ProcessDefinitionsClient client = client(10, 100);

        assertEquals(1, client.businessServices("STUDIO", List.of("Demo.show")).size());
        assertEquals(1, client.businessServices("STUDIO", List.of("Demo.show")).size());
        assertEquals(1, client.businessServices("STUDIOB", List.of("Demo.show")).size());
        server.verify();
    }

    @Test
    void aProcessWithoutDefinitionsIsAskedForAgain() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"definitions\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(DEFINITION, MediaType.APPLICATION_JSON));
        ProcessDefinitionsClient client = client(10, 100);

        assertEquals(0, client.businessServices("STUDIO", List.of("Demo.show")).size());
        assertEquals(1, client.businessServices("STUDIO", List.of("Demo.show")).size());
        server.verify();
    }

    @Test
    void aFailedCallIsNotKept() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess(DEFINITION, MediaType.APPLICATION_JSON));
        ProcessDefinitionsClient client = client(10, 100);

        assertEquals("EG_WF_ERROR", assertThrows(CustomException.class,
                () -> client.businessServices("STUDIO", List.of("Demo.show"))).getCode());
        assertEquals(1, client.businessServices("STUDIO", List.of("Demo.show")).size());
        server.verify();
    }

    @Test
    void capacityZeroKeepsNothing() {
        server.expect(ExpectedCount.times(2), requestTo(URL)).andRespond(withSuccess(DEFINITION, MediaType.APPLICATION_JSON));
        ProcessDefinitionsClient client = client(10, 0);

        client.businessServices("STUDIO", List.of("Demo.show"));
        client.businessServices("STUDIO", List.of("Demo.show"));
        server.verify();
    }
}
