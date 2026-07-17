package dev.unitedplaylists.service;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.ServiceConnection;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.oauth.OAuthClient;
import dev.unitedplaylists.provider.oauth.TokenSet;
import dev.unitedplaylists.repository.ServiceConnectionRepository;
import dev.unitedplaylists.security.TokenCipher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns connected services and their tokens.
 *
 * <p>The one place that decrypts tokens and the one place that refreshes them, so
 * providers can take a valid access token as a given and stay free of both crypto
 * and persistence.
 */
@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    /**
     * Refresh this far before actual expiry. A token that is valid now but expires
     * mid-flight produces a spurious 401, so a request is treated as expired while
     * it still has a minute to live.
     */
    private static final Duration REFRESH_SKEW = Duration.ofMinutes(1);

    private final ServiceConnectionRepository repository;
    private final TokenCipher cipher;
    private final Map<ProviderId, OAuthClient> oauthClients = new EnumMap<>(ProviderId.class);
    private final Clock clock;

    public ConnectionService(
            ServiceConnectionRepository repository,
            TokenCipher cipher,
            List<OAuthClient> clients,
            Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.clock = clock;
        for (OAuthClient client : clients) {
            oauthClients.put(client.provider(), client);
        }
    }

    /**
     * Credentials for every connected service, refreshed as needed.
     *
     * <p>A service whose tokens cannot be recovered is dropped from the list rather
     * than throwing: one dead connection must not stop the user searching the
     * services that still work. The UI surfaces the dead one via
     * {@link #listConnections()}.
     */
    @Transactional
    public List<ProviderCredentials> activeCredentials() {
        List<ProviderCredentials> out = new ArrayList<>();
        for (ServiceConnection connection : repository.findAll()) {
            try {
                out.add(toCredentials(refreshIfNeeded(connection)));
            } catch (ProviderException | TokenCipher.TokenDecryptionException e) {
                log.warn("Skipping {}: credentials unusable ({})",
                        connection.getProvider(), e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Credentials for one service.
     *
     * @throws ProviderException {@code UNAUTHORIZED} if not connected or unrecoverable
     */
    @Transactional
    public ProviderCredentials credentialsFor(ProviderId provider) {
        ServiceConnection connection = repository.findById(provider)
                .orElseThrow(() -> new ProviderException(
                        provider, ProviderException.Kind.UNAUTHORIZED, provider + " is not connected"));
        return toCredentials(refreshIfNeeded(connection));
    }

    /** Stores a newly authorized service, replacing any previous connection to it. */
    @Transactional
    public ServiceConnection connect(
            ProviderId provider, TokenSet tokens, String accountLabel, String market) {
        ServiceConnection connection = new ServiceConnection(
                provider,
                cipher.encrypt(tokens.accessToken()),
                cipher.encrypt(tokens.refreshToken()),
                tokens.expiresAt(),
                tokens.scopes(),
                accountLabel,
                market,
                clock.instant());
        ServiceConnection saved = repository.save(connection);
        log.info("Connected {} for account {}", provider, accountLabel);
        return saved;
    }

    /**
     * Forgets a service.
     *
     * <p>Playlists imported from it are deliberately left alone. They are local
     * copies, and silently deleting a user's library because they unlinked an
     * account would be a nasty surprise; the tracks simply become unplayable until
     * the service is reconnected.
     */
    @Transactional
    public void disconnect(ProviderId provider) {
        repository.deleteById(provider);
        log.info("Disconnected {}", provider);
    }

    public boolean isConnected(ProviderId provider) {
        return repository.existsById(provider);
    }

    /** Connection status for the UI. Carries no token material. */
    @Transactional(readOnly = true)
    public List<ConnectionStatus> listConnections() {
        Instant now = clock.instant();
        return repository.findAll().stream()
                .map(c -> new ConnectionStatus(
                        c.getProvider(),
                        c.getAccountLabel(),
                        c.getMarket(),
                        c.getConnectedAt(),
                        c.getAccessTokenExpiresAt(),
                        c.needsRefresh(now, REFRESH_SKEW) && !c.hasRefreshToken(),
                        grantedScopes(c.getScopes())))
                .toList();
    }

    /**
     * The scopes a service actually granted, as opposed to those requested.
     *
     * <p>The two differ more often than one would like — a service can hand back a
     * token with fewer scopes than asked for, and the only symptom is a 403 much
     * later, on one endpoint, with no explanation.
     */
    @Transactional(readOnly = true)
    public List<String> grantedScopesFor(ProviderId provider) {
        return repository.findById(provider)
                .map(ServiceConnection::getScopes)
                .map(ConnectionService::grantedScopes)
                .orElseGet(List::of);
    }

    private static List<String> grantedScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return List.of();
        }
        // OAuth scope is a space-delimited string.
        return List.of(scopes.trim().split("\\s+"));
    }

    private ServiceConnection refreshIfNeeded(ServiceConnection connection) {
        Instant now = clock.instant();
        if (!connection.needsRefresh(now, REFRESH_SKEW)) {
            return connection;
        }
        if (!connection.hasRefreshToken()) {
            throw new ProviderException(
                    connection.getProvider(),
                    ProviderException.Kind.UNAUTHORIZED,
                    connection.getProvider() + " token expired and there is no refresh token");
        }
        OAuthClient client = Optional.ofNullable(oauthClients.get(connection.getProvider()))
                .orElseThrow(() -> new ProviderException(
                        connection.getProvider(),
                        ProviderException.Kind.UNSUPPORTED,
                        "No OAuth client for " + connection.getProvider()));

        String refreshToken = cipher.decrypt(connection.getRefreshTokenEnc());
        TokenSet refreshed = client.refresh(refreshToken);
        connection.applyRefresh(
                cipher.encrypt(refreshed.accessToken()),
                cipher.encrypt(refreshed.refreshToken()),
                refreshed.expiresAt(),
                now);
        log.debug("Refreshed access token for {}", connection.getProvider());
        return repository.save(connection);
    }

    private ProviderCredentials toCredentials(ServiceConnection connection) {
        return new ProviderCredentials(
                connection.getProvider(),
                cipher.decrypt(connection.getAccessTokenEnc()),
                connection.getAccessTokenExpiresAt(),
                connection.getMarket());
    }

    /** What the UI shows about a connected service. */
    public record ConnectionStatus(
            ProviderId provider,
            String accountLabel,
            String market,
            Instant connectedAt,
            Instant expiresAt,
            boolean needsReconnect,
            /** What the service actually granted, which may be less than was asked for. */
            List<String> grantedScopes) {

        public ConnectionStatus {
            grantedScopes = grantedScopes == null ? List.of() : List.copyOf(grantedScopes);
        }
    }
}
