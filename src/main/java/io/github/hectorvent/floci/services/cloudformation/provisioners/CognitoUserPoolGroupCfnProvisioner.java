package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::Cognito::UserPoolGroup}. The type had no handler at
 * all, so every group a template declared was stubbed with a synthetic physical id and reported
 * CREATE_COMPLETE while the pool gained nothing.
 *
 * <p>{@code Ref} returns the group name, which is the physical id AWS uses, and the pool id is kept
 * as a resource attribute because a group is addressed by pool id plus name and the physical id
 * carries only the name.
 */
@ApplicationScoped
public class CognitoUserPoolGroupCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CognitoUserPoolGroupCfnProvisioner.class);

    /** Attribute holding the owning pool id, the half of the key the physical id cannot carry. */
    static final String USER_POOL_ID_ATTR = "UserPoolId";

    private final CognitoService cognitoService;

    @Inject
    public CognitoUserPoolGroupCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Cognito::UserPoolGroup");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String userPoolId = ctx.resolveOptional(props, "UserPoolId");
        if (userPoolId == null || userPoolId.isBlank()) {
            throw new IllegalArgumentException("UserPoolId is required for AWS::Cognito::UserPoolGroup");
        }
        String groupName = ctx.resolveOptional(props, "GroupName");
        if (groupName == null || groupName.isBlank()) {
            groupName = ctx.generatePhysicalName(r.getLogicalId(), 128, false);
        }
        String description = ctx.resolveOptional(props, "Description");
        Integer precedence = parsePrecedence(ctx.resolveOptional(props, "Precedence"));
        String roleArn = ctx.resolveOptional(props, "RoleArn");

        // provision is also the update path. A group is addressed by its name, so a renamed group
        // has nothing to update under the new name: that is a replacement on AWS too.
        if (groupName.equals(r.getPhysicalId())) {
            cognitoService.updateGroup(userPoolId, groupName, description, precedence, roleArn);
        } else {
            cognitoService.createGroup(userPoolId, groupName, description, precedence, roleArn);
        }

        r.setPhysicalId(groupName);
        r.getAttributes().put(USER_POOL_ID_ATTR, userPoolId);
    }

    /**
     * The pool id lives in the create-time attributes, not the physical id, so deletion goes
     * through the resource-aware form. Without the pool id there is nothing to address, which is
     * the CREATE-rollback case where the group was never created either.
     */
    @Override
    public void delete(StackResource resource, String region) {
        String userPoolId = resource.getAttributes().get(USER_POOL_ID_ATTR);
        if (userPoolId == null || userPoolId.isBlank()) {
            return;
        }
        try {
            cognitoService.deleteGroup(userPoolId, resource.getPhysicalId());
        } catch (Exception e) {
            // Deleting a group that is already gone is tolerated, as it is for every other type.
            LOG.debugv("Error deleting Cognito group {0}: {1}", resource.getPhysicalId(), e.getMessage());
        }
    }

    /**
     * Precedence decides which group's role wins and the order groups appear in the token, so a
     * value AWS would reject fails the resource rather than being dropped. The monolith's shared
     * helper returned null on a bad number, but this type never had a handler there, so there is
     * no prior behaviour to preserve, and silently losing the value is the failure this whole
     * change is about.
     */
    private static Integer parsePrecedence(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Precedence must be an integer for AWS::Cognito::UserPoolGroup, got: " + value, e);
        }
    }
}
