package dev.unitedplaylists.config;

import dev.unitedplaylists.security.TokenCipher;
import dev.unitedplaylists.service.SearchService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Outbound HTTP timeouts are not configured here. Spring Boot applies
 * {@code spring.http.client.connect-timeout} and {@code read-timeout} to the
 * auto-configured {@code RestClient.Builder} that every provider injects, so
 * setting them in {@code application.yml} covers the lot. They are not optional:
 * without them the default is to wait forever, and one unresponsive service would
 * pin a search thread indefinitely. {@code ProviderTimeoutIntegrationTest} proves
 * they are actually applied.
 */
@Configuration
public class AppConfig {

    /**
     * Injected everywhere rather than calling {@code Instant.now()} inline, so tests
     * can pin time instead of sleeping.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TokenCipher tokenCipher(@Value("${unitedplaylists.security.token-key:}") String key) {
        return new TokenCipher(key);
    }

    @Bean(destroyMethod = "shutdown")
    public SearchService.SearchExecutor searchExecutor() {
        return new SearchService.SearchExecutor();
    }
}
