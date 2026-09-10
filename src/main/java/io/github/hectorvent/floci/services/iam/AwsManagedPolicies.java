package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

final class AwsManagedPolicies {

    private static final Logger LOG = Logger.getLogger(AwsManagedPolicies.class);

    static final String ARN_PREFIX = "arn:aws:iam::aws:policy";

    private static final String CATALOG_RESOURCE_NAME = "iam/managed-policies.yaml";

    static final String PERMISSIVE_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":"
            + "[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";

    record ManagedPolicyDef(String name, String path, String description, String document) {
        String arn() {
            return ARN_PREFIX + path + name;
        }

        /** The real document when the catalog carries one, otherwise the shared placeholder. */
        String resolvedDocument() {
            return document != null ? document : PERMISSIVE_DOCUMENT;
        }
    }

    static final List<ManagedPolicyDef> POLICIES = load();

    private AwsManagedPolicies() {
    }

    private static List<ManagedPolicyDef> load() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(CATALOG_RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException(
                        "AWS managed policy catalog not found on the classpath: " + CATALOG_RESOURCE_NAME);
            }
            Catalog catalog = new ObjectMapper(new YAMLFactory()).readValue(in, Catalog.class);
            List<ManagedPolicyDef> defs = new ArrayList<>();
            for (CatalogEntry entry : catalog.policies == null ? List.<CatalogEntry>of() : catalog.policies) {
                if (entry.name == null || entry.name.isBlank() || entry.path == null || entry.path.isBlank()) {
                    continue;
                }
                defs.add(new ManagedPolicyDef(entry.name, entry.path, entry.description, entry.document));
            }
            LOG.debugv("Loaded {0} AWS managed policies from {1}", defs.size(), CATALOG_RESOURCE_NAME);
            return List.copyOf(defs);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read the AWS managed policy catalog: " + CATALOG_RESOURCE_NAME, e);
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Catalog {
        public List<CatalogEntry> policies;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class CatalogEntry {
        public String name;
        public String path;
        public String description;
        /** Real AWS policy document JSON, verbatim. Absent for the vast majority of the catalog. */
        public String document;
    }
}
