package org.egov.inbox.platform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.Role;
import org.egov.common.contract.request.User;
import org.egov.inbox.web.model.InboxRequest;
import org.egov.inbox.web.model.V2.SearchRequest;
import org.egov.tracer.model.CustomException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * On DIGIT 3.0 the caller and tenant come only from the headers Kong sets after checking the token
 * ({@code X-Tenant-ID} from the token's realm, {@code X-User-ID}, the realm roles). The body's tenant
 * is replaced with the header's, and the body's RequestInfo user is rebuilt from the headers, so the
 * rest of the inbox (role-based states, audit) works on the authenticated caller only.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = InboxPlatform.PROPERTY, havingValue = InboxPlatform.GATEWAY)
public class GatewayCaller implements CallerResolver {

    @Override
    public void resolve(InboxRequest request, HttpServletRequest http) {
        String tenant = tenant(http);
        if (request.getInbox() != null) {
            warnOnMismatch(request.getInbox().getTenantId(), tenant);
            request.getInbox().setTenantId(tenant);
            if (request.getInbox().getProcessSearchCriteria() != null) {
                request.getInbox().getProcessSearchCriteria().setTenantId(tenant);
            }
        }
        request.setRequestInfo(requestInfo(request.getRequestInfo(), tenant, http));
    }

    @Override
    public void resolve(SearchRequest request, HttpServletRequest http) {
        String tenant = tenant(http);
        if (request.getIndexSearchCriteria() != null) {
            warnOnMismatch(request.getIndexSearchCriteria().getTenantId(), tenant);
            request.getIndexSearchCriteria().setTenantId(tenant);
        }
        request.setRequestInfo(requestInfo(request.getRequestInfo(), tenant, http));
    }

    private static String tenant(HttpServletRequest http) {
        String tenant = http.getHeader(InboxPlatform.TENANT_ID);
        if (tenant == null || tenant.isBlank()) {
            throw new CustomException("TENANT_HEADER_MISSING", "X-Tenant-ID header is required");
        }
        return tenant.trim();
    }

    private static RequestInfo requestInfo(RequestInfo fromBody, String tenant, HttpServletRequest http) {
        String userId = http.getHeader(InboxPlatform.USER_ID);
        if (userId == null || userId.isBlank()) {
            throw new CustomException("USER_HEADER_MISSING", "X-User-ID header is required");
        }
        String roles = http.getHeader(InboxPlatform.JWT_ROLES);
        if (roles == null || roles.isBlank()) {
            roles = http.getHeader(InboxPlatform.ROLES);
        }

        User user = new User();
        user.setUuid(userId.trim());
        user.setUserName(http.getHeader(InboxPlatform.JWT_USER_NAME));
        user.setTenantId(tenant);
        List<Role> userRoles = new ArrayList<>();
        if (roles != null && !roles.isBlank()) {
            Arrays.stream(roles.split(",")).map(String::trim).filter(code -> !code.isEmpty()).forEach(code -> {
                Role role = new Role();
                role.setCode(code);
                role.setName(code);
                role.setTenantId(tenant);
                userRoles.add(role);
            });
        }
        user.setRoles(userRoles);

        RequestInfo requestInfo = fromBody == null ? new RequestInfo() : fromBody;
        requestInfo.setUserInfo(user);
        requestInfo.setAuthToken(null);
        return requestInfo;
    }

    private static void warnOnMismatch(String fromBody, String fromHeader) {
        if (fromBody != null && !fromBody.equals(fromHeader)) {
            log.warn("Request body names tenant {} but the token's tenant is {}; using {}", fromBody, fromHeader, fromHeader);
        }
    }
}
