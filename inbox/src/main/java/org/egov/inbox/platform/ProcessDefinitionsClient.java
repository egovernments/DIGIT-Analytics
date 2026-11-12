package org.egov.inbox.platform;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.egov.inbox.web.model.workflow.Action;
import org.egov.inbox.web.model.workflow.BusinessService;
import org.egov.inbox.web.model.workflow.State;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The workflow of each requested process from DIGIT 3.0 workflow v3, as the business services the
 * inbox already works with: {@code GET <workflow.host><workflow.businessservice.search.path>?processCode=..}
 * (on 3.0 the path setting is {@code /workflow/v3/process/definition}), one call per process code.
 *
 * <p>A process can have several versions and applications stay on the version they started on, so
 * the versions are merged: a state is in the result if any version has it, an action role on any
 * version counts, and the SLAs come from the latest version that has the state. The state's code is
 * its id ({@code uuid}) and name; {@code applicationStatus} is the state's
 * {@code additionalDetails.applicationStatus} when set, else the code.
 */
@Component
@ConditionalOnProperty(name = InboxPlatform.PROPERTY, havingValue = InboxPlatform.GATEWAY)
public class ProcessDefinitionsClient {

    private static final String TERMINAL = "TERMINAL";

    private final RestTemplate restTemplate;
    private final String url;

    public ProcessDefinitionsClient(RestTemplate restTemplate,
                                    @Value("${workflow.host}") String host,
                                    @Value("${workflow.businessservice.search.path}") String path) {
        this.restTemplate = restTemplate;
        this.url = MdmsRecordsClient.trimSlash(host) + path;
    }

    public List<BusinessService> businessServices(String tenantId, List<String> processCodes) {
        List<BusinessService> result = new ArrayList<>();
        if (processCodes == null) {
            return result;
        }
        for (String code : new LinkedHashSet<>(processCodes)) {
            BusinessService merged = merge(tenantId, code, definitions(tenantId, code));
            if (merged != null) {
                result.add(merged);
            }
        }
        return result;
    }

    private List<Map<String, Object>> definitions(String tenantId, String code) {
        URI uri = UriComponentsBuilder.fromUriString(url).queryParam("processCode", code).encode().build().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set(InboxPlatform.TENANT_ID, tenantId);
        headers.set(InboxPlatform.CLIENT_ID_HEADER, InboxPlatform.CLIENT_ID);
        try {
            Map<String, Object> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            Object definitions = response == null ? null : response.get("definitions");
            return definitions instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        } catch (RestClientException e) {
            throw new CustomException("EG_WF_ERROR", "Failed to fetch workflow process definitions for " + code);
        }
    }

    private static BusinessService merge(String tenantId, String code, List<Map<String, Object>> definitions) {
        if (definitions.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> oldestFirst = new ArrayList<>(definitions);
        oldestFirst.sort(Comparator.comparingLong(d -> createdTime(map(d.get("process")))));

        Map<String, State> states = new LinkedHashMap<>();
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        Long processSla = null;
        for (Map<String, Object> definition : oldestFirst) {
            Map<String, Object> process = map(definition.get("process"));
            if (process.get("sla") instanceof Number sla) {
                processSla = sla.longValue();
            }
            for (Object detail : list(definition.get("states"))) {
                Map<String, Object> stateDetail = map(detail);
                Map<String, Object> s = map(stateDetail.get("state"));
                String stateCode = (String) s.get("code");
                if (stateCode == null) {
                    continue;
                }
                State state = states.computeIfAbsent(stateCode, c -> new State());
                state.setUuid(stateCode);
                state.setState(stateCode);
                state.setTenantId(tenantId);
                state.setBusinessServiceId(code);
                state.setSla(s.get("sla") instanceof Number sla ? sla.longValue() : null);
                Object applicationStatus = map(s.get("additionalDetails")).get("applicationStatus");
                state.setApplicationStatus(applicationStatus instanceof String a && !a.isBlank() ? a : stateCode);
                state.setIsStartState(Boolean.TRUE.equals(s.get("isInitial")) || "INITIAL".equals(s.get("type")));
                state.setIsTerminateState(s.get("type") instanceof String t && t.startsWith(TERMINAL));
                Set<String> stateRoles = roles.computeIfAbsent(stateCode, c -> new LinkedHashSet<>());
                for (Object a : list(stateDetail.get("actions"))) {
                    for (Object role : list(map(a).get("roles"))) {
                        stateRoles.add(String.valueOf(role));
                    }
                }
            }
        }

        List<State> merged = new ArrayList<>();
        states.forEach((stateCode, state) -> {
            Action action = new Action();
            action.setTenantId(tenantId);
            action.setCurrentState(stateCode);
            action.setRoles(new ArrayList<>(roles.get(stateCode)));
            state.setActions(roles.get(stateCode).isEmpty() ? List.of() : List.of(action));
            merged.add(state);
        });

        BusinessService businessService = new BusinessService();
        businessService.setTenantId(tenantId);
        businessService.setUuid(code);
        businessService.setBusinessService(code);
        businessService.setBusinessServiceSla(processSla);
        businessService.setStates(merged);
        return businessService;
    }

    private static long createdTime(Map<String, Object> process) {
        return map(process.get("auditDetail")).get("createdTime") instanceof Number n ? n.longValue() : 0L;
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
