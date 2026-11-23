package org.egov.inbox.service.V2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wnameless.json.flattener.JsonFlattener;
import com.google.gson.Gson;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.egov.hash.HashService;
import org.egov.inbox.service.V2.validator.ValidatorDefaultImplementation;
import org.egov.inbox.store.InboxQuery;
import org.egov.inbox.store.InboxStores;
import org.egov.inbox.service.WorkflowService;
import org.egov.inbox.util.MDMSUtil;
import org.egov.inbox.web.model.Inbox;
import org.egov.inbox.web.model.InboxRequest;
import org.egov.inbox.web.model.InboxResponse;
import org.egov.inbox.web.model.V2.*;
import org.egov.inbox.web.model.workflow.BusinessService;
import org.egov.inbox.web.model.workflow.ProcessInstance;
import org.egov.inbox.web.model.workflow.ProcessInstanceSearchCriteria;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.util.*;

import static org.egov.inbox.util.InboxConstants.*;

@Service
@Slf4j
public class InboxServiceV2 {

    @Autowired
    private InboxStores stores;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private ValidatorDefaultImplementation validator;

    @Autowired
    private MDMSUtil mdmsUtil;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private HashService hashService;


    /**
     *
     * @param inboxRequest
     * @return
     */
    public InboxResponse getInboxResponse(InboxRequest inboxRequest){

        validator.validateSearchCriteria(inboxRequest);
        InboxQueryConfiguration inboxQueryConfiguration = mdmsUtil.getConfigFromMDMS(inboxRequest.getInbox().getTenantId(), inboxRequest.getInbox().getProcessSearchCriteria().getModuleName());
        hashParamsWhereverRequiredBasedOnConfiguration(inboxRequest.getInbox().getModuleSearchCriteria(), inboxQueryConfiguration);
        List<BusinessService> businessServices = workflowService.getBusinessServices(inboxRequest);
        List<String> actionableStatuses = enrichActionableStatusesFromRole(inboxRequest, businessServices);
        List<Inbox> items = getInboxItems(inboxRequest, inboxQueryConfiguration, businessServices);
        enrichProcessInstanceInInboxItems(items);
        Integer totalCount = CollectionUtils.isEmpty(inboxRequest.getInbox().getProcessSearchCriteria().getStatus()) ? 0 : getTotalApplicationCount(inboxRequest, inboxQueryConfiguration);
        List<HashMap<String, Object>> statusCountMap = CollectionUtils.isEmpty(inboxRequest.getInbox().getProcessSearchCriteria().getStatus()) ? new ArrayList<>() : getStatusCountMap(inboxRequest, inboxQueryConfiguration, actionableStatuses, businessServices);
        Integer nearingSlaCount = CollectionUtils.isEmpty(inboxRequest.getInbox().getProcessSearchCriteria().getStatus()) ? 0 : getApplicationsNearingSlaCount(inboxRequest, inboxQueryConfiguration, businessServices);
        InboxResponse inboxResponse = InboxResponse.builder().items(items).totalCount(totalCount).statusMap(statusCountMap).nearingSlaCount(nearingSlaCount).build();

        return inboxResponse;
    }

    private void hashParamsWhereverRequiredBasedOnConfiguration(Map<String, Object> moduleSearchCriteria, InboxQueryConfiguration inboxQueryConfiguration) {

        inboxQueryConfiguration.getAllowedSearchCriteria().forEach(searchParam -> {
            if(!ObjectUtils.isEmpty(searchParam.getIsHashingRequired()) && searchParam.getIsHashingRequired()){
                if(moduleSearchCriteria.containsKey(searchParam.getName())){
                    if(moduleSearchCriteria.get(searchParam.getName()) instanceof List){
                        List<Object> hashedParams = new ArrayList<>();
                        ((List<?>) moduleSearchCriteria.get(searchParam.getName())).forEach(object -> {
                            hashedParams.add(hashService.getHashValue(object));
                        });
                        moduleSearchCriteria.put(searchParam.getName(), hashedParams);
                    }else{
                        Object hashedValue = hashService.getHashValue(moduleSearchCriteria.get(searchParam.getName()));
                        moduleSearchCriteria.put(searchParam.getName(), hashedValue);
                    }
                }
            }
        });
    }

