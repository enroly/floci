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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbTransactionCollisionIdempotencyTest {

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
    void failedTransactionReplayPreservesTheOriginalFailureAfterTheCollisionIsRemoved() {
        ObjectNode occupant = item("A:", "B");
        ObjectNode requested = item("A", ":B");
        List<JsonNode> transactItems = List.of(transactionPut(requested));
        ObjectNode rawRequest = mapper.createObjectNode();
        rawRequest.putArray("TransactItems").addAll(transactItems);
        rawRequest.put("ClientRequestToken", "collision-token");
        service.putItem("orders", occupant, "us-east-1");

        AwsException firstFailure = assertThrows(AwsException.class, () ->
                service.transactWriteItems(transactItems, "us-east-1", "collision-token", rawRequest));
        service.deleteItem("orders", occupant, "us-east-1");

        AwsException replayFailure = assertThrows(AwsException.class, () ->
                service.transactWriteItems(transactItems, "us-east-1", "collision-token", rawRequest));

        assertEquals(firstFailure.getErrorCode(), replayFailure.getErrorCode());
        assertNull(service.getItem("orders", requested, "us-east-1"));
        service.transactWriteItems(transactItems, "us-east-1");
        assertEquals(requested, service.getItem("orders", requested, "us-east-1"));
    }

    private ObjectNode item(String pk, String sk) {
        ObjectNode item = mapper.createObjectNode();
        item.set("pk", stringAttribute(pk));
        item.set("sk", stringAttribute(sk));
        return item;
    }

    private ObjectNode transactionPut(JsonNode item) {
        ObjectNode put = mapper.createObjectNode();
        put.put("TableName", "orders");
        put.set("Item", item);
        ObjectNode request = mapper.createObjectNode();
        request.set("Put", put);
        return request;
    }

    private ObjectNode stringAttribute(String value) {
        ObjectNode attribute = mapper.createObjectNode();
        attribute.put("S", value);
        return attribute;
    }
}
