package org.egov.inbox.repository;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.egov.inbox.config.InboxConfiguration;
import org.egov.inbox.web.model.InboxSearchCriteria;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ElasticSearchRepository {

    private InboxConfiguration config;

    private ElasticSearchQueryBuilder queryBuilder;

    @Autowired
    private RestTemplate restTemplate;

    private ObjectMapper mapper;

    @Autowired
    public ElasticSearchRepository(InboxConfiguration config, ElasticSearchQueryBuilder queryBuilder, RestTemplate restTemplate, ObjectMapper mapper) {
        this.config = config;
        this.queryBuilder = queryBuilder;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }


    /**
     * Searches records from elasticsearch based on the fuzzy search criteria
     *
     * @param criteria
     * @return
     */
    public Object elasticSearchApplications(InboxSearchCriteria criteria, List<String> uuids) {


        String url = getESURL(criteria);

        String searchQuery = queryBuilder.getSearchQuery(criteria, uuids);

        HttpHeaders headers = getHttpHeaders();
        
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> requestEntity = new HttpEntity<>(searchQuery, headers);
        ResponseEntity response = null;
        try {
            response = restTemplate.postForEntity(url, requestEntity, Object.class);

        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("WNS_ES_SEARCH_ERROR", "Failed to fetch data from ES for W&S");
        }

        return response.getBody();

    }


    /**
     * Generates elasticsearch search url from application properties
     *
     * @return
     */
    private String getESURL(InboxSearchCriteria criteria) {

        StringBuilder builder = new StringBuilder(config.getIndexServiceHost());
        if (criteria.getProcessSearchCriteria().getModuleName().equals("ws-services"))
            builder.append(config.getEsWSIndex());
        else if (criteria.getProcessSearchCriteria().getModuleName().equals("sw-services")) {
            builder.append(config.getEsSWIndex());
        }
        builder.append(config.getIndexServiceHostSearchEndpoint());

        return builder.toString();
    }
    private HttpHeaders getHttpHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", getESEncodedCredentials());
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setContentType(MediaType.APPLICATION_JSON);

        List<MediaType> mediaTypes = new ArrayList<>();
        mediaTypes.add(MediaType.APPLICATION_JSON);
        headers.setAccept(mediaTypes);
        return headers;
    }

    public String getESEncodedCredentials() {
        String credentials = config.getUserName() + ":" + config.getPassword();
        byte[] credentialsBytes = credentials.getBytes();
        byte[] base64CredentialsBytes = Base64.getEncoder().encode(credentialsBytes);
        return "Basic " + new String(base64CredentialsBytes);
    }
}