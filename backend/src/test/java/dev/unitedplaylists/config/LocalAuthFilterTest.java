package dev.unitedplaylists.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for the local access filter.
 *
 * <p>Tested at this level rather than over real HTTP because the JDK's HTTP client
 * refuses to set an {@code Origin} header — it is on {@code HttpURLConnection}'s
 * restricted list — so an end-to-end test physically cannot express the
 * cross-origin attack this filter exists to stop. A browser has no such
 * restriction, which is precisely the threat.
 */
class LocalAuthFilterTest {

    private static final String TOKEN = "correct-horse-battery-staple";

    private LocalAuthFilter filter;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new LocalAuthFilter(TOKEN);
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    private boolean passedThrough() {
        return response.getStatus() == HttpServletResponse.SC_OK;
    }

    @Nested
    @DisplayName("the shared secret")
    class Secret {

        @Test
        void allowsTheCorrectToken() throws Exception {
            MockHttpServletRequest request = request("/api/v1/playlists");
            request.addHeader(LocalAuthFilter.HEADER, TOKEN);

            filter.doFilter(request, response, chain);

            assertThat(passedThrough()).isTrue();
        }

        @Test
        void rejectsAMissingToken() throws Exception {
            filter.doFilter(request("/api/v1/playlists"), response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(response.getContentAsString()).contains("unauthorized");
        }

        @Test
        void rejectsAWrongToken() throws Exception {
            MockHttpServletRequest request = request("/api/v1/playlists");
            request.addHeader(LocalAuthFilter.HEADER, "guessed-wrong");

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("a token that is a prefix of the real one is still rejected")
        void rejectsAPrefixOfTheToken() throws Exception {
            MockHttpServletRequest request = request("/api/v1/playlists");
            request.addHeader(LocalAuthFilter.HEADER, TOKEN.substring(0, TOKEN.length() - 1));

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("cross-origin defence")
    class Origins {

        /**
         * The attack: a page the user is browsing fires a request at the local
         * backend. Without this check it would read their whole library.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "https://evil.example",
                "http://evil.example",
                "https://open.spotify.com",
                // These two defeated an earlier prefix-based check: both start with
                // "http://127.0.0.1" / "http://localhost" but are attacker-controlled
                // domains. They stay here as regression cases.
                "http://127.0.0.1.evil.example",
                "http://localhost.evil.example",
                "http://127.0.0.1@evil.example",
                "https://127.0.0.1",
                "http://notlocalhost"
        })
        void rejectsForeignOriginsEvenWithAValidToken(String origin) throws Exception {
            MockHttpServletRequest request = request("/api/v1/playlists");
            request.addHeader(LocalAuthFilter.HEADER, TOKEN);
            request.addHeader(HttpHeaders.ORIGIN, origin);

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(response.getContentAsString()).contains("Cross-origin");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://127.0.0.1:8420",
                "http://localhost:8420",
                "http://[::1]:8420",
                "file://",
                "null"
        })
        void allowsLoopbackAndElectronOrigins(String origin) throws Exception {
            MockHttpServletRequest request = request("/api/v1/playlists");
            request.addHeader(LocalAuthFilter.HEADER, TOKEN);
            request.addHeader(HttpHeaders.ORIGIN, origin);

            filter.doFilter(request, response, chain);

            assertThat(passedThrough()).isTrue();
        }

        @Test
        @DisplayName("no Origin at all is fine: that is a non-browser client")
        void allowsAbsentOrigin() throws Exception {
            MockHttpServletRequest request = request("/api/v1/playlists");
            request.addHeader(LocalAuthFilter.HEADER, TOKEN);

            filter.doFilter(request, response, chain);

            assertThat(passedThrough()).isTrue();
        }

        @Test
        @DisplayName("origin is checked before the token, so it cannot be probed for")
        void checksOriginBeforeToken() throws Exception {
            MockHttpServletRequest request = request("/api/v1/playlists");
            request.addHeader(HttpHeaders.ORIGIN, "https://evil.example");

            filter.doFilter(request, response, chain);

            assertThat(response.getContentAsString()).contains("Cross-origin");
        }
    }

    @Nested
    @DisplayName("open paths")
    class OpenPaths {

        @ParameterizedTest
        @ValueSource(strings = {
                "/actuator/health",
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/swagger-ui.html",
                "/swagger-ui/index.html"
        })
        void areReachableWithoutTheToken(String path) {
            assertThat(filter.shouldNotFilter(request(path))).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/v1/playlists",
                "/api/v1/search",
                "/api/v1/connections/providers",
                "/api/v1/playback/ticket"
        })
        void everythingElseIsProtected(String path) {
            assertThat(filter.shouldNotFilter(request(path))).isFalse();
        }
    }
}
