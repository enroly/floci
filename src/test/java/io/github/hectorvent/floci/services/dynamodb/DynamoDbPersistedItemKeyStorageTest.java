package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbPersistedItemKeyStorageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void migratesLegacyKeysStoredOnDiskAndKeepsThemReachableAfterAnotherRestart(@TempDir Path temporaryDirectory) {
        StorageBackend<String, TableDefinition> persistedTables = persistentTables(temporaryDirectory.resolve("tables.json"));
        StorageBackend<String, Map<String, JsonNode>> persistedItems = persistentItems(temporaryDirectory.resolve("items.json"));
        TableDefinition table = compositeTable();
        ObjectNode item = item("account", "order", "value");
        persistedTables.put("us-east-1::orders", table);
        persistedItems.put("us-east-1::orders", new HashMap<>(Map.of("account#order", item)));

        StorageBackend<String, TableDefinition> restartedTables = persistentTables(temporaryDirectory.resolve("tables.json"));
        StorageBackend<String, Map<String, JsonNode>> restartedItems = persistentItems(temporaryDirectory.resolve("items.json"));
        DynamoDbService migrated = service(restartedTables, restartedItems);
        migrated.loadPersistedItems();

        assertEquals(item, migrated.getItem("orders", item, "us-east-1"));
        assertEquals(Map.of("account::order", item), restartedItems.get("us-east-1::orders").orElseThrow());

        StorageBackend<String, TableDefinition> secondRestartTables = persistentTables(temporaryDirectory.resolve("tables.json"));
        StorageBackend<String, Map<String, JsonNode>> secondRestartItems = persistentItems(temporaryDirectory.resolve("items.json"));
        DynamoDbService reopened = service(secondRestartTables, secondRestartItems);
        reopened.loadPersistedItems();

        assertEquals(item, reopened.getItem("orders", item, "us-east-1"));
    }

    @Test
    void migratesEveryAccountAwareItemMapUsingItsOwningAccount() {
        String firstAccount = "111111111111";
        String secondAccount = "222222222222";
        AccountAwareStorageBackend<TableDefinition> accountTables = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Map<String, JsonNode>> accountItems =
                AccountAwareStorageBackend.inMemory("000000000000");
        accountTables.putForAccount(firstAccount, "us-east-1::orders", compositeTable());
        accountTables.putForAccount(secondAccount, "us-east-1::orders", compositeTable());
        accountItems.putForAccount(firstAccount, "us-east-1::orders", new HashMap<>(Map.of(
                "first#order", item("first", "order", "first"))));
        accountItems.putForAccount(secondAccount, "us-east-1::orders", new HashMap<>(Map.of(
                "second#order", item("second", "order", "second"))));

        service(accountTables, accountItems).loadPersistedItems();

        assertEquals(Map.of("first::order", item("first", "order", "first")),
                accountItems.getForAccount(firstAccount, "us-east-1::orders").orElseThrow());
        assertEquals(Map.of("second::order", item("second", "order", "second")),
                accountItems.getForAccount(secondAccount, "us-east-1::orders").orElseThrow());
    }

    @Test
    void leavesLegacyAccountScopedEntriesUntouchedWhenMigrationPlanningFails() {
        InMemoryStorage<String, TableDefinition> tableDelegate = new InMemoryStorage<>();
        InMemoryStorage<String, Map<String, JsonNode>> itemDelegate = new InMemoryStorage<>();
        AccountAwareStorageBackend<TableDefinition> accountTables =
                new AccountAwareStorageBackend<>(tableDelegate, null, "000000000000");
        AccountAwareStorageBackend<Map<String, JsonNode>> accountItems =
                new AccountAwareStorageBackend<>(itemDelegate, null, "000000000000");
        ObjectNode invalidItem = mapper.createObjectNode();
        invalidItem.set("pk", stringAttribute("account"));
        tableDelegate.put("us-east-1::orders", compositeTable());
        itemDelegate.put("us-east-1::orders", new HashMap<>(Map.of("legacy", invalidItem)));

        assertThrows(IllegalStateException.class, () -> service(accountTables, accountItems).loadPersistedItems());

        assertEquals(Set.of("us-east-1::orders"), tableDelegate.keys());
        assertEquals(Set.of("us-east-1::orders"), itemDelegate.keys());
        assertEquals(Map.of("legacy", invalidItem), itemDelegate.get("us-east-1::orders").orElseThrow());
    }

    private StorageBackend<String, TableDefinition> persistentTables(Path path) {
        PersistentStorage<String, TableDefinition> storage = new PersistentStorage<>(path,
                new TypeReference<Map<String, TableDefinition>>() {});
        storage.load();
        return storage;
    }

    private StorageBackend<String, Map<String, JsonNode>> persistentItems(Path path) {
        PersistentStorage<String, Map<String, JsonNode>> storage = new PersistentStorage<>(path,
                new TypeReference<Map<String, Map<String, JsonNode>>>() {});
        storage.load();
        return storage;
    }

    private DynamoDbService service(StorageBackend<String, TableDefinition> tableStore,
                                    StorageBackend<String, Map<String, JsonNode>> itemStore) {
        return new DynamoDbService(tableStore, itemStore,
                new RegionResolver("us-east-1", "000000000000"), "::");
    }

    private TableDefinition compositeTable() {
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