    private void enrichProcessInstanceInInboxItems(List<Inbox> items) {
        /*
          As part of the new inbox, having currentProcessInstance as part of the index is mandated. This has been
          done to avoid having redundant network calls which could hog the performance.
        */
        items.forEach(item -> {
            if(item.getBusinessObject().containsKey(CURRENT_PROCESS_INSTANCE_CONSTANT)) {
                // Set process instance object in the native process instance field declared in the model inbox class.
                // A value that is not a process instance stays in the business object, so one bad
                // document cannot fail the page.
                ProcessInstance processInstance;
                try {
                    processInstance = mapper.convertValue(item.getBusinessObject().get(CURRENT_PROCESS_INSTANCE_CONSTANT), ProcessInstance.class);
                } catch (IllegalArgumentException e) {
                    log.warn("Inbox document has an unreadable {}: {}", CURRENT_PROCESS_INSTANCE_CONSTANT, e.getMessage());
                    return;
                }
                item.setProcessInstance(processInstance);

                // Remove current process instance from business object in order to avoid having redundant data in response.
                item.getBusinessObject().remove(CURRENT_PROCESS_INSTANCE_CONSTANT);
            }
        });
    }

    private List<Inbox> getInboxItems(InboxRequest inboxRequest, InboxQueryConfiguration configuration, List<BusinessService> businessServices){
        if(CollectionUtils.isEmpty(inboxRequest.getInbox().getProcessSearchCriteria().getStatus())){
            return new ArrayList<>();
        }
        List<Map<String, Object>> documents = stores.forConfig(configuration).search(inboxQuery(inboxRequest, configuration));
        return toInboxItems(documents, businessServices);
    }

    private InboxQuery inboxQuery(InboxRequest inboxRequest, InboxQueryConfiguration configuration) {
        return new InboxQuery(configuration, inboxRequest.getInbox().getTenantId(), inboxRequest.getInbox().getModuleSearchCriteria(),
                inboxRequest.getInbox().getProcessSearchCriteria(), inboxRequest.getInbox().getOffset(), inboxRequest.getInbox().getLimit());
    }

    /**
     * Narrows the request's states to those the caller's roles can act on (all of them when none are
     * asked for) and returns all the actionable states.
     */
    private List<String> enrichActionableStatusesFromRole(InboxRequest inboxRequest, List<BusinessService> businessServices) {
        ProcessInstanceSearchCriteria processCriteria = inboxRequest.getInbox().getProcessSearchCriteria();
        String tenantId = inboxRequest.getInbox().getTenantId();
        processCriteria.setTenantId(tenantId);

        HashMap<String, String> StatusIdNameMap = workflowService.getActionableStatusesForRole(inboxRequest.getRequestInfo(), businessServices,
                inboxRequest.getInbox().getProcessSearchCriteria());
        log.info(StatusIdNameMap.toString());
        List<String> actionableStatusUuid = new ArrayList<>();
        if (StatusIdNameMap.values().size() > 0) {
            if (!CollectionUtils.isEmpty(processCriteria.getStatus())) {
                processCriteria.getStatus().forEach(statusUuid -> {
                    if(StatusIdNameMap.keySet().contains(statusUuid)){
                        actionableStatusUuid.add(statusUuid);
                    }
                });
                inboxRequest.getInbox().getProcessSearchCriteria().setStatus(actionableStatusUuid);
            } else {
                inboxRequest.getInbox().getProcessSearchCriteria().setStatus(new ArrayList<>(StatusIdNameMap.keySet()));
            }
        }else{
            inboxRequest.getInbox().getProcessSearchCriteria().setStatus(new ArrayList<>());
        }
        return new ArrayList<>(StatusIdNameMap.keySet());
    }

