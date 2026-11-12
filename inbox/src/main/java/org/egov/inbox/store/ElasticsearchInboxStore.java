package org.egov.inbox.store;

import static org.egov.inbox.util.InboxConstants.AGGREGATIONS_KEY;
import static org.egov.inbox.util.InboxConstants.COUNT_CONSTANT;
import static org.egov.inbox.util.InboxConstants.COUNT_PATH;
import static org.egov.inbox.util.InboxConstants.DATA_KEY;
import static org.egov.inbox.util.InboxConstants.DOC_COUNT_KEY;
import static org.egov.inbox.util.InboxConstants.HITS;
import static org.egov.inbox.util.InboxConstants.KEY;
import static org.egov.inbox.util.InboxConstants.SEARCH_PATH;
import static org.egov.inbox.util.InboxConstants.SOURCE_KEY;
import static org.egov.inbox.util.InboxConstants.STATUS_COUNT_AGGREGATIONS_BUCKETS_PATH;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.egov.inbox.config.InboxConfiguration;
import org.egov.inbox.repository.ServiceRequestRepository;
import org.egov.inbox.repository.builder.V2.InboxQueryBuilder;
import org.egov.tracer.model.CustomException;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import lombok.extern.slf4j.Slf4j;

/** Inbox documents in an Elasticsearch index ({@code index}), under {@code _source.Data}. */
@Slf4j
@Component
public class ElasticsearchInboxStore implements InboxStore {

    private final InboxConfiguration config;
    private final InboxQueryBuilder queryBuilder;
    private final ServiceRequestRepository serviceRequestRepository;
    private final ObjectMapper mapper;

    public ElasticsearchInboxStore(InboxConfiguration config, InboxQueryBuilder queryBuilder,
                                   ServiceRequestRepository serviceRequestRepository, ObjectMapper mapper) {
        this.config = config;
        this.queryBuilder = queryBuilder;
        this.serviceRequestRepository = serviceRequestRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "ELASTICSEARCH";
    }

    @Override
    public List<Map<String, Object>> search(InboxQuery query) {
        Map<String, Object> finalQueryBody = queryBuilder.getESQuery(query, Boolean.TRUE);
        try {
            log.info("Query: " + mapper.writeValueAsString(finalQueryBody));
        } catch (Exception e) {
            log.warn("Could not log the Elasticsearch query", e);
        }
        Object result = serviceRequestRepository.fetchESResult(getURI(query, SEARCH_PATH), finalQueryBody);
        log.info(result.toString());

        Map<String, Object> hits = (Map<String, Object>) ((Map<String, Object>) result).get(HITS);
        List<Map<String, Object>> nestedHits = (List<Map<String, Object>>) hits.get(HITS);
        List<Map<String, Object>> documents = new ArrayList<>();
        if (CollectionUtils.isEmpty(nestedHits)) {
            return documents;
        }
        nestedHits.forEach(hit -> {
            Map<String, Object> source = (Map<String, Object>) hit.get(SOURCE_KEY);
            documents.add((Map<String, Object>) source.get(DATA_KEY));
        });
        return documents;
    }

    @Override
    public Integer count(InboxQuery query) {
        return countOf(query, queryBuilder.getESQuery(query, Boolean.FALSE));
    }

    @Override
    public Map<String, Integer> countByState(InboxQuery query) {
        Map<String, Object> finalQueryBody = queryBuilder.getStatusCountQuery(query);
        Map<String, Object> response = (Map<String, Object>) serviceRequestRepository.fetchESResult(getURI(query, SEARCH_PATH), finalQueryBody);
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty((Map<String, Object>) response.get(AGGREGATIONS_KEY))) {
            List<Map<String, Object>> buckets = JsonPath.read(response, STATUS_COUNT_AGGREGATIONS_BUCKETS_PATH);
            buckets.forEach(bucket -> counts.put((String) bucket.get(KEY), (Integer) bucket.get(DOC_COUNT_KEY)));
        }
        return counts;
    }

    @Override
    public Integer countModifiedBetween(InboxQuery query, long from, long to) {
        return countOf(query, queryBuilder.getNearingSlaCountQuery(query, from, to));
    }

    private Integer countOf(InboxQuery query, Map<String, Object> finalQueryBody) {
        Map<String, Object> response = (Map<String, Object>) serviceRequestRepository.fetchESResult(getURI(query, COUNT_PATH), finalQueryBody);
        if (!response.containsKey(COUNT_CONSTANT)) {
            throw new CustomException("INBOX_COUNT_ERR", "Error occurred while executing ES count query");
        }
        return (Integer) response.get(COUNT_CONSTANT);
    }

    private StringBuilder getURI(InboxQuery query, String endpoint) {
        StringBuilder uri = new StringBuilder(config.getIndexServiceHost());
        uri.append(query.config().getIndex());
        uri.append(endpoint);
        return uri;
    }
}
