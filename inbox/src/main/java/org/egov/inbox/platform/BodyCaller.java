package org.egov.inbox.platform;

import org.egov.inbox.web.model.InboxRequest;
import org.egov.inbox.web.model.V2.SearchRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/** On DIGIT 2.9 the gateway has already put the caller in the body's RequestInfo: nothing to do. */
@Component
@ConditionalOnProperty(name = InboxPlatform.PROPERTY, havingValue = InboxPlatform.BODY, matchIfMissing = true)
public class BodyCaller implements CallerResolver {

    @Override
    public void resolve(InboxRequest request, HttpServletRequest http) {
    }

    @Override
    public void resolve(SearchRequest request, HttpServletRequest http) {
    }
}
