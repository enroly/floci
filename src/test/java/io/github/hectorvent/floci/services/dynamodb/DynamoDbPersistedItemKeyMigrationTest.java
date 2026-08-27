package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbPersistedItemKeyMigrationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StorageBackend<String, TableDefinition> tableStore;
    private StorageBackend<String, Map<String, JsonNode>> itemStore;

    @BeforeEach
    void setUp() {
        tableStore = new InMemoryStorage<>();
        itemStore = new InMemoryStorage<>();
    }

    @Test
    void migratesLegacyHashKeysToTheConfiguredLiteralDelimiter() {
        TableDefinition table = compositeTable("orders");
        tableStore.put("us-east-1::orders", table);
        itemStore.put("us-east-1::orders", new HashMap<>(Map.of("account#order", item("account", "order", "value"))));

        service().loadPersistedItems();

        assertEquals(Map.of("account::order", item("account", "order", "value")),
                itemStore.get("us-east-1::orders").orElseThrow());
    }

    @Test
    void normalizesMixedLegacyAndCurrentEntriesIdempotently() {
        TableDefinition table = compositeTable("orders");
        ObjectNode item = item("account", "order", "value");
        tableStore.put("us-east-1::orders", table);
        itemStore.put("us-east-1::orders", new HashMap<>(Map.of(
                "account#order", item,
                "account::order", item.deepCopy())));

        DynamoDbService service = service();
        service.loadPersistedItems();
        service.loadPersistedItems();

        assertEquals(Map.of("account::order", item), itemStore.get("us-east-1::orders").orElseThrow());
    }

    @Test
    void doesNotWriteAnyMigrationBeforeEveryPersistedTableIsValidated() {
        tableStore.put("us-east-1::orders", compositeTable("orders"));
        tableStore.put("us-east-1::broken", compositeTable("broken"));
        Map<String, JsonNode> validItems = new HashMap<>(Map.of(
                "account#order", item("account", "order", "value")));
        Map<String, JsonNode> brokenItems = new HashMap<>(Map.of(
                "account#order", missingSortKeyItem("account")));
        itemStore.put("us-east-1::orders", validItems);
        itemStore.put("us-east-1::broken", brokenItems);

        assertThrows(IllegalStateException.class, () -> service().loadPersistedItems());

        assertEquals(validItems, itemStore.get("us-east-1::orders").orElseThrow());
        assertEquals(brokenItems, itemStore.get("us-east-1::broken").orElseThrow());
    }

    @Test
    void failsClosedWhenDistinctIdentitiesOverlapAtTheConfiguredDelimiter() {
        tableStore.put("us-east-1::orders", compositeTable("orders"));
        Map<String, JsonNode> persistedItems = new HashMap<>();
        persistedItems.put("legacy-left", item("A:", "B", "left"));
        persistedItems.put("legacy-right", item("A", ":B", "right"));
        itemStore.put("us-east-1::orders", persistedItems);

        assertThrows(IllegalStateException.class, () -> service().loadPersistedItems());

        assertEquals(persistedItems, itemStore.get("us-east-1::orders").orElseThrow());
    }

    @Test
    void leavesHashOnlyPersistedMapsUnchanged() {
        tableStore.put("us-east-1::accounts", hashOnlyTable("accounts"));
        Map<String, JsonNode> persistedItems = new HashMap<>(Map.of(
                "account::with-delimiter", hashOnlyItem("account::with-delimiter")));
        itemStore.put("us-east-1::accounts", persistedItems);

        service().loadPersistedItems();

        assertEquals(persistedItems, itemStore.get("us-east-1::accounts").orElseThrow());
    }

    @Test
    void normalizesHashOnlyNumericKeysFromTheItemBody() {
        tableStore.put("us-east-1::accounts", numericHashOnlyTable("accounts"));
        ObjectNode item = numericHashOnlyItem("1.0");
        itemStore.put("us-east-1::accounts", new HashMap<>(Map.of("1.0", item)));

        DynamoDbService service = service();
        service.loadPersistedItems();

        assertEquals(Map.of("1", item), itemStore.get("us-east-1::accounts").orElseThrow());
        assertEquals(item, service.getItem("accounts", item, "us-east-1"));
    }

    @Test
    void failsClosedOnAnUnkeyableHashOnlyBodyWithoutRewritingTheMap() {
        tableStore.put("us-east-1::accounts", hashOnlyTable("accounts"));
        Map<String, JsonNode> persistedItems = new HashMap<>(Map.of(
                "account", mapper.createObjectNode()));
        itemStore.put("us-east-1::accounts", persistedItems);

        assertThrows(IllegalStateException.class, () -> service().loadPersistedItems());

        assertEquals(persistedItems, itemStore.get("us-east-1::accounts").orElseThrow());
    }

    private DynamoDbService service() {
        return new DynamoDbService(tableStore, itemStore,
                new RegionResolver("us-east-1", "000000000000"), "::");
    }

    private TableDefinition compositeTable(String tableName) {
        return new TableDefinition(tableName,
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "S")));
    }

    private TableDefinition hashOnlyTable(String tableName) {
        return new TableDefinition(tableName,
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")));
    }

    private TableDefinition numericHashOnlyTable(String tableName) {
        return new TableDefinition(tableName,
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "N")));
    }

    private ObjectNode numericHashOnlyItem(String pk) {
        ObjectNode item = mapper.createObjectNode();
        ObjectNode attribute = mapper.createObjectNode();
        attribute.put("N", pk);
        item.set("pk", attribute);
        return item;
    }

    private ObjectNode item(String pk, String sk, String value) {
        ObjectNode item = mapper.createObjectNode();
        item.set("pk", stringAttribute(pk));
        item.set("sk", stringAttribute(sk));
        item.set("value", stringAttribute(value));
        return item;
    }

    private ObjectNode hashOnlyItem(String pk) {
        ObjectNode item = mapper.createObjectNode();
        item.set("pk", stringAttribute(pk));
        return item;
    }

    private ObjectNode missingSortKeyItem(String pk) {
        ObjectNode item = mapper.createObjectNode();
        item.set("pk", stringAttribute(pk));
        return item;
    }

    private ObjectNode stringAttribute(String value) {
        ObjectNode attribute = mapper.createObjectNode();
        attribute.put("S", value);
        return attribute;
    }
}
