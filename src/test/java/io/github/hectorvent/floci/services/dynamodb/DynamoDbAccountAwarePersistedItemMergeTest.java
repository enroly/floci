package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbAccountAwarePersistedItemMergeTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String TABLE_KEY = "us-east-1::orders";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mergesDistinctLegacyAndAccountScopedMapsBeforeRemovingTheLegacySource() {
        InMemoryStorage<String, Map<String, JsonNode>> itemDelegate = new InMemoryStorage<>();
        AccountAwareStorageBackend<Map<String, JsonNode>> itemStore = accountItemStore(itemDelegate);
        AccountAwareStorageBackend<TableDefinition> tableStore = accountTableStore();
        ObjectNode legacy = item("legacy", "one", "legacy");
        ObjectNode scoped = item("scoped", "two", "scoped");
        tableStore.putForAccount(ACCOUNT_ID, TABLE_KEY, table());
        itemDelegate.put(TABLE_KEY, new HashMap<>(Map.of("legacy#one", legacy)));
        itemDelegate.put(ACCOUNT_ID + "/" + TABLE_KEY, new HashMap<>(Map.of("scoped#two", scoped)));

        service(tableStore, itemStore).loadPersistedItems();

        assertEquals(Map.of("legacy::one", legacy, "scoped::two", scoped),
                itemStore.getForAccount(ACCOUNT_ID, TABLE_KEY).orElseThrow());
        assertEquals(Set.of(ACCOUNT_ID + "/" + TABLE_KEY), itemDelegate.keys());
    }

    @Test
    void rejectsConflictingMixedMapsBeforeWritingOrRemovingEitherSource() {
        InMemoryStorage<String, Map<String, JsonNode>> itemDelegate = new InMemoryStorage<>();
        AccountAwareStorageBackend<Map<String, JsonNode>> itemStore = accountItemStore(itemDelegate);
        AccountAwareStorageBackend<TableDefinition> tableStore = accountTableStore();
        ObjectNode legacy = item("account", "order", "legacy");
        ObjectNode scoped = item("account", "order", "scoped");
        Map<String, JsonNode> legacyMap = new HashMap<>(Map.of("account#order", legacy));
        Map<String, JsonNode> scopedMap = new HashMap<>(Map.of("account::order", scoped));
        tableStore.putForAccount(ACCOUNT_ID, TABLE_KEY, table());
        itemDelegate.put(TABLE_KEY, legacyMap);
        itemDelegate.put(ACCOUNT_ID + "/" + TABLE_KEY, scopedMap);

        assertThrows(IllegalStateException.class, () -> service(tableStore, itemStore).loadPersistedItems());

        assertEquals(Set.of(TABLE_KEY, ACCOUNT_ID + "/" + TABLE_KEY), itemDelegate.keys());
        assertEquals(legacyMap, itemDelegate.get(TABLE_KEY).orElseThrow());
        assertEquals(scopedMap, itemDelegate.get(ACCOUNT_ID + "/" + TABLE_KEY).orElseThrow());
    }

    private AccountAwareStorageBackend<TableDefinition> accountTableStore() {
        return new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT_ID);
    }

    private AccountAwareStorageBackend<Map<String, JsonNode>> accountItemStore(
            InMemoryStorage<String, Map<String, JsonNode>> delegate) {
        return new AccountAwareStorageBackend<>(delegate, null, ACCOUNT_ID);
    }

    private DynamoDbService service(AccountAwareStorageBackend<TableDefinition> tableStore,
                                    AccountAwareStorageBackend<Map<String, JsonNode>> itemStore) {
        return new DynamoDbService(tableStore, itemStore,
                new RegionResolver("us-east-1", ACCOUNT_ID), "::");
    }

    private TableDefinition table() {
        return new TableDefinition("orders",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "S")));
    }

    private ObjectNode item(String pk, String sk, String value) {
        ObjectNode item = mapper.createObjectNode();
        item.set("pk", stringAttribute(pk));
        item.set("sk", stringAttribute(sk));
        item.set("value", stringAttribute(value));
        return item;
    }

    private ObjectNode stringAttribute(String value) {
        ObjectNode attribute = mapper.createObjectNode();
        attribute.put("S", value);
        return attribute;
    }
}
