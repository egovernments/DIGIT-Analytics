package org.egov.inbox.store;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.egov.inbox.web.model.V2.InboxQueryConfiguration;
import org.egov.tracer.model.CustomException;
import org.springframework.stereotype.Component;

/** Picks the store a module's configuration names (Elasticsearch when it names none). */
@Component
public class InboxStores {

    private final Map<String, InboxStore> stores = new HashMap<>();

    public InboxStores(List<InboxStore> available) {
        available.forEach(store -> stores.put(store.name(), store));
    }

    public InboxStore forConfig(InboxQueryConfiguration config) {
        InboxStore store = stores.get(config.getStore().toUpperCase(Locale.ROOT));
        if (store == null) {
            throw new CustomException("INBOX_STORE_NOT_SUPPORTED",
                    "Inbox store '" + config.getStore() + "' configured for module " + config.getModule() + " is not available");
        }
        return store;
    }
}
