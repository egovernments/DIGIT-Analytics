package org.egov.inbox.repository.builder.V2;

import static org.egov.inbox.util.InboxConstants.BOOL_KEY;
import static org.egov.inbox.util.InboxConstants.MUST_KEY;
import static org.egov.inbox.util.InboxConstants.ORDER_KEY;
import static org.egov.inbox.util.InboxConstants.QUERY_KEY;
import static org.egov.inbox.util.InboxConstants.SORT_BY_CONSTANT;
import static org.egov.inbox.util.InboxConstants.SORT_KEY;
import static org.egov.inbox.util.InboxConstants.SORT_ORDER_CONSTANT;
import static org.egov.inbox.util.InboxConstants.SOURCE_KEY;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egov.inbox.store.InboxQuery;
import org.egov.inbox.util.ErrorConstants;
import org.egov.inbox.web.model.V2.InboxQueryConfiguration;
import org.egov.inbox.web.model.V2.SearchParam;
import org.egov.inbox.web.model.V2.SortParam;
import org.egov.inbox.web.model.workflow.ProcessInstanceSearchCriteria;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import lombok.extern.slf4j.Slf4j;


@Slf4j
@Component
public class InboxQueryBuilder implements QueryBuilderInterface {

    @Autowired
    private ObjectMapper objectMapper;



    /**
     * The bool query for the query's criteria: module criteria, then process criteria when present.
     * With pagination it also gets from/size, the sort clause and the source filter.
     */
    @Override
    public Map<String, Object> getESQuery(InboxQuery query, Boolean isPaginationRequired) {

        InboxQueryConfiguration configuration = query.config();
        Map<String, Object> params = query.moduleCriteria();
        Map<String, Object> baseEsQuery = getBaseESQueryBody(query, isPaginationRequired);

        if(isPaginationRequired) {
            // Adds sort clause to the inbox ES query only in case pagination is present, else not
            String sortClauseFieldPath = configuration.getSortParam().getPath();
            addSortClauseToBaseQuery(baseEsQuery, sortClauseFieldPath, query.sortOrder());

            // Adds source filter only when requesting for inbox items.
            List<String> sourceFilterPathList = configuration.getSourceFilterPathList();
            addSourceFilterToBaseQuery(baseEsQuery, sourceFilterPathList);
        }

        Map<String, Object> innerBoolClause = (HashMap<String, Object>) ((HashMap<String, Object>) baseEsQuery.get(QUERY_KEY)).get(BOOL_KEY);
        List<Object> mustClauseList = (ArrayList<Object>) innerBoolClause.get(MUST_KEY);

        Map<String, String> nameToPathMap = new HashMap<>();
        Map<String, SearchParam.Operator> nameToOperator = new HashMap<>();

        configuration.getAllowedSearchCriteria().forEach(searchParam -> {
            nameToPathMap.put(searchParam.getName(), searchParam.getPath());
            nameToOperator.put(searchParam.getName(), searchParam.getOperator());
        });

        addModuleSearchCriteriaToBaseQuery(params, nameToPathMap, nameToOperator, mustClauseList);
        if (query.processCriteria() != null)
            addProcessSearchCriteriaToBaseQuery(query.processCriteria(), nameToPathMap, nameToOperator, mustClauseList);

        innerBoolClause.put(MUST_KEY, mustClauseList);

        return baseEsQuery;
    }

    private void addSourceFilterToBaseQuery(Map<String, Object> baseEsQuery, List<String> sourceFilterPathList) {
        if(!CollectionUtils.isEmpty(sourceFilterPathList))
            baseEsQuery.put(SOURCE_KEY, sourceFilterPathList);
    }

