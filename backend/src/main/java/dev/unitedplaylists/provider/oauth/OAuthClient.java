package dev.unitedplaylists.provider.oauth;

import dev.unitedplaylists.domain.ProviderId;

/**
 * The OAuth 2.0 half of a service integration.
 *
 * <p>Separate from {@link dev.unitedplaylists.provider.MusicProvider} because the
 * two vary independently: Apple Music needs a signed developer token and no
 * refresh flow at all, while Spotify and Google are both plain Authorization Code
 * + PKCE. Splitting them keeps a provider from having to no-op its way through
 * methods that make no sense for it.
 *
 * <p>PKCE is used rather than a client secret because this app ships to users'
 * machines. Anything embedded in the binary is public, so there is no such thing
 * as a confidential client here.
 */
public interface OAuthClient {

    ProviderId provider();

    /** Builds the URL to send the user to, along with the state and verifier to keep. */
    AuthorizationRequest buildAuthorizationRequest(String redirectUri);

    /**
     * Trades an authorization code for tokens.
     *
     * @throws dev.unitedplaylists.provider.ProviderException if the exchange is rejected
     */
    TokenSet exchangeCode(String code, String codeVerifier, String redirectUri);

    /**
     * Trades a refresh token for a fresh access token.
     *
     * @throws dev.unitedplaylists.provider.ProviderException with kind
     *     {@code UNAUTHORIZED} if the refresh token has been revoked, which means
     *     the user must reconnect
     */
    TokenSet refresh(String refreshToken);
}
