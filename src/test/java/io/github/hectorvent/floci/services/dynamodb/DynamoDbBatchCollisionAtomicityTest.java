package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoDbBatchCollisionAtomicityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void batchWritePreventsConcurrentCollidingPutsFromCreatingPartialWrites() throws InterruptedException {
        BlockingItemStorage itemStore = new BlockingItemStorage();
        DynamoDbService service = new DynamoDbService(new InMemoryStorage<>(), itemStore,
                new RegionResolver("us-east-1", "000000000000"), "::");
        service.createTable("orders",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "S")),
                5L, 5L, "us-east-1");
        ObjectNode independent = item("independent", "item", "independent");
        ObjectNode left = item("A:", "B", "left");
        ObjectNode right = item("A", ":B", "right");
        AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        AtomicReference<Throwable> pointFailure = new AtomicReference<>();
        Thread batch = new Thread(() -> batchWrite(service, independent, left, batchFailure));
        Thread pointPut = new Thread(() -> pointPut(service, right, pointFailure));

        batch.start();
        assertTrue(itemStore.firstWriteStarted.await(5, TimeUnit.SECONDS));
        pointPut.start();
        boolean pointReachedItemStore = itemStore.secondWriteStarted.await(1, TimeUnit.SECONDS);
        itemStore.releaseSecondWrite.countDown();
        itemStore.releaseFirstWrite.countDown();
        batch.join(5_000);
        pointPut.join(5_000);

        assertFalse(batch.isAlive());
        assertFalse(pointPut.isAlive());
        assertFalse(pointReachedItemStore);
        assertNull(batchFailure.get());
        assertInstanceOf(AwsException.class, pointFailure.get());
        assertEquals(independent, service.getItem("orders", independent, "us-east-1"));
        assertEquals(left, service.getItem("orders", left, "us-east-1"));
    }

    private void batchWrite(DynamoDbService service, JsonNode independent, JsonNode left,
                            AtomicReference<Throwable> failure) {
        try {
            service.batchWriteItem(Map.of("orders", List.of(putRequest(independent), putRequest(left))), "us-east-1");
        } catch (Throwable exception) {
            failure.set(exception);
        }
    }

    private void pointPut(DynamoDbService service, JsonNode right, AtomicReference<Throwable> failure) {
        try {
            service.putItem("orders", right, "us-east-1");
        } catch (Throwable exception) {
            failure.set(exception);
        }
    }

    private ObjectNode item(String pk, String sk, String value) {
        ObjectNode item = mapper.createObjectNode();
        item.set("pk", stringAttribute(pk));
        item.set("sk", stringAttribute(sk));
        item.set("value", stringAttribute(value));
        return item;
    }

    private ObjectNode putRequest(JsonNode item) {
        return mapper.createObjectNode().set("PutRequest", mapper.createObjectNode().set("Item", item));
    }

    private ObjectNode stringAttribute(String value) {
        ObjectNode attribute = mapper.createObjectNode();
        attribute.put("S", value);
        return attribute;
    }

    private static final class BlockingItemStorage implements StorageBackend<String, Map<String, JsonNode>> {
        private final InMemoryStorage<String, Map<String, JsonNode>> delegate = new InMemoryStorage<>();
        private final AtomicBoolean blockFirstWrite = new AtomicBoolean(true);
        private final CountDownLatch firstWriteStarted = new CountDownLatch(1);
        private final CountDownLatch secondWriteStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        private final CountDownLatch releaseSecondWrite = new CountDownLatch(1);

        @Override
        public void put(String key, Map<String, JsonNode> value) {
            try {
                if (blockFirstWrite.compareAndSet(true, false)) {
                    firstWriteStarted.countDown();
                    releaseFirstWrite.await();
                } else {
                    secondWriteStarted.countDown();
                    releaseSecondWrite.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to persist a DynamoDB item", e);
            }
            delegate.put(key, value);
        }

        @Override public Optional<Map<String, JsonNode>> get(String key) { return delegate.get(key); }
        @Override public void delete(String key) { delegate.delete(key); }
        @Override public List<Map<String, JsonNode>> scan(Predicate<String> keyFilter) { return delegate.scan(keyFilter); }
        @Override public Set<String> keys() { return delegate.keys(); }
        @Override public void flush() { delegate.flush(); }
        @Override public void load() { delegate.load(); }
        @Override public void clear() { delegate.clear(); }
    }
}