    private void addSortClauseToBaseQuery(Map<String, Object> baseEsQuery, String sortClauseFieldPath, SortParam.Order sortOrder) {
        List<Map> sortClause = new ArrayList<>();
        Map<String, Object> innerSortOrderClause = new HashMap<>();
        innerSortOrderClause.put(ORDER_KEY, sortOrder);
        Map<String, Map> outerSortClauseChild = new HashMap<>();
        outerSortClauseChild.put(sortClauseFieldPath, innerSortOrderClause);
        sortClause.add(outerSortClauseChild);
        baseEsQuery.put(SORT_KEY, sortClause);
    }

    private void addProcessSearchCriteriaToBaseQuery(ProcessInstanceSearchCriteria processSearchCriteria, Map<String, String> nameToPathMap, Map<String, SearchParam.Operator> nameToOperator, List<Object> mustClauseList) {
        if(!ObjectUtils.isEmpty(processSearchCriteria.getTenantId())){
            String key = "tenantId";
            Map<String, Object> mustClauseChild = null;
            Map<String, Object> params = new HashMap<>();
            params.put(key, processSearchCriteria.getTenantId());
            mustClauseChild = (Map<String, Object>) prepareMustClauseChild(params, key, nameToPathMap, nameToOperator);
            if(CollectionUtils.isEmpty(mustClauseChild)){
                log.info("Error occurred while preparing filter for must clause. Filter for key " + key + " will not be added.");
            }else {
                mustClauseList.add(mustClauseChild);
            }
        }

        if(!ObjectUtils.isEmpty(processSearchCriteria.getStatus())){
            String key = "status";
            Map<String, Object> mustClauseChild = null;
            Map<String, Object> params = new HashMap<>();
            params.put(key, processSearchCriteria.getStatus());
            mustClauseChild = (Map<String, Object>) prepareMustClauseChild(params, key, nameToPathMap, nameToOperator);
            if(CollectionUtils.isEmpty(mustClauseChild)){
                log.info("Error occurred while preparing filter for must clause. Filter for key " + key + " will not be added.");
            }else {
                mustClauseList.add(mustClauseChild);
            }
        }

        if(!ObjectUtils.isEmpty(processSearchCriteria.getAssignee())){
            String key = "assignee";
            Map<String, Object> mustClauseChild = null;
            Map<String, Object> params = new HashMap<>();
            params.put(key, processSearchCriteria.getAssignee());
            mustClauseChild = (Map<String, Object>) prepareMustClauseChild(params, key, nameToPathMap, nameToOperator);
            if(CollectionUtils.isEmpty(mustClauseChild)){
                log.info("Error occurred while preparing filter for must clause. Filter for key " + key + " will not be added.");
            }else {
                mustClauseList.add(mustClauseChild);
            }
        }

        if(!ObjectUtils.isEmpty(processSearchCriteria.getFromDate())){
            String key = "fromDate";
            Map<String, Object> mustClauseChild = null;
            Map<String, Object> params = new HashMap<>();
            params.put(key, processSearchCriteria.getFromDate());
            mustClauseChild = (Map<String, Object>) prepareMustClauseChild(params, key, nameToPathMap, nameToOperator);
            if(CollectionUtils.isEmpty(mustClauseChild)){
                log.info("Error occurred while preparing filter for must clause. Filter for key " + key + " will not be added.");
            }else {
                mustClauseList.add(mustClauseChild);
            }
        }

        if(!ObjectUtils.isEmpty(processSearchCriteria.getToDate())){
            String key = "toDate";
            Map<String, Object> mustClauseChild = null;
            Map<String, Object> params = new HashMap<>();
            params.put(key, processSearchCriteria.getToDate());
            mustClauseChild = (Map<String, Object>) prepareMustClauseChild(params, key, nameToPathMap, nameToOperator);
            if(CollectionUtils.isEmpty(mustClauseChild)){
                log.info("Error occurred while preparing filter for must clause. Filter for key " + key + " will not be added.");
            }else {
                mustClauseList.add(mustClauseChild);
            }
        }

    }


