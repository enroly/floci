package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Buffers a request's entity stream at most once per request and shares the bytes with every
 * caller in that request via a request-scoped property, resetting the entity stream to a fresh
 * copy of the same bytes on the one occasion it actually reads from it.
 *
 * More than one component on the IAM enforcement path needs to look at the same request body:
 * IamActionRegistry (Action, for the Query protocol) and ResourceArnBuilder (a resource
 * identifier such as TableName, QueueUrl, TopicArn or SecretId, for whichever protocol the
 * service actually uses). SqsQueueUrlRouterFilter, a separate pre-matching filter, may also have
 * already rewritten the entity stream before either of these run. Reading a live entity stream
 * more than once would either throw (already consumed) or silently return nothing; buffering it
 * once here, behind a request property, means only the first caller in a request ever touches
 * the real stream, and the real downstream handler still sees the complete, correct body because
 * every reset here uses the same cached bytes.
 */
final class RequestBodyReader {

    private static final Logger LOG = Logger.getLogger(RequestBodyReader.class);
    private static final String BUFFERED_BODY_PROPERTY =
            "io.github.hectorvent.floci.services.iam.RequestBodyReader.bufferedBody";

    private RequestBodyReader() {
    }

    /**
     * Returns the request body bytes, reading and buffering the entity stream on the first call
     * for this request and resetting it to a fresh, fully-intact copy. Every later call in the
     * same request, whether from this class or a different one, returns the cached bytes without
     * touching the stream again.
     */
    static byte[] buffer(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty(BUFFERED_BODY_PROPERTY);
        if (cached instanceof byte[] bytes) {
            return bytes;
        }
        InputStream in = ctx.getEntityStream();
        byte[] body;
        if (in == null) {
            body = new byte[0];
        } else {
            try {
                body = in.readAllBytes();
            } catch (IOException e) {
                LOG.debugv(e, "Failed to buffer request body for IAM enforcement");
                body = new byte[0];
            }
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        ctx.setProperty(BUFFERED_BODY_PROPERTY, body);
        return body;
    }

    /**
     * Looks up a single key in an {@code application/x-www-form-urlencoded} body. Returns
     * {@code null} when the request is not form-encoded or the key is absent.
     */
    static String formField(ContainerRequestContext ctx, String key) {
        MediaType mt = ctx.getMediaType();
        if (mt == null
                || !"application".equalsIgnoreCase(mt.getType())
                || !"x-www-form-urlencoded".equalsIgnoreCase(mt.getSubtype())) {
            return null;
        }
        byte[] body = buffer(ctx);
        if (body.length == 0) {
            return null;
        }
        Charset charset = resolveCharset(mt);
        String form = new String(body, charset);
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            String pairKey = eq < 0 ? pair : pair.substring(0, eq);
            if (!key.equals(URLDecoder.decode(pairKey, charset))) {
                continue;
            }
            return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), charset);
        }
        return null;
    }

    /**
     * Looks up a single top-level string field in a JSON 1.0/1.1 ({@code application/x-amz-json-*})
     * body, the protocol DynamoDB, Kinesis, Secrets Manager, SSM, KMS and modern SQS all use to
     * carry their target resource identifier. Returns {@code null} when the request is not JSON,
     * the body doesn't parse, or the field is absent or not a string.
     */
    static String jsonField(ContainerRequestContext ctx, ObjectMapper objectMapper, String key) {
        MediaType mt = ctx.getMediaType();
        if (mt == null || !"application".equalsIgnoreCase(mt.getType())
                || mt.getSubtype() == null || !mt.getSubtype().startsWith("x-amz-json")) {
            return null;
        }
        byte[] body = buffer(ctx);
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode value = objectMapper.readTree(body).path(key);
            return value.isTextual() ? value.asText() : null;
        } catch (IOException e) {
            LOG.debugv(e, "Failed to parse JSON request body for IAM enforcement");
            return null;
        }
    }

    private static Charset resolveCharset(MediaType mt) {
        String name = mt.getParameters().get("charset");
        if (name == null || name.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
