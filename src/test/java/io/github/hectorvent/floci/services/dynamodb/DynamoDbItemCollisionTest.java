package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbItemCollisionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"), "::");
        service.createTable("orders",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "S")),
                5L, 5L, "us-east-1");
    }

    @Test
    void pointOperationsDoNotActOnADifferentIdentityAtTheSameStorageAddress() {
        ObjectNode left = item("A:", "B", "left");
        ObjectNode right = item("A", ":B", "right");
        service.putItem("orders", left, "us-east-1");

        assertNull(service.getItem("orders", right, "us-east-1"));
        assertNull(service.deleteItem("orders", right, "us-east-1"));
        assertThrows(AwsException.class, () -> service.putItem("orders", right, "us-east-1"));
        assertThrows(AwsException.class, () -> service.updateItem("orders", right, null,
                "SET value = :value", null, expressionValues("changed"), "NONE", "us-east-1"));

        assertEquals(left, service.getItem("orders", left, "us-east-1"));
    }

    @Test
    void updateRejectsRemovalOfEitherPrimaryKeyAttribute() {
        ObjectNode item = item("account", "order", "value");
        service.putItem("orders", item, "us-east-1");

        assertThrows(AwsException.class, () -> service.updateItem("orders", item, null,
                "REMOVE sk", null, null, "NONE", "us-east-1"));

        assertEquals(item, service.getItem("orders", item, "us-east-1"));
    }

    @Test
    void queryPaginationResumesWhenConfiguredDelimiterAppearsInPrimaryKeyValues() {
        ObjectNode firstItem = item("account::", "first::", "first");
        ObjectNode secondItem = item("account::", "second::", "second");
        service.putItem("orders", firstItem, "us-east-1");
        service.putItem("orders", secondItem, "us-east-1");
        ObjectNode values = mapper.createObjectNode();
        values.set(":pk", stringAttribute("account::"));

        DynamoDbService.QueryResult firstPage = service.query("orders", null, values,
                "pk = :pk", null, 1, null, null, null, null, "us-east-1");
        DynamoDbService.QueryResult secondPage = service.query("orders", null, values,
                "pk = :pk", null, 1, null, null, firstPage.lastEvaluatedKey(), null, "us-east-1");

        assertEquals(List.of(firstItem), firstPage.items());
        assertEquals(List.of(secondItem), secondPage.items());
    }

    @Test
    void batchAndTransactionRejectCollisionsBeforeApplyingAnyWrite() {
        ObjectNode left = item("A:", "B", "left");
        ObjectNode right = item("A", ":B", "right");

        assertThrows(AwsException.class, () -> service.batchWriteItem(Map.of("orders", List.of(
                putRequest(left), putRequest(right))), "us-east-1"));
        assertNull(service.getItem("orders", left, "us-east-1"));
        assertNull(service.getItem("orders", right, "us-east-1"));

        ObjectNode independent = item("independent", "item", "independent");
        assertThrows(AwsException.class, () -> service.transactWriteItems(List.of(
                transactionPut(independent), transactionPut(left), transactionPut(right)), "us-east-1"));
        assertNull(service.getItem("orders", independent, "us-east-1"));
        assertNull(service.getItem("orders", left, "us-east-1"));
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

    private ObjectNode expressionValues(String value) {
        ObjectNode values = mapper.createObjectNode();
        values.set(":value", stringAttribute(value));
        return values;
    }

    private ObjectNode putRequest(JsonNode item) {
        ObjectNode request = mapper.createObjectNode();
        request.set("PutRequest", mapper.createObjectNode().set("Item", item));
        return request;
    }

    private ObjectNode transactionPut(JsonNode item) {
        ObjectNode request = mapper.createObjectNode();
        ObjectNode put = mapper.createObjectNode();
        put.put("TableName", "orders");
        put.set("Item", item);
        request.set("Put", put);
        return request;
    }
}
