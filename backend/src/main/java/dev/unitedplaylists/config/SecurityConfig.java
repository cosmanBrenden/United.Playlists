package dev.unitedplaylists.config;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Wires the local shared-secret filter.
 *
 * <p>Spring Security itself is not used: there are no users, roles, or sessions in
 * a single-user local app, so it would be ceremony around a single check that
 * {@link LocalAuthFilter} already does.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * The secret Electron must present.
     *
     * <p>Generated per run unless one is supplied. A fresh secret each start means a
     * stale value cannot be replayed against a later session, and nothing needs to
     * be stored.
     */
    @Bean
    public LocalApiToken localApiToken(Environment environment) {
        String configured = environment.getProperty("unitedplaylists.security.api-token");
        if (configured != null && !configured.isBlank()) {
            return new LocalApiToken(configured);
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        log.info("Generated a local API token for this session");
        return new LocalApiToken(generated);
    }

    /**
     * CORS for the Electron renderer.
     *
     * <p>Needed because the UI and the backend are always different origins: the
     * renderer is served from a Vite dev server in development and from {@code file://}
     * when packaged, while the backend is on an ephemeral loopback port. Every call
     * the app makes is therefore cross-origin, and every one carries the
     * {@code X-UnitedPlaylists-Token} header, which makes it a "non-simple" request
     * that the browser preflights first.
     *
     * <p>This is not a loosening of the shared-secret rule. Only loopback origins are
     * allowed, {@link LocalAuthFilter} still rejects foreign ones by exact host match,
     * and the real request still needs the secret — CORS only governs what the browser
     * is willing to let a page read.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:[*]",
                "http://127.0.0.1:[*]",
                "http://[::1]:[*]",
                // A packaged Electron renderer loads over file://, and Chromium sends
                // "null" as the origin for it. Without this, the app works in dev and
                // breaks the moment it is packaged.
                "null",
                "file://"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(LocalAuthFilter.HEADER, "Content-Type", "Accept"));
        // No cookies are involved; the secret travels in a header. Leaving this false
        // keeps the origin patterns usable and grants the browser nothing extra.
        config.setAllowCredentials(false);
        // Cache the preflight, so the browser does not pay two round trips per call.
        config.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns("/*");
        // Ahead of LocalAuthFilter: a preflight carries no credentials by design, so
        // the auth filter would 401 it and the browser would never send the real
        // request. CorsFilter answers the preflight itself and stops the chain.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<LocalAuthFilter> localAuthFilter(LocalApiToken token) {
        FilterRegistrationBean<LocalAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new LocalAuthFilter(token.value()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** Wrapper so the token can be injected without colliding with other Strings. */
    public record LocalApiToken(String value) {
    }
}