    private InboxQuery statusCountQuery(InboxRequest inboxRequest, InboxQueryConfiguration configuration, List<String> actionableStatuses) {
        Map<String, Object> moduleCriteria = new HashMap<>(inboxRequest.getInbox().getModuleSearchCriteria());
        moduleCriteria.remove(STATUS_CRITERIA_KEY);
        moduleCriteria.remove(ASSIGNEE_CRITERIA_KEY);
        ProcessInstanceSearchCriteria requested = inboxRequest.getInbox().getProcessSearchCriteria();
        ProcessInstanceSearchCriteria process = new ProcessInstanceSearchCriteria();
        process.setTenantId(requested.getTenantId());
        process.setBusinessService(requested.getBusinessService());
        process.setModuleName(requested.getModuleName());
        process.setFromDate(requested.getFromDate());
        process.setToDate(requested.getToDate());
        process.setStatus(actionableStatuses);
        return new InboxQuery(configuration, inboxRequest.getInbox().getTenantId(), moduleCriteria, process, null, null);
    }

    public Integer getTotalApplicationCount(InboxRequest inboxRequest, InboxQueryConfiguration configuration){
        return stores.forConfig(configuration).count(inboxQuery(inboxRequest, configuration));
    }

    /**
     * Count per actionable state, for the status filter: every actionable state keeps its count
     * whichever states are ticked (moduleSearchCriteria.status, the narrowed process states) and
     * whoever they are assigned to (moduleSearchCriteria.assignee, the process assignee). The
     * tenant, dates and other module criteria still apply.
     */
    public List<HashMap<String, Object>> getStatusCountMap(InboxRequest inboxRequest, InboxQueryConfiguration configuration, List<String> actionableStatuses,
                                                          List<BusinessService> businessServices){
        Map<String, Integer> countByState = stores.forConfig(configuration).countByState(statusCountQuery(inboxRequest, configuration, actionableStatuses));
        Set<String> actionable = new HashSet<>(actionableStatuses);
        HashMap<String, Object> statusCountMap = new HashMap<>();
        countByState.forEach((state, count) -> {
            if (actionable.contains(state))
                statusCountMap.put(state, count);
        });
        List<HashMap<String, Object>> transformedStatusMap = transformStatusMap(statusCountMap, businessServices);
        return transformedStatusMap;
    }

    /**
     * Days left on the document's SLA: the state's SLA from its last change when the state has one,
     * else the business service's SLA from creation. Null when the document lacks what that needs
     * (state, business service, audit times), so one incomplete document cannot fail the page.
     */
    private Long getApplicationServiceSla(Map<String, Long> businessServiceSlaMap, Map<String, Long> stateUuidSlaMap, Map<String, Object> data) {

        long currentDate = System.currentTimeMillis();
        Map<?, ?> auditDetails = data.get(AUDIT_DETAILS_KEY) instanceof Map<?, ?> map ? map : Map.of();
        Object stateUuid = readOrNull(data, STATE_UUID_PATH);
        if (stateUuid != null && stateUuidSlaMap.containsKey(stateUuid)) {
            if (auditDetails.get(LAST_MODIFIED_TIME_KEY) instanceof Number lastModifiedTime) {
                return Math.round((stateUuidSlaMap.get(stateUuid) - (currentDate - lastModifiedTime.longValue())) / ((double) (24 * 60 * 60 * 1000)));
            }
        } else if (auditDetails.get(CREATED_TIME_KEY) instanceof Number createdTime) {
            Object businessService = readOrNull(data, BUSINESS_SERVICE_PATH);
            Long businessServiceSLA = businessService == null ? null : businessServiceSlaMap.get(businessService);
            if (businessServiceSLA == null)
                return null;

            return Math.round((businessServiceSLA - (currentDate - createdTime.longValue())) / ((double) (24 * 60 * 60 * 1000)));
        }
        return null;
    }

