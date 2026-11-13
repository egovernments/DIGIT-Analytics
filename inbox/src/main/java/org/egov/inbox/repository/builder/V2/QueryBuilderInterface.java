package org.egov.inbox.repository.builder.V2;

import java.util.Map;

import org.egov.inbox.store.InboxQuery;

public interface QueryBuilderInterface{

    /**
     * This method will take the inbox query and create
     * elasticsearch query based on its search criteria
     * @param query
     * @return
     */
    public Map<String, Object> getESQuery(InboxQuery query, Boolean isPaginationRequired);


    /**
     * This method will take the inbox query and create
     * elasticsearch query based on its search criteria
     * to get status count map
     * @param query
     * @return
     */
    public Map<String, Object> getStatusCountQuery(InboxQuery query);

    /**
     * This method will take the inbox query and create
     * elasticsearch query based on its search criteria
     * to get the count of documents last modified between from and to
     * @param query
     * @return
     */
    public Map<String, Object> getNearingSlaCountQuery(InboxQuery query, long from, long to);

}
