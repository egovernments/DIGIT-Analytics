package org.egov.inbox.platform;

import org.egov.inbox.web.model.InboxRequest;
import org.egov.inbox.web.model.V2.SearchRequest;

import jakarta.servlet.http.HttpServletRequest;

/** Settles who is calling and for which tenant, before an inbox v2 request is served. */
public interface CallerResolver {

    void resolve(InboxRequest request, HttpServletRequest http);

    void resolve(SearchRequest request, HttpServletRequest http);
}
