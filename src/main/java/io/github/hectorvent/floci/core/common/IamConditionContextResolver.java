package io.github.hectorvent.floci.core.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class IamConditionContextResolver {

    public Map<String, String> resolve(String credentialScope, String action, ContainerRequestContext ctx,
                                       String resourceArn, String region, String accountId) {
        Map<String, String> conditions = globalConditionContext(resourceArn, region, accountId);
        Map<String, String> serviceConditions = serviceConditionContext(credentialScope, action, ctx);
        if (serviceConditions != null) {
            conditions.putAll(serviceConditions);
        }
        return conditions.isEmpty() ? null : conditions;
    }

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
