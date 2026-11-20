package org.egov.inbox.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.egov.inbox.web.model.V2.InboxQueryConfiguration;
import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.Test;

class InboxStoresTest {

    private final InboxStore elasticsearch = new NamedStore("ELASTICSEARCH");
    private final InboxStore postgres = new NamedStore("POSTGRES");
    private final InboxStores stores = new InboxStores(List.of(elasticsearch, postgres));

    @Test
    void aModuleWithoutStoreUsesElasticsearch() {
        assertSame(elasticsearch, stores.forConfig(config(null)));
        assertSame(elasticsearch, stores.forConfig(config(" ")));
    }

    @Test
    void theStoreNameIsNotCaseSensitive() {
        assertSame(postgres, stores.forConfig(config("postgres")));
        assertSame(postgres, stores.forConfig(config("Postgres")));
    }

    @Test
    void anUnknownStoreIsRefusedWithItsName() {
        CustomException e = assertThrows(CustomException.class, () -> stores.forConfig(config("CASSANDRA")));
        assertEquals("INBOX_STORE_NOT_SUPPORTED", e.getCode());
        assertEquals("Inbox store 'CASSANDRA' configured for module m is not available", e.getMessage());
    }

    private static InboxQueryConfiguration config(String store) {
        return InboxQueryConfiguration.builder().module("m").store(store).build();
    }

    private record NamedStore(String name) implements InboxStore {
        public List<Map<String, Object>> search(InboxQuery query) { return List.of(); }
        public Integer count(InboxQuery query) { return 0; }
        public Map<String, Integer> countByState(InboxQuery query) { return Map.of(); }
        public Integer countModifiedBetween(InboxQuery query, long from, long to) { return 0; }
    }
}
