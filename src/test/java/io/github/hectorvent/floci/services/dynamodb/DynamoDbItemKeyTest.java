package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbItemKeyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsToHashDelimiterForCompositeTableKeys() {
        DynamoDbItemKey itemKey = new DynamoDbItemKey("#");

        assertEquals("account#order", itemKey.storageKey(compositeTable(), item("pk", "account", "sk", "order"), false));
    }

    @Test
    void usesConfiguredDelimiterAsAnExactLiteral() {
        DynamoDbItemKey itemKey = new DynamoDbItemKey(".*");

        assertEquals("account.*order", itemKey.storageKey(compositeTable(), item("pk", "account", "sk", "order"), false));
    }

    @Test
    void rejectsAnEmptyDelimiter() {
        assertThrows(IllegalArgumentException.class, () -> new DynamoDbItemKey(""));
    }

    @Test
    void keepsHashOnlyKeysBareWhenTheyContainTheDelimiter() {
        DynamoDbItemKey itemKey = new DynamoDbItemKey("::");

        assertEquals("account::order", itemKey.storageKey(hashOnlyTable(), item("pk", "account::order"), false));
    }

    @Test
    void retainsStructuredIdentityWhenCompositeValuesOverlapAtTheDelimiter() {
        DynamoDbItemKey itemKey = new DynamoDbItemKey("::");
        TableDefinition table = compositeTable();

        DynamoDbItemKey.LogicalIdentity left = itemKey.logicalIdentity(table, item("pk", "A:", "sk", "B"), false);
        DynamoDbItemKey.LogicalIdentity right = itemKey.logicalIdentity(table, item("pk", "A", "sk", ":B"), false);

        assertEquals("A:::B", itemKey.storageKey(left));
        assertEquals(itemKey.storageKey(left), itemKey.storageKey(right));
        assertNotEquals(left, right);
    }

    @Test
    void preservesNumberCanonicalizationInLogicalIdentity() {
        DynamoDbItemKey itemKey = new DynamoDbItemKey("#");
        ObjectNode item = mapper.createObjectNode();
        item.set("pk", attributeValue("N", "1.0"));

        assertEquals("1", itemKey.storageKey(hashOnlyTable(), item, false));
    }

    private TableDefinition hashOnlyTable() {
        return new TableDefinition("hash-only",
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")));
    }

    private TableDefinition compositeTable() {
        return new TableDefinition("composite",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "S")));
    }

    private ObjectNode item(String... values) {
        ObjectNode item = mapper.createObjectNode();
        for (int i = 0; i < values.length; i += 2) {
            item.set(values[i], attributeValue("S", values[i + 1]));
        }
        return item;
    }

    private ObjectNode attributeValue(String type, String value) {
        ObjectNode attributeValue = mapper.createObjectNode();
        attributeValue.put(type, value);
        return attributeValue;
    }
}
