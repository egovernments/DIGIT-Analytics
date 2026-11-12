package org.egov.inbox.store;

import static org.egov.inbox.util.InboxConstants.SORT_ORDER_CONSTANT;

import java.util.Map;

import org.egov.inbox.web.model.V2.InboxQueryConfiguration;
import org.egov.inbox.web.model.V2.SortParam;
import org.egov.inbox.web.model.workflow.ProcessInstanceSearchCriteria;

/**
 * One inbox search, independent of the store that answers it: the module's configuration, the
 * caller's module criteria (as sent, including sortOrder), the process criteria the service filled in
 * (tenant, actionable states, ...; null for a plain index search) and the page.
 */
public record InboxQuery(InboxQueryConfiguration config,
                         Map<String, Object> moduleCriteria,
                         ProcessInstanceSearchCriteria processCriteria,
                         Integer offset,
                         Integer limit) {

    /** The request's sortOrder when given, else the configuration's default order. */
    public SortParam.Order sortOrder() {
        return moduleCriteria.containsKey(SORT_ORDER_CONSTANT)
                ? SortParam.Order.valueOf((String) moduleCriteria.get(SORT_ORDER_CONSTANT))
                : config.getSortParam().getOrder();
    }
}
