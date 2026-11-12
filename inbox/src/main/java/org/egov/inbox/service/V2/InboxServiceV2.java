package org.egov.inbox.service.V2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wnameless.json.flattener.JsonFlattener;
import com.google.gson.Gson;
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
        List<Inbox> items = getInboxItems(inboxRequest, inboxQueryConfiguration);
        enrichProcessInstanceInInboxItems(items);
        Integer totalCount = CollectionUtils.isEmpty(inboxRequest.getInbox().getProcessSearchCriteria().getStatus()) ? 0 : getTotalApplicationCount(inboxRequest, inboxQueryConfiguration);
        List<HashMap<String, Object>> statusCountMap = CollectionUtils.isEmpty(inboxRequest.getInbox().getProcessSearchCriteria().getStatus()) ? new ArrayList<>() : getStatusCountMap(inboxRequest, inboxQueryConfiguration);
        Integer nearingSlaCount = CollectionUtils.isEmpty(inboxRequest.getInbox().getProcessSearchCriteria().getStatus()) ? 0 : getApplicationsNearingSlaCount(inboxRequest, inboxQueryConfiguration);
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
                ProcessInstance processInstance = mapper.convertValue(item.getBusinessObject().get(CURRENT_PROCESS_INSTANCE_CONSTANT), ProcessInstance.class);
                item.setProcessInstance(processInstance);

                // Remove current process instance from business object in order to avoid having redundant data in response.
                item.getBusinessObject().remove(CURRENT_PROCESS_INSTANCE_CONSTANT);
            }
        });
    }

    private List<Inbox> getInboxItems(InboxRequest inboxRequest, InboxQueryConfiguration configuration){
        List<BusinessService> businessServices = workflowService.getBusinessServices(inboxRequest);
        enrichActionableStatusesFromRole(inboxRequest, businessServices);
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

    private void enrichActionableStatusesFromRole(InboxRequest inboxRequest, List<BusinessService> businessServices) {
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
    }

    public Integer getTotalApplicationCount(InboxRequest inboxRequest, InboxQueryConfiguration configuration){
        return stores.forConfig(configuration).count(inboxQuery(inboxRequest, configuration));
    }

    /** Count per actionable state, of the documents matching the request's criteria. */
    public List<HashMap<String, Object>> getStatusCountMap(InboxRequest inboxRequest, InboxQueryConfiguration configuration){
        Map<String, Integer> countByState = stores.forConfig(configuration).countByState(inboxQuery(inboxRequest, configuration));
        Set<String> actionableStatuses = new HashSet<>(inboxRequest.getInbox().getProcessSearchCriteria().getStatus());
        HashMap<String, Object> statusCountMap = new HashMap<>();
        countByState.forEach((state, count) -> {
            if (actionableStatuses.contains(state))
                statusCountMap.put(state, count);
        });
        List<HashMap<String, Object>> transformedStatusMap = transformStatusMap(inboxRequest, statusCountMap);
        return transformedStatusMap;
    }

    private Long getApplicationServiceSla(Map<String, Long> businessServiceSlaMap, Map<String, Long> stateUuidSlaMap, Object data) {

        Long currentDate = System.currentTimeMillis(); //current time
        Map<String, Object> auditDetails = (Map<String, Object>) ((Map<String, Object>) data).get(AUDIT_DETAILS_KEY);
        String stateUuid = JsonPath.read(data, STATE_UUID_PATH);
        if(stateUuidSlaMap.containsKey(stateUuid)){
            if (!ObjectUtils.isEmpty(auditDetails.get(LAST_MODIFIED_TIME_KEY))) {
                Long lastModifiedTime = ((Number) auditDetails.get(LAST_MODIFIED_TIME_KEY)).longValue();

                return Long.valueOf(Math.round((stateUuidSlaMap.get(stateUuid) - (currentDate - lastModifiedTime)) / ((double) (24 * 60 * 60 * 1000))));
            }
        }else {
            if (!ObjectUtils.isEmpty(auditDetails.get(CREATED_TIME_KEY))) {
                Long createdTime = ((Number) auditDetails.get(CREATED_TIME_KEY)).longValue();
                String businessService = JsonPath.read(data, BUSINESS_SERVICE_PATH);
                Long businessServiceSLA = businessServiceSlaMap.get(businessService);

                return Long.valueOf(Math.round((businessServiceSLA - (currentDate - createdTime)) / ((double) (24 * 60 * 60 * 1000))));
            }
        }
        return null;
    }

    private List<HashMap<String,Object>> transformStatusMap(InboxRequest request,HashMap<String, Object> statusCountMap) {

        if(CollectionUtils.isEmpty(statusCountMap))
            return null;

        List<BusinessService> businessServices = workflowService.getBusinessServices(request);

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

    public Integer getApplicationsNearingSlaCount(InboxRequest inboxRequest, InboxQueryConfiguration configuration) {
        List<BusinessService> businessServicesObjs = workflowService.getBusinessServices(inboxRequest);
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
        InboxQuery query = new InboxQuery(configuration, criteria.getTenantId(), criteria.getModuleSearchCriteria(), null, criteria.getOffset(), criteria.getLimit());
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
