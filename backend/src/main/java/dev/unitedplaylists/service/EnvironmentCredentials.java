package dev.unitedplaylists.service;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.spotify.SpotifyProperties;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The startup-configured credentials, gathered in one place.
 *
 * <p>Only Spotify has any: it is the one authenticated provider. The scraper-backed
 * providers (YouTube, SoundCloud) reach their sites anonymously and need no client id
 * or secret, so they never appear here.
 *
 * <p>Exists so {@link ProviderSettingsService} does not depend on every provider's
 * properties class — which would mean editing it for each new authenticated service.
 */
@Component
public class EnvironmentCredentials {

    private final Map<ProviderId, String> clientIds = new EnumMap<>(ProviderId.class);
    private final Map<ProviderId, String> clientSecrets = new EnumMap<>(ProviderId.class);

    public EnvironmentCredentials(SpotifyProperties spotify) {
        putIfPresent(clientIds, ProviderId.SPOTIFY, spotify.clientId());
        // Spotify uses PKCE and has no secret; nothing to record here.
    }

    private static void putIfPresent(Map<ProviderId, String> target, ProviderId id, String value) {
        if (value != null && !value.isBlank()) {
            target.put(id, value);
        }
    }

    public Map<ProviderId, String> clientIds() {
        return Map.copyOf(clientIds);
    }

    public Map<ProviderId, String> clientSecrets() {
        return Map.copyOf(clientSecrets);
    }
}
