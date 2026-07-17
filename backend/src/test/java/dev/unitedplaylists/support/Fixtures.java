package dev.unitedplaylists.support;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.service.ProviderSettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Test data builders, kept terse so tests read as intent rather than setup. */
public final class Fixtures {

    private Fixtures() {
    }

    public static Track spotifyTrack(String id, String title) {
        return track(ProviderId.SPOTIFY, id, title);
    }

    public static Track youtubeTrack(String id, String title) {
        return track(ProviderId.YOUTUBE, id, title);
    }

    public static Track track(ProviderId provider, String id, String title) {
        return new Track(
                new TrackRef(provider, id),
                title,
                List.of("Test Artist"),
                "Test Album",
                Duration.ofMinutes(3),
                "https://example.invalid/art.jpg",
                true);
    }

    public static Track trackWithArtists(
            ProviderId provider, String id, String title, String... artists) {
        return new Track(
                new TrackRef(provider, id),
                title,
                List.of(artists),
                "Test Album",
                Duration.ofMinutes(3),
                null,
                true);
    }

    public static ProviderCredentials credentials(ProviderId provider) {
        return new ProviderCredentials(
                provider, "test-access-token", Instant.parse("2030-01-01T00:00:00Z"), "US");
    }

    /**
     * A settings service that reports the given credentials, without a database.
     *
     * <p>Providers resolve their client id per call so that saving one in the app
     * takes effect immediately; that makes the settings service a dependency of every
     * provider, and this keeps provider tests from needing a Spring context to say
     * "pretend a client id is configured".
     *
     * @param clientId     null to simulate a service that has not been set up
     * @param clientSecret null for services that need none, like Spotify
     */
    public static ProviderSettingsService settingsFor(
            ProviderId provider, String clientId, String clientSecret) {
        ProviderSettingsService settings = mock(ProviderSettingsService.class);
        lenient().when(settings.clientId(provider))
                .thenReturn(Optional.ofNullable(clientId).filter(id -> !id.isBlank()));
        lenient().when(settings.clientSecret(provider))
                .thenReturn(Optional.ofNullable(clientSecret).filter(s -> !s.isBlank()));
        lenient().when(settings.isConfigured(provider))
                .thenReturn(clientId != null && !clientId.isBlank());
        lenient().when(settings.sourceOf(provider))
                .thenReturn(clientId == null || clientId.isBlank()
                        ? ProviderSettingsService.Source.NONE
                        : ProviderSettingsService.Source.APP);
        return settings;
    }

    /** A settings service reporting a configured client id and no secret. */
    public static ProviderSettingsService configuredSettings(ProviderId provider) {
        return settingsFor(provider, "test-client-id", null);
    }

    /** A settings service reporting nothing configured. */
    public static ProviderSettingsService unconfiguredSettings(ProviderId provider) {
        return settingsFor(provider, null, null);
    }
}
