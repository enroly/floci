package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamConditionContextResolverTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "eu-west-2";

    private final IamConditionContextResolver resolver = new IamConditionContextResolver();

    @Test
    void resolvesS3ListBucketQueryConditionContext() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("prefix", "my_namespace/table/");
        query.add("delimiter", "/");
        query.add("max-keys", "100");

        when(containerRequest.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getQueryParameters()).thenReturn(query);

        Map<String, String> conditions = resolver.resolve(
                "s3", "s3:ListBucket", containerRequest, "arn:aws:s3:::my-bucket", REGION, ACCOUNT);

        assertEquals("my_namespace/table/", conditions.get("s3:prefix"));
        assertEquals("/", conditions.get("s3:delimiter"));
        assertEquals("100", conditions.get("s3:max-keys"));
    }

    @Test
    void s3BucketListConditionContextReturnsNullWhenNoSupportedQueryParametersArePresent() {
        assertNull(resolver.s3BucketListConditionContext(new MultivaluedHashMap<>()));
    }

    @Test
    void globalKeysArePopulatedForEveryServiceAndAction() {
        // The bug this pins: the resolver used to return null for anything but s3:ListBucket, so a
        // statement carrying aws:ResourceAccount could never be satisfied and was dropped, denying
        // a call the policy plainly allowed. Global keys describe the request, not the operation.
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);

        Map<String, String> lambda = resolver.resolve("lambda", "lambda:InvokeFunction", containerRequest,
                "arn:aws:lambda:eu-west-2:000000000000:function:some-fn", REGION, ACCOUNT);
        assertEquals(ACCOUNT, lambda.get("aws:ResourceAccount"));
        assertEquals(ACCOUNT, lambda.get("aws:PrincipalAccount"));
        assertEquals(REGION, lambda.get("aws:RequestedRegion"));

        Map<String, String> s3 = resolver.resolve("s3", "s3:GetObject", containerRequest,
                "arn:aws:s3:::my-bucket/key.txt", REGION, ACCOUNT);
        assertEquals(ACCOUNT, s3.get("aws:ResourceAccount"));
    }

    @Test
    void resourceAccountComesFromTheResourceArnWhenItCarriesOne() {
        Map<String, String> conditions = resolver.globalConditionContext(
                "arn:aws:dynamodb:eu-west-2:111111111111:table/students", REGION, ACCOUNT);

        assertEquals("111111111111", conditions.get("aws:ResourceAccount"));
        assertEquals(ACCOUNT, conditions.get("aws:PrincipalAccount"));
    }

    @Test
    void keysFlociCannotKnowStayAbsentSoAPolicyDependingOnThemFailsClosed() {
        Map<String, String> conditions = resolver.globalConditionContext(
                "arn:aws:s3:::my-bucket", REGION, ACCOUNT);

        assertFalse(conditions.containsKey("aws:SecureTransport"));
        assertFalse(conditions.containsKey("aws:SourceArn"));
        assertFalse(conditions.containsKey("aws:PrincipalArn"));
    }

    @Test
    void theContextSatisfiesTheStatementThatUsedToBeDropped() {
        // Verbatim shape from the CDK bootstrap template's FilePublishingRoleDefaultPolicy. With
        // aws:ResourceAccount absent the statement can never be satisfied, so it is dropped and the
        // call is denied - a false denial on a grant that is exactly right. Same policy, same
        // action, same ARN: the only difference between ALLOW and DENY here is the context.
        String policy = """
            {"Version":"2012-10-17","Statement":[{
              "Effect":"Allow",
              "Action":["s3:GetObject*","s3:GetBucket*","s3:List*","s3:PutObject*"],
              "Resource":["arn:aws:s3:::cdk-hnb659fds-assets-000000000000-eu-west-2",
                          "arn:aws:s3:::cdk-hnb659fds-assets-000000000000-eu-west-2/*"],
              "Condition":{"StringEquals":{"aws:ResourceAccount":["000000000000"]}}
            }]}""";
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        CallerContext caller = CallerContext.of(List.of(policy));
        String bucketArn = "arn:aws:s3:::cdk-hnb659fds-assets-000000000000-eu-west-2";

        assertEquals(IamPolicyEvaluator.Decision.DENY,
                evaluator.evaluate(caller, null, "s3:ListBucket", bucketArn, Map.of()),
                "without the key the statement is unsatisfiable, which is the bug");
        assertEquals(IamPolicyEvaluator.Decision.ALLOW,
                evaluator.evaluate(caller, null, "s3:ListBucket", bucketArn,
                        resolver.globalConditionContext(bucketArn, REGION, ACCOUNT)),
                "the resolved context must satisfy it");
    }

    @Test
    void returnsNullOnlyWhenNothingAtAllIsKnown() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);

        assertNull(resolver.resolve("lambda", "lambda:InvokeFunction", containerRequest, null, null, null));
        assertTrue(resolver.globalConditionContext(null, null, null).isEmpty());
    }
}
