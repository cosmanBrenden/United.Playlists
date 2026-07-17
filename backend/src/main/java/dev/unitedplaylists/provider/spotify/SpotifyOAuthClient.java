package dev.unitedplaylists.provider.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.HttpSupport;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.oauth.AuthorizationRequest;
import dev.unitedplaylists.provider.oauth.OAuthClient;
import dev.unitedplaylists.provider.oauth.Pkce;
import dev.unitedplaylists.provider.oauth.TokenSet;
import dev.unitedplaylists.service.ProviderSettingsService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/** Spotify Authorization Code + PKCE. */
@Component
public class SpotifyOAuthClient implements OAuthClient {

    private final RestClient http;
    private final SpotifyProperties properties;
    private final ProviderSettingsService settings;
    private final Clock clock;

    public SpotifyOAuthClient(
            RestClient.Builder builder,
            SpotifyProperties properties,
            ProviderSettingsService settings,
            Clock clock) {
        this.properties = properties;
        this.settings = settings;
        this.clock = clock;
        this.http = HttpSupport.withErrorHandling(builder.clone(), ProviderId.SPOTIFY)
                .baseUrl(properties.authBaseUrl())
                .build();
    }

    /**
     * @throws ProviderException if no client id has been configured, which means the
     *     user reached a connect button they should never have been shown
     */
    private String clientId() {
        return settings.clientId(ProviderId.SPOTIFY)
                .orElseThrow(() -> new ProviderException(
                        ProviderId.SPOTIFY,
                        ProviderException.Kind.UNSUPPORTED,
                        "No Spotify client ID has been set up yet"));
    }

    @Override
    public ProviderId provider() {
        return ProviderId.SPOTIFY;
    }

    @Override
    public AuthorizationRequest buildAuthorizationRequest(String redirectUri) {
        String verifier = Pkce.generateVerifier();
        String state = Pkce.generateState();
        String url = UriComponentsBuilder.fromUriString(properties.authBaseUrl())
                .path("/authorize")
                .queryParam("client_id", clientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("code_challenge_method", "S256")
                .queryParam("code_challenge", Pkce.challengeFor(verifier))
                .queryParam("state", state)
                .queryParam("scope", SpotifyProperties.SCOPES)
                .encode()
                .toUriString();
        return new AuthorizationRequest(url, state, verifier);
    }

    @Override
    public TokenSet exchangeCode(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", clientId());
        form.add("code_verifier", codeVerifier);
        return postToken(form);
    }

    @Override
    public TokenSet refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", clientId());
        return postToken(form);
    }

    private TokenSet postToken(MultiValueMap<String, String> form) {
        try {
            JsonNode body = http.post()
                    .uri("/api/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null || body.path("access_token").asText(null) == null) {
                throw new ProviderException(
                        ProviderId.SPOTIFY,
                        ProviderException.Kind.UNAUTHORIZED,
                        "Spotify token response contained no access_token");
            }
            // expires_in is relative to now, so it is resolved at the moment of the
            // response; storing the relative value would make it meaningless later.
            long expiresIn = body.path("expires_in").asLong(3600);
            return new TokenSet(
                    body.path("access_token").asText(),
                    // Spotify only returns refresh_token on the initial exchange, and on
                    // refresh only when it chooses to rotate. Null means "keep the old one".
                    body.path("refresh_token").asText(null),
                    Instant.now(clock).plusSeconds(expiresIn),
                    body.path("scope").asText(null));
        } catch (RestClientException e) {
            throw HttpSupport.transportFailure(ProviderId.SPOTIFY, e);
        }
    }
}
