package org.egov.inbox.platform;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.egov.inbox.web.model.V2.InboxQueryConfiguration;
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

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads a module's inbox configuration from DIGIT 3.0 MDMS v2 records:
 * {@code GET <egov.mdms.host><egov.mdms.search.endpoint>?schemaCode=INBOX.InboxQueryConfiguration&module=..}
 * in the tenant ({@code X-Tenant-ID}). On 3.0 the endpoint setting is {@code /mdms-v2/v2}.
 */
@Component
@ConditionalOnProperty(name = InboxPlatform.PROPERTY, havingValue = InboxPlatform.GATEWAY)
public class MdmsRecordsClient {

    static final String SCHEMA_CODE = "INBOX.InboxQueryConfiguration";

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final String url;

    public MdmsRecordsClient(RestTemplate restTemplate, ObjectMapper mapper,
                             @Value("${egov.mdms.host}") String host,
                             @Value("${egov.mdms.search.endpoint}") String endpoint) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.url = trimSlash(host) + endpoint;
    }

    public InboxQueryConfiguration inboxConfiguration(String tenantId, String module) {
        URI uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("schemaCode", SCHEMA_CODE)
                .queryParam("module", module)
                .queryParam("isActive", true)
                .queryParam("limit", 10)
                .encode().build().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set(InboxPlatform.TENANT_ID, tenantId);
        headers.set(InboxPlatform.CLIENT_ID_HEADER, InboxPlatform.CLIENT_ID);

        Map<String, Object> response;
        try {
            response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
        } catch (RestClientException e) {
            throw new CustomException("CONFIG_ERROR", "Error in fetching inbox query configuration from MDMS for: " + module);
        }
        List<Map<String, Object>> records = response == null ? null : (List<Map<String, Object>>) response.get("mdms");
        if (records != null) {
            for (Map<String, Object> record : records) {
                Object data = record.get("data");
                if (data instanceof Map<?, ?> d && module.equals(d.get("module"))) {
                    return mapper.convertValue(d, InboxQueryConfiguration.class);
                }
            }
        }
        throw new CustomException("CONFIG_ERROR", "Inbox Query Configuration not found in MDMS response for: " + module);
    }

    static String trimSlash(String host) {
        return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }
}
