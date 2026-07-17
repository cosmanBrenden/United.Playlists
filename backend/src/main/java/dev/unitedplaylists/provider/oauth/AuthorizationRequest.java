package dev.unitedplaylists.provider.oauth;

import java.util.Objects;

/**
 * A pending authorization, held between opening the browser and the callback.
 *
 * @param authorizationUrl where to send the user
 * @param state            CSRF token; the callback must present this exact value
 * @param codeVerifier     PKCE verifier, kept server-side and never sent to the browser
 */
public record AuthorizationRequest(String authorizationUrl, String state, String codeVerifier) {

    public AuthorizationRequest {
        Objects.requireNonNull(authorizationUrl, "authorizationUrl");
        Objects.requireNonNull(state, "state");
    }
}