	private void addModuleSearchCriteriaToBaseQuery(Map<String, Object> params, Map<String, String> nameToPathMap,
			Map<String, SearchParam.Operator> nameToOperator, List<Object> mustClauseList) {
		params.keySet().forEach(key -> {
			if (!(key.equals(SORT_ORDER_CONSTANT) || key.equals(SORT_BY_CONSTANT))) {

				SearchParam.Operator operator = nameToOperator.get(key);
				if (operator != null && operator.equals(SearchParam.Operator.WILDCARD)) {
					List<Map<String, Object>> mustClauseChild = null;

					mustClauseChild = (List<Map<String, Object>>) prepareMustClauseWildCardChild(params, key,
							nameToPathMap, nameToOperator);

					if (CollectionUtils.isEmpty(mustClauseChild)) {
						log.info("Error occurred while preparing filter for must clause. Filter for key " + key
								+ " will not be added.");
					} else {
						mustClauseList.addAll(mustClauseChild);
					}
				} else {

					Map<String, Object> mustClauseChild = null;
					mustClauseChild = (Map<String, Object>) prepareMustClauseChild(params, key, nameToPathMap,
							nameToOperator);
					if (CollectionUtils.isEmpty(mustClauseChild)) {
						log.info("Error occurred while preparing filter for must clause. Filter for key " + key
								+ " will not be added.");
					} else {
						mustClauseList.add(mustClauseChild);
					}

				}
			}
		});
	}


    /** The query's criteria plus a terms aggregation on the configured state field. */
    @Override
    public Map<String, Object> getStatusCountQuery(InboxQuery query) {
        Map<String, Object> baseEsQuery = getESQuery(query, Boolean.FALSE);
        appendStatusCountAggsNode(baseEsQuery, query.config().getStatePath());
        return baseEsQuery;
    }

    /** The query's criteria plus a range on the configured last-modified field. */
    @Override
    public Map<String, Object> getNearingSlaCountQuery(InboxQuery query, long from, long to) {
        Map<String, Object> baseEsQuery = getESQuery(query, Boolean.FALSE);
        appendNearingSlaCountClause(baseEsQuery, query.config().getLastModifiedPath(), from, to);
        return baseEsQuery;
    }

    private void appendNearingSlaCountClause(Map<String, Object> baseEsQuery, String lastModifiedPath, Long gteParam, Long lteParam) {
        List mustClause = JsonPath.read(baseEsQuery, "$.query.bool.must");
        Map<String, Object> rangeObject = new HashMap<>();
        Map<String, Object> rangeClause = new HashMap<>();
        rangeClause.put("gte", gteParam);
        rangeClause.put("lte", lteParam);
        rangeObject.put(lastModifiedPath, rangeClause);
        HashMap<String, Object> rangeMap = new HashMap<>();
        rangeMap.put("range", rangeObject);
        mustClause.add(rangeMap);
    }

    private void appendStatusCountAggsNode(Map<String, Object> baseEsQuery, String statePath) {
        Map<String, Object> aggsNode = new HashMap<>();
        aggsNode.put("statusCount", new HashMap<>());
        Map<String, Object> statusCountNode = (Map<String, Object>) aggsNode.get("statusCount");
        statusCountNode.put("terms", new HashMap<>());
        Map<String, Object> innerTermsQuery = (Map<String, Object>) statusCountNode.get("terms");
        innerTermsQuery.put("field", statePath);
        baseEsQuery.put("aggs", aggsNode);
    }

    private Map<String, Object> getBaseESQueryBody(InboxQuery query, Boolean isPaginationRequired){
        Map<String, Object> baseEsQuery = new HashMap<>();
        Map<String, Object> boolQuery = new HashMap<>();

        // Prepare bool query
        boolQuery.put("bool", new HashMap<>());
        Map<String, Object> innerBoolBody = (Map<String, Object>) boolQuery.get("bool");
        innerBoolBody.put("must", new ArrayList<>());

        // Prepare base ES query
        if(isPaginationRequired) {
            baseEsQuery.put("from", query.offset());
            baseEsQuery.put("size", query.limit());
        }
        baseEsQuery.put("query", boolQuery);

        return baseEsQuery;
    }


