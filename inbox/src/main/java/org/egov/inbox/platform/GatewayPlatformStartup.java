package org.egov.inbox.platform;

import java.util.Set;

import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Startup on DIGIT 3.0 ({@code inbox.platform=3.0}). Beans are created on first use, except the v2
 * search path, which is still built (and checked) at startup.
 *
 * <p>Several beans call 2.9 core services while starting: enc-client loads its policies from the 2.9
 * MDMS search API, and the v1 water/sewerage service looks up a system user in egov-user. Neither API
 * exists on 3.0, and only v2 is used there, so those beans are simply never created.
 */
@Configuration
@ConditionalOnProperty(name = InboxPlatform.PROPERTY, havingValue = InboxPlatform.GATEWAY)
public class GatewayPlatformStartup {

    /** Classes of the v2 search path, built at startup as usual. */
    private static final Set<String> EAGER_PACKAGES = Set.of(
            "org.egov.inbox.store.",
            "org.egov.inbox.platform.",
            "org.egov.inbox.service.V2.",
            "org.egov.inbox.repository.builder.V2.");
    private static final Set<String> EAGER_CLASSES = Set.of(
            "org.egov.inbox.web.controller.InboxV2Controller",
            "org.egov.inbox.service.WorkflowService",
            "org.egov.inbox.util.MDMSUtil",
            "org.egov.inbox.repository.ServiceRequestRepository",
            "org.egov.inbox.config.InboxConfiguration");

    @Bean
    static LazyInitializationBeanFactoryPostProcessor lazyBeansOnGateway() {
        return new LazyInitializationBeanFactoryPostProcessor();
    }

    @Bean
    static LazyInitializationExcludeFilter v2SearchPathStaysEager() {
        return (beanName, definition, type) -> {
            String name = type.getName();
            return EAGER_CLASSES.contains(name) || EAGER_PACKAGES.stream().anyMatch(name::startsWith);
        };
    }
}
