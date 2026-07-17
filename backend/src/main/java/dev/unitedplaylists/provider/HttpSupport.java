package dev.unitedplaylists.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.unitedplaylists.domain.ProviderId;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Turns HTTP failures into {@link ProviderException}s.
 *
 * <p>Every service reports the same handful of conditions with the same status
 * codes, so the mapping lives here once instead of in each provider. The mapping
 * matters because callers act on the kind: {@code UNAUTHORIZED} prompts a
 * reconnect, {@code RATE_LIMITED} is worth retrying, {@code FORBIDDEN} usually is
 * not.
 */
public final class HttpSupport {

    private static final Logger log = LoggerFactory.getLogger(HttpSupport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpSupport() {
    }

    /** Installs status handlers that convert 4xx/5xx into {@link ProviderException}. */
    public static RestClient.Builder withErrorHandling(RestClient.Builder builder, ProviderId provider) {
        return builder.defaultStatusHandler(
                status -> status.isError(),
                (request, response) -> {
                    String body = safeBody(response);
                    int code = response.getStatusCode().value();
                    // The raw exchange, logged once, verbatim. Everything downstream is an
                    // interpretation of this; when the interpretation is wrong — and a
                    // bare "Forbidden" gives it little to work with — this is the only
                    // record of what the service actually said.
                    log.warn("{} {} {} -> {} {}",
                            provider, request.getMethod(), request.getURI(), code,
                            body == null || body.isBlank() ? "<empty body>" : truncate(body));
                    throw new ProviderException(provider, kindFor(code), describe(provider, code, body));
                });
    }

    private static ProviderException.Kind kindFor(int status) {
        return switch (status) {
            case 401 -> ProviderException.Kind.UNAUTHORIZED;
            case 403 -> ProviderException.Kind.FORBIDDEN;
            case 404 -> ProviderException.Kind.NOT_FOUND;
            case 429 -> ProviderException.Kind.RATE_LIMITED;
            default -> ProviderException.Kind.UNAVAILABLE;
        };
    }

    private static String describe(ProviderId provider, int status, String body) {
        String hint = switch (status) {
            case 401 -> "token rejected; reconnect " + provider;
            case 403 -> "refused";
            case 429 -> "rate limited";
            default -> "HTTP " + status;
        };
        String reason = extractReason(body);
        return reason == null
                ? provider + ": " + hint
                : provider + ": " + hint + " — " + reason;
    }

    /**
     * Pulls the service's own explanation out of its error body.
     *
     * <p>Worth the effort because these messages are the only thing that identifies
     * the actual problem. Spotify answers a missing dashboard registration and a
     * missing scope with the same bare 403; the message is what tells them apart.
     * Previously this dumped 200 characters of raw JSON into the UI, which buried the
     * one useful sentence in braces and quotes.
     */
    private static String extractReason(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode error = root.path("error");

            // Spotify: {"error":{"status":403,"message":"..."}}
            String message = error.path("message").asText(null);

            // Google: {"error":{"message":"...","errors":[{"reason":"quotaExceeded"}]}}
            String reason = error.path("errors").path(0).path("reason").asText(null);
            if (message != null && reason != null) {
                return message + " (" + reason + ")";
            }
            if (message != null) {
                return message;
            }

            // OAuth error responses: {"error":"invalid_grant","error_description":"..."}
            String oauthError = root.path("error").isTextual() ? root.path("error").asText() : null;
            String description = root.path("error_description").asText(null);
            if (oauthError != null) {
                return description == null ? oauthError : oauthError + ": " + description;
            }
        } catch (JsonProcessingException e) {
            // Not JSON. Fall through to the raw body, which is better than nothing.
        }
        return truncate(body);
    }

    private static String safeBody(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String truncate(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    /**
     * Wraps transport-level failures, which arrive as exceptions rather than status
     * codes and would otherwise escape as raw {@code RestClientException}.
     */
    public static ProviderException transportFailure(ProviderId provider, RestClientException e) {
        boolean timedOut = e instanceof ResourceAccessException
                && (e.getCause() instanceof HttpTimeoutException
                        || e.getCause() instanceof java.net.SocketTimeoutException);
        return new ProviderException(
                provider,
                ProviderException.Kind.UNAVAILABLE,
                provider + (timedOut ? ": timed out" : ": unreachable — " + e.getMessage()),
                e);
    }
}
