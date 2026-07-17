package dev.unitedplaylists.service;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.oauth.AuthorizationRequest;
import dev.unitedplaylists.provider.oauth.OAuthClient;
import dev.unitedplaylists.provider.oauth.OAuthProperties;
import dev.unitedplaylists.provider.oauth.TokenSet;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Drives the OAuth authorization flow.
 *
 * <p>Holds the pending state and PKCE verifier between opening the browser and the
 * callback arriving. In memory rather than in the database: these live for seconds,
 * and writing a code verifier to disk gives it a lifetime it should not have.
 */
@Service
public class OAuthFlowService {

    /**
     * How long a started authorization stays valid. Long enough to sign in and pass
     * a 2FA prompt, short enough that an abandoned attempt cannot be completed by
     * someone else later.
     */
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    private final Map<ProviderId, OAuthClient> clients = new EnumMap<>(ProviderId.class);
    private final Map<ProviderId, Pending> pending = new ConcurrentHashMap<>();
    private final ConnectionService connectionService;
    private final Clock clock;
    private final String redirectUri;

    public OAuthFlowService(
            List<OAuthClient> oauthClients,
            ConnectionService connectionService,
            Clock clock,
            OAuthProperties oauthProperties) {
        this.connectionService = connectionService;
        this.clock = clock;
        // Same source as the URI shown to the user on the setup screen. These once
        // had separate inline defaults, which disagreed on the port.
        this.redirectUri = oauthProperties.redirectUri();
        for (OAuthClient client : oauthClients) {
            clients.put(client.provider(), client);
        }
    }

    /** @return the URL to open in the user's browser */
    public String beginAuthorization(ProviderId providerId) {
        OAuthClient client = require(providerId);
        AuthorizationRequest request = client.buildAuthorizationRequest(redirectUri);
        pending.put(providerId, new Pending(
                request.state(), request.codeVerifier(), clock.instant().plus(PENDING_TTL)));
        return request.authorizationUrl();
    }

    /**
     * Completes the flow and stores the connection.
     *
     * @throws ProviderException if state does not match, which means either a stale
     *     callback or a forged one
     */
    public void completeAuthorization(ProviderId providerId, String code, String state) {
        OAuthClient client = require(providerId);
        Pending expected = pending.remove(providerId);

        if (expected == null) {
            throw new ProviderException(
                    providerId, ProviderException.Kind.UNAUTHORIZED,
                    "No authorization is in progress for " + providerId);
        }
        if (clock.instant().isAfter(expected.expiresAt())) {
            throw new ProviderException(
                    providerId, ProviderException.Kind.UNAUTHORIZED,
                    "Authorization timed out; start again");
        }
        // The whole point of state: without this check, a page the user visits could
        // hand us an authorization code for the attacker's account and quietly bind
        // the user's app to it.
        if (!constantTimeEquals(state, expected.state())) {
            throw new ProviderException(
                    providerId, ProviderException.Kind.UNAUTHORIZED,
                    "Authorization state did not match");
        }

        TokenSet tokens = client.exchangeCode(code, expected.codeVerifier(), redirectUri);
        connectionService.connect(providerId, tokens, null, null);
    }

    /** Drops expired pending authorizations so an abandoned flow cannot be resumed. */
    public void evictExpired() {
        Instant now = clock.instant();
        pending.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
    }

    private OAuthClient require(ProviderId providerId) {
        OAuthClient client = clients.get(providerId);
        if (client == null) {
            throw new ProviderException(
                    providerId, ProviderException.Kind.UNSUPPORTED,
                    providerId + " does not support OAuth sign-in");
        }
        return client;
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private record Pending(String state, String codeVerifier, Instant expiresAt) {
    }
}
