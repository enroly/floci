package io.github.hectorvent.floci.core.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the IAM condition context for one request: the {@code aws:*} keys that describe the
 * request itself, plus any service-specific keys the operation contributes.
 *
 * <p>A condition key that is absent from this map is not "ignored": a statement carrying it can
 * never be satisfied, so {@code IamPolicyEvaluator} drops that statement entirely and the call it
 * was meant to permit is denied. That makes an unpopulated global key a false denial on a grant
 * that is completely correct, which is the one failure mode local enforcement must not have: it
 * sends a developer off to widen a CDK policy that was never wrong.
 *
 * <p>Only facts about the request go in here. Floci is a single-account, single-partition emulator,
 * so the account on the credential is the account that owns every resource the request can reach;
 * that is what makes {@code aws:ResourceAccount} a fact rather than a guess. Keys whose true value
 * Floci cannot know are deliberately left absent, so a policy that depends on one fails closed
 * exactly as it does on AWS rather than being satisfied by an invented value. That set is:
 * <ul>
 *   <li>{@code aws:SecureTransport}, because Floci serves plain HTTP where AWS is always TLS, so either
 *       value is a lie, and it is the one key where guessing wrong turns a Deny on (the CDK
 *       bootstrap's {@code AllowSSLRequestsOnly} bucket policy is the live example)</li>
 *   <li>{@code aws:SourceArn}, {@code aws:SourceAccount}, {@code aws:ViaAWSService}, which are service-to-
 *       service keys; a direct SDK call genuinely has no value for them on AWS either</li>
 *   <li>{@code aws:PrincipalArn}, {@code aws:userid}, {@code aws:username}, knowable but only by
 *       a second IAM lookup keyed on the access key id, which this resolver does not have</li>
 * </ul>
 */
@ApplicationScoped
public class IamConditionContextResolver {

    /**
     * @param credentialScope canonical service name from the request's credential scope
     * @param action          resolved IAM action, e.g. {@code s3:ListBucket}
     * @param ctx             the in-flight request
     * @param resourceArn     the ARN the action is being evaluated against; may be null
     * @param region          the region the request resolved to; may be null
     * @param accountId       the account the request's credentials belong to; may be null
     */
    public Map<String, String> resolve(String credentialScope, String action, ContainerRequestContext ctx,
                                       String resourceArn, String region, String accountId) {
        Map<String, String> conditions = globalConditionContext(resourceArn, region, accountId);
        Map<String, String> serviceConditions = serviceConditionContext(credentialScope, action, ctx);
        if (serviceConditions != null) {
            conditions.putAll(serviceConditions);
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /**
     * The request-shaped {@code aws:*} keys, populated for every service and every action rather
     * than per operation. These describe the caller and the target, not the operation, so a
     * per-service switch is the wrong shape for them: leaving them to one is what made every
     * statement with a global condition key unsatisfiable outside the single S3 case below.
     */
    Map<String, String> globalConditionContext(String resourceArn, String region, String accountId) {
        Map<String, String> conditions = new LinkedHashMap<>();
        String resourceAccount = accountFromArn(resourceArn);
        if (isBlank(resourceAccount)) {
            // S3 ARNs carry no account segment at all, and neither do a few others. In a
            // single-account emulator the resource is owned by the account making the request.
            resourceAccount = accountId;
        }
        putIfPresent(conditions, "aws:ResourceAccount", resourceAccount);
        putIfPresent(conditions, "aws:PrincipalAccount", accountId);
        putIfPresent(conditions, "aws:RequestedRegion", region);
        return conditions;
    }

    private Map<String, String> serviceConditionContext(String credentialScope, String action,
                                                        ContainerRequestContext ctx) {
        return switch (credentialScope) {
            case "s3" -> s3ConditionContext(action, ctx);
            default -> null;
        };
    }

    private Map<String, String> s3ConditionContext(String action, ContainerRequestContext ctx) {
        return switch (action) {
            case "s3:ListBucket" -> s3BucketListConditionContext(ctx.getUriInfo().getQueryParameters());
            default -> null;
        };
    }

    Map<String, String> s3BucketListConditionContext(MultivaluedMap<String, String> queryParameters) {
        Map<String, String> conditions = new LinkedHashMap<>();
        addQueryCondition(conditions, "s3:prefix", queryParameters, "prefix");
        addQueryCondition(conditions, "s3:delimiter", queryParameters, "delimiter");
        addQueryCondition(conditions, "s3:max-keys", queryParameters, "max-keys");
        return conditions.isEmpty() ? null : conditions;
    }

    /** Returns the account segment of an ARN ({@code arn:partition:service:region:account:...}). */
    private static String accountFromArn(String arn) {
        if (arn == null || !arn.startsWith("arn:")) {
            return null;
        }
        String[] segments = arn.split(":", 6);
        return segments.length > 4 ? segments[4] : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void putIfPresent(Map<String, String> conditions, String conditionKey, String value) {
        if (!isBlank(value)) {
            conditions.put(conditionKey, value);
        }
    }

    private static void addQueryCondition(Map<String, String> conditions, String conditionKey,
                                          MultivaluedMap<String, String> queryParameters, String queryParameter) {
        String value = queryParameters.getFirst(queryParameter);
        if (value != null) {
            conditions.put(conditionKey, value);
        }
    }
}
