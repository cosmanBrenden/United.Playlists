package dev.unitedplaylists.service;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.ProviderSetting;
import dev.unitedplaylists.repository.ProviderSettingRepository;
import dev.unitedplaylists.security.TokenCipher;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves each service's API credentials.
 *
 * <p>Credentials come from one of two places, in order:
 *
 * <ol>
 *   <li>What the user entered in the app, stored in the database. This wins.
 *   <li>An environment variable baked in at startup, which is what a developer or a
 *       packaged build with bundled credentials would use.
 * </ol>
 *
 * <p>Keeping the environment as a fallback rather than deleting it matters: it is
 * how the test suite, CI, and anyone running from source configure things, and a
 * packaged release can ship its own registration so users never see this screen at
 * all. The app-entered value overrides it so a user is never stuck with a build's
 * defaults.
 *
 * <p>Lookups hit the database on every call rather than being cached, which is what
 * makes saving a client ID take effect immediately instead of at the next restart.
 * The cost is a primary-key read against a local H2 file, on a path that already
 * makes a network request to a streaming service.
 */
@Service
public class ProviderSettingsService {

    private static final Logger log = LoggerFactory.getLogger(ProviderSettingsService.class);

    private final ProviderSettingRepository repository;
    private final TokenCipher cipher;
    private final Clock clock;
    /** Startup-configured fallbacks, by provider. */
    private final Map<ProviderId, String> environmentClientIds;
    private final Map<ProviderId, String> environmentClientSecrets;

    public ProviderSettingsService(
            ProviderSettingRepository repository,
            TokenCipher cipher,
            Clock clock,
            EnvironmentCredentials environment) {
        this.repository = repository;
        this.cipher = cipher;
        this.clock = clock;
        this.environmentClientIds = environment.clientIds();
        this.environmentClientSecrets = environment.clientSecrets();
    }

    /** Where a provider's credentials came from. */
    public enum Source {
        /** Entered by the user in the app. */
        APP,
        /** Supplied by configuration or an environment variable at startup. */
        ENVIRONMENT,
        /** Not configured at all. */
        NONE
    }

    /** Empty when the service has no client id from either source. */
    @Transactional(readOnly = true)
    public Optional<String> clientId(ProviderId provider) {
        return repository.findById(provider)
                .map(ProviderSetting::getClientId)
                .filter(id -> !id.isBlank())
                .or(() -> Optional.ofNullable(environmentClientIds.get(provider))
                        .filter(id -> !id.isBlank()));
    }

    /**
     * Empty when the service has no client secret, which is normal: Spotify's PKCE
     * flow does not use one.
     */
    @Transactional(readOnly = true)
    public Optional<String> clientSecret(ProviderId provider) {
        Optional<ProviderSetting> stored = repository.findById(provider);
        if (stored.isPresent()) {
            ProviderSetting setting = stored.get();
            if (setting.hasClientSecret()) {
                try {
                    return Optional.ofNullable(cipher.decrypt(setting.getClientSecretEnc()));
                } catch (TokenCipher.TokenDecryptionException e) {
                    // Unreadable secret must not take the whole service down; the user
                    // can re-enter it. Falling through to the environment is the best
                    // remaining chance of working.
                    log.warn("Stored client secret for {} could not be decrypted: {}",
                            provider, e.getMessage());
                }
            }
            // A stored setting with no secret is a deliberate "no secret", not a gap to
            // fill from the environment.
            if (!setting.hasClientSecret()) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(environmentClientSecrets.get(provider))
                .filter(secret -> !secret.isBlank());
    }

    @Transactional(readOnly = true)
    public Source sourceOf(ProviderId provider) {
        if (repository.existsById(provider)) {
            return Source.APP;
        }
        String fromEnvironment = environmentClientIds.get(provider);
        return fromEnvironment != null && !fromEnvironment.isBlank()
                ? Source.ENVIRONMENT
                : Source.NONE;
    }

    @Transactional(readOnly = true)
    public boolean isConfigured(ProviderId provider) {
        return clientId(provider).isPresent();
    }

    /**
     * Saves credentials entered in the app, replacing anything already stored.
     *
     * @param clientSecret null or blank for services that need none
     * @throws IllegalArgumentException if the client id is blank
     */
    @Transactional
    public void save(ProviderId provider, String clientId, String clientSecret) {
        String trimmedSecret = clientSecret == null || clientSecret.isBlank()
                ? null
                : clientSecret.trim();
        String encrypted = trimmedSecret == null ? null : cipher.encrypt(trimmedSecret);

        repository.findById(provider)
                .ifPresentOrElse(
                        existing -> existing.update(clientId, encrypted, clock.instant()),
                        () -> repository.save(new ProviderSetting(
                                provider, clientId, encrypted, clock.instant())));
        log.info("Saved app credentials for {}", provider);
    }

    /** Forgets app-entered credentials, falling back to the environment if it has any. */
    @Transactional
    public void clear(ProviderId provider) {
        repository.deleteById(provider);
        log.info("Cleared app credentials for {}", provider);
    }
}
