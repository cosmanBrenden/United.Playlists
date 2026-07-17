package dev.unitedplaylists.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.apple.AppleMusicProvider;
import dev.unitedplaylists.provider.spotify.SpotifyProperties;
import dev.unitedplaylists.provider.spotify.SpotifyProvider;
import dev.unitedplaylists.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Every unavailable service must explain itself, in its own terms.
 *
 * <p>These exist because the UI once hard-coded a single explanation for every
 * unavailable provider, so an unconfigured Spotify told the user to buy an Apple
 * Developer membership. The reasons are different problems with different fixes, and
 * the provider is the only thing that knows which applies.
 *
 * <p>Only Spotify and Apple Music remain here. YouTube and SoundCloud are scraper
 * providers now — always available, never needing setup, so they have no unavailable
 * reason to give.
 */
class UnavailableReasonTest {

    private SpotifyProvider spotify(String clientId, boolean enabled) {
        return new SpotifyProvider(
                RestClient.builder(),
                new SpotifyProperties(clientId, null, null, enabled),
                Fixtures.settingsFor(ProviderId.SPOTIFY, clientId, null));
    }

    @Test
    @DisplayName("an available provider gives no reason")
    void availableProvidersHaveNoReason() {
        assertThat(spotify("a-client-id", true).isAvailable()).isTrue();
        assertThat(spotify("a-client-id", true).unavailableReason()).isNull();
    }

    @Test
    @DisplayName("unconfigured Spotify points at the Spotify dashboard, not Apple")
    void spotifyExplainsItsMissingClientId() {
        String reason = spotify(null, true).unavailableReason();

        assertThat(reason).containsIgnoringCase("client ID");
        assertThat(reason).contains("developer.spotify.com");
        // The bug this test exists for.
        assertThat(reason).doesNotContainIgnoringCase("apple");
        // Credentials are entered in the app now; sending people to set a shell variable
        // and restart would be the thing this replaced.
        assertThat(reason).doesNotContain("UP_SPOTIFY_CLIENT_ID");
        assertThat(reason).doesNotContainIgnoringCase("restart");
    }

    @Test
    @DisplayName("Apple Music explains the membership requirement")
    void appleMusicExplainsTheMembership() {
        String reason = new AppleMusicProvider().unavailableReason();

        assertThat(reason).containsIgnoringCase("Apple Developer membership");
        // Nothing the user can set will fix this one, so it must not read like config.
        assertThat(reason).doesNotContain("UP_");
    }

    @Test
    @DisplayName("a disabled Spotify says so, rather than blaming missing config")
    void disabledProviderSaysSoPlainly() {
        assertThat(spotify("a-client-id", false).unavailableReason())
                .containsIgnoringCase("switched off");
    }

    @Test
    @DisplayName("Spotify and Apple give different explanations")
    void reasonsAreDistinctPerService() {
        assertThat(spotify(null, true).unavailableReason())
                .isNotEqualTo(new AppleMusicProvider().unavailableReason());
    }

    @Test
    @DisplayName("every reason names the service it is about")
    void reasonsIdentifyTheirService() {
        assertThat(spotify(null, true).unavailableReason()).containsIgnoringCase("Spotify");
        assertThat(new AppleMusicProvider().unavailableReason()).containsIgnoringCase("Apple Music");
    }

    @Test
    @DisplayName("scraper providers are always available and give no reason")
    void scraperProvidersAreAlwaysAvailable() {
        var extraction = new dev.unitedplaylists.provider.newpipe.NewPipeExtractionService();
        var youtube = new dev.unitedplaylists.provider.youtube.YoutubeScraperProvider(extraction, true);
        var soundcloud = new dev.unitedplaylists.provider.soundcloud.SoundCloudProvider(extraction, true);

        assertThat(youtube.isAvailable()).isTrue();
        assertThat(youtube.unavailableReason()).isNull();
        assertThat(youtube.requiresAuthentication()).isFalse();
        assertThat(soundcloud.isAvailable()).isTrue();
        assertThat(soundcloud.unavailableReason()).isNull();
        assertThat(soundcloud.requiresAuthentication()).isFalse();
    }
}
