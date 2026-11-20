package org.egov.inbox.platform;

/**
 * The platform the inbox runs on, from {@code inbox.platform}: {@value #BODY} (DIGIT 2.9, the default:
 * caller in the body's RequestInfo, MDMS search API, workflow business services) or {@value #GATEWAY}
 * (DIGIT 3.0: caller and tenant from Kong's headers, MDMS v2 records, workflow v3 process definitions).
 */
public final class InboxPlatform {

    public static final String PROPERTY = "inbox.platform";
    public static final String BODY = "2.9";
    public static final String GATEWAY = "3.0";

    /** Name the inbox gives itself to core services ({@code X-Client-ID}). */
    public static final String CLIENT_ID = "inbox";

    public static final String TENANT_ID = "X-Tenant-ID";
    public static final String USER_ID = "X-User-ID";
    public static final String CLIENT_ID_HEADER = "X-Client-ID";
    public static final String JWT_ROLES = "X-JWT-realm-roles";
    public static final String ROLES = "X-Roles";
    public static final String JWT_USER_NAME = "X-JWT-preferred_username";

    private InboxPlatform() {
    }
}
