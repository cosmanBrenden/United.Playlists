package dev.unitedplaylists.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a shared secret on every request.
 *
 * <p>Binding to loopback is not access control. Every other process on the machine
 * can reach a localhost port, and — the part that catches people out — so can any
 * website the user happens to be visiting: a page at evil.example can fire requests
 * at 127.0.0.1 from the user's own browser. Without this filter, that page could
 * read the user's playlists, and the OAuth callback endpoint would be an open door
 * for injecting an attacker's authorization code.
 *
 * <p>The secret is minted at startup and handed to the Electron main process, which
 * passes it on every call. It never touches disk.
 *
 * <p>Requests are also required to carry no {@code Origin} header, or a loopback
 * one: a real browser attaches {@code Origin} on cross-site requests, so rejecting
 * foreign origins blocks the drive-by case even if the secret ever leaked.
 */
public class LocalAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-UnitedPlaylists-Token";

    /** Reachable without the secret: neither reveals anything nor changes anything. */
    private static final Set<String> OPEN_PATHS = Set.of(
            "/actuator/health", "/v3/api-docs", "/swagger-ui.html");

    /** Hosts that are actually this machine. Matched exactly, never as a prefix. */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1");

    private final byte[] expectedToken;

    public LocalAuthFilter(String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // A CORS preflight carries no credentials — the browser strips custom headers
        // from it by design — so demanding the secret here rejects the preflight, and
        // the browser then never sends the real request at all. The app looks like the
        // backend is unreachable while the backend sits there working perfectly.
        //
        // Waving it through gives nothing away: a preflight neither carries data nor
        // returns any. The real request that follows still needs the secret.
        //
        // CorsFilter is ordered ahead of this and normally answers preflights itself;
        // this check means a future ordering change cannot quietly break the app again.
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String path = request.getRequestURI();
        return OPEN_PATHS.stream().anyMatch(path::startsWith)
                || path.startsWith("/swagger-ui/")
                || path.startsWith("/v3/api-docs/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !isLoopbackOrigin(origin)) {
            reject(response, "Cross-origin requests are not accepted");
            return;
        }

        String presented = request.getHeader(HEADER);
        if (presented == null || !constantTimeEquals(presented)) {
            reject(response, "Missing or invalid " + HEADER);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * True for origins that are genuinely local.
     *
     * <p>The host is parsed and compared exactly rather than prefix-matched. A prefix
     * check looks equivalent and is not: {@code "http://127.0.0.1.evil.example"}
     * starts with {@code "http://127.0.0.1"}, so an attacker only has to register a
     * subdomain of their own domain to walk straight through it.
     */
    private boolean isLoopbackOrigin(String origin) {
        // Electron renderers on a file:// page send one of these.
        if ("null".equals(origin) || "file://".equals(origin)) {
            return true;
        }
        final URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        // URI keeps the brackets on an IPv6 literal.
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /** Constant-time to avoid leaking the secret one byte at a time via timing. */
    private boolean constantTimeEquals(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedToken);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"%s\"}".formatted(message));
    }
}