	private Object prepareMustClauseChild(Map<String, Object> params, String key, Map<String, String> nameToPathMap,
			Map<String, SearchParam.Operator> nameToOperatorMap) {

		SearchParam.Operator operator = nameToOperatorMap.get(key);
		if (operator == null || operator.equals(SearchParam.Operator.EQUAL) || operator.equals(SearchParam.Operator.CONTAINS)) {
			// Add terms clause in case the search criteria has a list of values
			if (params.get(key) instanceof List) {
				Map<String, Object> termsClause = new HashMap<>();
				termsClause.put("terms", new HashMap<>());
				Map<String, Object> innerTermsClause = (Map<String, Object>) termsClause.get("terms");
				innerTermsClause.put(addDataPathToSearchParamKey(key, nameToPathMap), params.get(key));
				return termsClause;
			}
			// Add term clause in case the search criteria has a single value
			else {
				Map<String, Object> termClause = new HashMap<>();
				termClause.put("term", new HashMap<>());
				Map<String, Object> innerTermClause = (Map<String, Object>) termClause.get("term");
				innerTermClause.put(addDataPathToSearchParamKey(key, nameToPathMap), params.get(key));
				return termClause;
			}
		} else if (operator.equals(SearchParam.Operator.LTE) || operator.equals(SearchParam.Operator.GTE)) {
			Map<String, Object> rangeClause = new HashMap<>();
			rangeClause.put("range", new HashMap<>());
			Map<String, Object> innerTermClause = (Map<String, Object>) rangeClause.get("range");
			Map<String, Object> comparatorMap = new HashMap<>();

			if (operator.equals(SearchParam.Operator.LTE)) {
				comparatorMap.put("lte", params.get(key));
			} else if (operator.equals(SearchParam.Operator.GTE)) {
				comparatorMap.put("gte", params.get(key));
			}
			innerTermClause.put(addDataPathToSearchParamKey(key, nameToPathMap), comparatorMap);
			return rangeClause;
		} else
			throw new CustomException(ErrorConstants.INVALID_OPERATOR_DATA, " Unsupported Operator : " + operator);

	}
	
	private List<Map<String, Object>> prepareMustClauseWildCardChild(Map<String, Object> params, String key,
			Map<String, String> nameToPathMap, Map<String, SearchParam.Operator> nameToOperatorMap) {
		// Add wildcard clause in case the search criteria has a list of values
		Object value = params.get(key);
		List<Map<String, Object>> wildcardClauses = new ArrayList<>();
		if (value instanceof List) {
			List<Object> values = (List<Object>) value;
			for (Object item : values) {
				Map<String, Object> wildcardClause = new HashMap<>();
				wildcardClause.put("wildcard", new HashMap<>());
				Map<String, Object> innerWildcardClause = (Map<String, Object>) wildcardClause.get("wildcard");
				innerWildcardClause.put(addDataPathToSearchParamKey(key, nameToPathMap), "*" + item + "*");
				wildcardClauses.add(wildcardClause);
			}

			return wildcardClauses;
		} else {
			Map<String, Object> wildcardClause = new HashMap<>();
			wildcardClause.put("wildcard", new HashMap<>());
			Map<String, Object> innerWildcardClause = (Map<String, Object>) wildcardClause.get("wildcard");
			innerWildcardClause.put(addDataPathToSearchParamKey(key, nameToPathMap), "*" + value + "*");
			wildcardClauses.add(wildcardClause);
			return wildcardClauses;
		}
	}

    private String addDataPathToSearchParamKey(String key, Map<String, String> nameToPathMap){

        String path = nameToPathMap.get(key);

        if (StringUtils.isEmpty(path))
            path = "Data." + key + ".keyword";

        return path;
    }

}
