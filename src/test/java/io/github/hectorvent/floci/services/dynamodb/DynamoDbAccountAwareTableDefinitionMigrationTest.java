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

class DynamoDbAccountAwareTableDefinitionMigrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String TABLE_KEY = "us-east-1::orders";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsConflictingPhysicalTableSchemasBeforeMutatingTablesOrItems() {
        InMemoryStorage<String, TableDefinition> tableDelegate = new InMemoryStorage<>();
        InMemoryStorage<String, Map<String, JsonNode>> itemDelegate = new InMemoryStorage<>();
        AccountAwareStorageBackend<TableDefinition> tableStore = accountTableStore(tableDelegate);
        AccountAwareStorageBackend<Map<String, JsonNode>> itemStore = accountItemStore(itemDelegate);
        TableDefinition hashOnly = hashOnlyTable();
        TableDefinition composite = compositeTable();
        Map<String, JsonNode> legacyItems = new HashMap<>(Map.of("account", compositeItem("account", "order")));
        tableDelegate.put(TABLE_KEY, hashOnly);
        tableDelegate.put(ACCOUNT_ID + "/" + TABLE_KEY, composite);
        itemDelegate.put(TABLE_KEY, legacyItems);

        assertThrows(IllegalStateException.class, () -> service(tableStore, itemStore).loadPersistedItems());

        assertEquals(Set.of(TABLE_KEY, ACCOUNT_ID + "/" + TABLE_KEY), tableDelegate.keys());
        assertEquals(Set.of(TABLE_KEY), itemDelegate.keys());
        assertEquals(hashOnly, tableDelegate.get(TABLE_KEY).orElseThrow());
        assertEquals(composite, tableDelegate.get(ACCOUNT_ID + "/" + TABLE_KEY).orElseThrow());
        assertEquals(legacyItems, itemDelegate.get(TABLE_KEY).orElseThrow());
    }

    @Test
    void migratesIdenticalPhysicalTableDefinitionsAfterMergingTheirItems() {
        InMemoryStorage<String, TableDefinition> tableDelegate = new InMemoryStorage<>();
        InMemoryStorage<String, Map<String, JsonNode>> itemDelegate = new InMemoryStorage<>();
        AccountAwareStorageBackend<TableDefinition> tableStore = accountTableStore(tableDelegate);
        AccountAwareStorageBackend<Map<String, JsonNode>> itemStore = accountItemStore(itemDelegate);
        ObjectNode legacy = compositeItem("legacy", "one");
        ObjectNode scoped = compositeItem("scoped", "two");
        tableDelegate.put(TABLE_KEY, compositeTable());
        tableDelegate.put(ACCOUNT_ID + "/" + TABLE_KEY, compositeTable());
        itemDelegate.put(TABLE_KEY, new HashMap<>(Map.of("legacy#one", legacy)));
        itemDelegate.put(ACCOUNT_ID + "/" + TABLE_KEY, new HashMap<>(Map.of("scoped#two", scoped)));

        service(tableStore, itemStore).loadPersistedItems();

        assertEquals(Set.of(ACCOUNT_ID + "/" + TABLE_KEY), tableDelegate.keys());
        assertEquals(Set.of(ACCOUNT_ID + "/" + TABLE_KEY), itemDelegate.keys());
        assertEquals(Map.of("legacy::one", legacy, "scoped::two", scoped),
                itemStore.getForAccount(ACCOUNT_ID, TABLE_KEY).orElseThrow());
    }

    private AccountAwareStorageBackend<TableDefinition> accountTableStore(
            InMemoryStorage<String, TableDefinition> delegate) {
        return new AccountAwareStorageBackend<>(delegate, null, ACCOUNT_ID);
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

    private TableDefinition hashOnlyTable() {
        return new TableDefinition("orders", List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")));
    }

    private TableDefinition compositeTable() {
        return new TableDefinition("orders",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "S")));
    }

    private ObjectNode hashOnlyItem(String pk) {
        ObjectNode item = mapper.createObjectNode();
        item.set("pk", stringAttribute(pk));
        return item;
    }

    private ObjectNode compositeItem(String pk, String sk) {
        ObjectNode item = hashOnlyItem(pk);
        item.set("sk", stringAttribute(sk));
        return item;
    }

    private ObjectNode stringAttribute(String value) {
        ObjectNode attribute = mapper.createObjectNode();
        attribute.put("S", value);
        return attribute;
    }
}