    /** The value at a definite path, or null when the document does not have it. */
    private static Object readOrNull(Map<String, Object> data, String path) {
        try {
            return JsonPath.read(data, path);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private List<HashMap<String,Object>> transformStatusMap(HashMap<String, Object> statusCountMap, List<BusinessService> businessServices) {

        if(CollectionUtils.isEmpty(statusCountMap))
            return null;

        Map<String,String> statusIdToBusinessServiceMap = workflowService.getStatusIdToBusinessServiceMap(businessServices);
        Map<String, String> statusIdToApplicationStatusMap = workflowService.getApplicationStatusIdToStatusMap(businessServices);
        Map<String, String> statusIdToApplicationStateMap = workflowService.getApplicationStatusIdToStateMap(businessServices);

        List<HashMap<String,Object>> statusCountMapTransformed = new ArrayList<>();

        for(Map.Entry<String, Object> entry : statusCountMap.entrySet()){
            String statusId = entry.getKey();
            Integer count = (Integer) entry.getValue();
            HashMap<String, Object> map = new HashMap<>();
            map.put(COUNT_CONSTANT, count);
            map.put(APPLICATION_STATUS_KEY,statusIdToApplicationStatusMap.get(statusId));
            map.put(BUSINESSSERVICE_KEY,statusIdToBusinessServiceMap.get(statusId));
            map.put(STATUSID_KEY, statusId);
            map.put(STATE,statusIdToApplicationStateMap.get(statusId));
            statusCountMapTransformed.add(map);
        }
        return statusCountMapTransformed;
    }

    private List<Inbox> toInboxItems(List<Map<String, Object>> documents, List<BusinessService> businessServices) {
        if(CollectionUtils.isEmpty(documents)){
            return new ArrayList<>();
        }

        Map<String, Long> businessServiceSlaMap = new HashMap<>();
        Map<String, Long> stateUuidVsSlaMap = new HashMap<>();

        businessServices.forEach(businessService -> {
            businessServiceSlaMap.put(businessService.getBusinessService(),businessService.getBusinessServiceSla());
            businessService.getStates().forEach(state -> {
                if(!ObjectUtils.isEmpty(state.getSla()))
                    stateUuidVsSlaMap.put(state.getUuid(), state.getSla());
            });
        });

        List<Inbox> inboxItemList = new ArrayList<>();
        documents.forEach(document ->{
            Inbox inbox = new Inbox();
            inbox.setBusinessObject(document);
            Long serviceSla = getApplicationServiceSla(businessServiceSlaMap, stateUuidVsSlaMap, inbox.getBusinessObject());
            inbox.getBusinessObject().put(SERVICESLA_KEY, serviceSla);
            inboxItemList.add(inbox);
        });
        return inboxItemList;
    }

    public Integer getApplicationsNearingSlaCount(InboxRequest inboxRequest, InboxQueryConfiguration configuration, List<BusinessService> businessServicesObjs) {
        Map<String, Long> businessServiceSlaMap = new HashMap<>();
        Map<String, HashSet<String>> businessServiceVsStateUuids = new HashMap<>();
        businessServicesObjs.forEach(businessService -> {
            List<String> listOfUuids = new ArrayList<>();
            businessService.getStates().forEach(state -> {
                listOfUuids.add(state.getUuid());
            });
            businessServiceVsStateUuids.put(businessService.getBusinessService(), new HashSet<>(listOfUuids));
            businessServiceSlaMap.put(businessService.getBusinessService(),businessService.getBusinessServiceSla());
        });

        List<String> uuidsInSearchCriteria = inboxRequest.getInbox().getProcessSearchCriteria().getStatus();

        Map<String, List<String>> businessServiceVsUuidsBasedOnSearchCriteria = new HashMap<>();

        // If status uuids are being passed in process search criteria, segregating them based on their business service
        if(!CollectionUtils.isEmpty(uuidsInSearchCriteria)) {
            uuidsInSearchCriteria.forEach(uuid -> {
                businessServiceVsStateUuids.keySet().forEach(businessService -> {
                    HashSet<String> setOfUuids = businessServiceVsStateUuids.get(businessService);
                    if (setOfUuids.contains(uuid)) {
                        if (businessServiceVsUuidsBasedOnSearchCriteria.containsKey(businessService)) {
                            businessServiceVsUuidsBasedOnSearchCriteria.get(businessService).add(uuid);
                        } else {
                            businessServiceVsUuidsBasedOnSearchCriteria.put(businessService, new ArrayList<>(Collections.singletonList(uuid)));
                        }
                    }
                });

            });
        }else{
            businessServiceVsStateUuids.keySet().forEach(businessService -> {
                HashSet<String> setOfUuids = businessServiceVsStateUuids.get(businessService);
                businessServiceVsUuidsBasedOnSearchCriteria.put(businessService, new ArrayList<>(setOfUuids));
            });
        }



        List<String> businessServices = new ArrayList<>(businessServiceVsUuidsBasedOnSearchCriteria.keySet());
        Integer totalCount = 0;
        // Fetch slot percentage only once here !!!!!!!!!!


        for(int i = 0; i < businessServices.size(); i++){
            String businessService = businessServices.get(i);
            Long businessServiceSla = businessServiceSlaMap.get(businessService);
            if (businessServiceSla == null)
                continue;    // a process without an SLA has nothing nearing it
            inboxRequest.getInbox().getProcessSearchCriteria().setStatus(businessServiceVsUuidsBasedOnSearchCriteria.get(businessService));
            // Nearing SLA = last modified within the first 60% of the business service's SLA, counted back from now.
            long now = System.currentTimeMillis();
            long slotLimit = businessServiceSla - 40 * (businessServiceSla / 100);
            totalCount += stores.forConfig(configuration).countModifiedBetween(inboxQuery(inboxRequest, configuration), now - slotLimit, now);
        }

        return totalCount;

    }


    public SearchResponse getSpecificFieldsFromESIndex(SearchRequest searchRequest) {
        String tenantId = searchRequest.getIndexSearchCriteria().getTenantId();
        String moduleName = searchRequest.getIndexSearchCriteria().getModuleName();
        Map<String, Object> moduleSearchCriteria = searchRequest.getIndexSearchCriteria().getModuleSearchCriteria();

        validator.validateSearchCriteria(tenantId, moduleName, moduleSearchCriteria);
        InboxQueryConfiguration inboxQueryConfiguration = mdmsUtil.getConfigFromMDMS(tenantId, moduleName);
        hashParamsWhereverRequiredBasedOnConfiguration(moduleSearchCriteria, inboxQueryConfiguration);
        List<Data> data = getDataFromSimpleSearch(searchRequest, inboxQueryConfiguration);
        SearchResponse searchResponse = SearchResponse.builder().data(data).build();
        return searchResponse;
    }

    private List<Data> getDataFromSimpleSearch(SearchRequest searchRequest, InboxQueryConfiguration configuration) {
        IndexSearchCriteria criteria = searchRequest.getIndexSearchCriteria();
        // The request's tenant (the token's on 3.0) bounds the search, like _search; the caller's
        // module criteria can only narrow it further.
        ProcessInstanceSearchCriteria tenantOnly = new ProcessInstanceSearchCriteria();
        tenantOnly.setTenantId(criteria.getTenantId());
        InboxQuery query = new InboxQuery(configuration, criteria.getTenantId(), criteria.getModuleSearchCriteria(), tenantOnly, criteria.getOffset(), criteria.getLimit());
        List<Map<String, Object>> documents = stores.forConfig(configuration).search(query);

        List<Data> dataList = new ArrayList<>();
        documents.forEach(dataObject -> {
            Data data = new Data();
            List<Field> fields = getFieldsFromDataObject(dataObject);
            data.setFields(fields);
            dataList.add(data);
        });

        return dataList;
    }

    private List<Field> getFieldsFromDataObject(Map<String, Object> dataObject) {
        List<Field> listOfFields = new ArrayList<>();
        try {
            Map<String, Object> flattenedDataObject = JsonFlattener.flattenAsMap(mapper.writeValueAsString(dataObject));
            flattenedDataObject.keySet().forEach(key -> {
                Field field = new Field();
                field.setKey(key);
                field.setValue(flattenedDataObject.get(key));
                listOfFields.add(field);
            });
        }catch (JsonProcessingException ex){
            throw new CustomException("EG_INBOX_GET_FIELDS_ERR", "Error while processing JSON.");
        }
        return listOfFields;
    }
}
