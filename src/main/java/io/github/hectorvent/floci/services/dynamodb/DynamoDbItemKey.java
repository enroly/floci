package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

final class DynamoDbItemKey {

    record LogicalIdentity(KeyComponent partition, KeyComponent sort) {}

    record KeyComponent(String type, String value) {}

    private final String delimiter;

    DynamoDbItemKey(String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            throw new IllegalArgumentException("DynamoDB item key delimiter must not be empty");
        }
        this.delimiter = delimiter;
    }

    String storageKey(TableDefinition table, JsonNode item, boolean isKeyArgument) {
        return storageKey(logicalIdentity(table, item, isKeyArgument));
    }

    String storageKey(LogicalIdentity identity) {
        if (identity.sort() == null) {
            return identity.partition().value();
        }
        return cursorKey(identity.partition().value(), identity.sort().value());
    }

    String cursorKey(String firstComponent, String... remainingComponents) {
        StringBuilder key = new StringBuilder(firstComponent);
        for (String component : remainingComponents) {
            key.append(delimiter).append(component);
        }
        return key.toString();
    }

    LogicalIdentity logicalIdentity(TableDefinition table, JsonNode item, boolean isKeyArgument) {
        String partitionKeyName = table.getPartitionKeyName();
        KeyComponent partition = keyComponent(item.get(partitionKeyName), partitionKeyName, isKeyArgument);
        String sortKeyName = table.getSortKeyName();
        if (sortKeyName == null) {
            return new LogicalIdentity(partition, null);
        }
        return new LogicalIdentity(partition, keyComponent(item.get(sortKeyName), sortKeyName, isKeyArgument));
    }

    boolean hasSameIdentity(TableDefinition table, JsonNode expected, JsonNode occupant, boolean isKeyArgument) {
        return logicalIdentity(table, expected, isKeyArgument)
                .equals(logicalIdentity(table, occupant, false));
    }

    static String canonicalScalarValue(JsonNode attributeValue) {
        if (attributeValue == null) {
            return null;
        }
        if (attributeValue.has("S")) {
            return attributeValue.get("S").asText();
        }
        if (attributeValue.has("N")) {
            String raw = attributeValue.get("N").asText();
            try {
                return DynamoDbNumberUtils.validateAndNormalize(raw);
            } catch (Exception ignored) {
                return raw;
            }
        }
        if (attributeValue.has("B")) {
            return attributeValue.get("B").asText();
        }
        if (attributeValue.has("BOOL")) {
            return attributeValue.get("BOOL").asText();
        }
        return attributeValue.asText();
    }

    private KeyComponent keyComponent(JsonNode attributeValue, String keyName, boolean isKeyArgument) {
        if (attributeValue == null) {
            throw missingKey(isKeyArgument);
        }
        if (attributeValue.has("S")) {
            String value = attributeValue.get("S").asText();
            if (value.isEmpty()) {
                throw new AwsException("ValidationException",
                        "One or more parameter values were invalid: "
                                + "The AttributeValue for a key attribute cannot contain an empty string value. Key: " + keyName,
                        400);
            }
            return new KeyComponent("S", value);
        }
        if (attributeValue.has("N")) {
            return new KeyComponent("N", canonicalScalarValue(attributeValue));
        }
        if (attributeValue.has("B")) {
            return new KeyComponent("B", canonicalScalarValue(attributeValue));
        }
        if (attributeValue.has("BOOL")) {
            return new KeyComponent("BOOL", canonicalScalarValue(attributeValue));
        }
        if (!attributeValue.isObject()) {
            return new KeyComponent("RAW", canonicalScalarValue(attributeValue));
        }
        throw new AwsException("ValidationException",
                "One or more parameter values were invalid: The AttributeValue for a key attribute is invalid. Key: " + keyName,
                400);
    }

    private AwsException missingKey(boolean isKeyArgument) {
        if (isKeyArgument) {
            return new AwsException("ValidationException", "The provided key element does not match the schema", 400);
        }
        return new AwsException("ValidationException", "One of the required keys was not given a value", 400);
    }
}
