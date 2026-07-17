package dev.unitedplaylists.provider.spotify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scopes Spotify is asked for.
 *
 * <p>These are load-bearing in a way that is easy to miss. The Web Playback SDK
 * validates the scopes on the token itself, so a scope can be essential even when
 * nothing in this codebase reads the data it guards — which is exactly how
 * {@code user-read-email} and {@code user-read-private} came to be deleted here as
 * "unused" and broke playback with a bare "Invalid token scopes".
 *
 * <p>Worse, the damage outlives the mistake: existing users keep a token with the
 * old scopes, and refreshing does not upgrade it. Every one of them has to sign in
 * again. That makes this list expensive to get wrong, and worth pinning.
 */
class SpotifyScopesTest {

    private List<String> scopes() {
        return List.of(SpotifyProperties.SCOPES.split(" "));
    }

    @Test
    @DisplayName("the Web Playback SDK's three required scopes are all present")
    void includesEveryScopeThePlaybackSdkDemands() {
        // All three, or the SDK refuses to start. Not negotiable, and not inferable
        // from which fields the app happens to read.
        assertThat(scopes()).contains("streaming", "user-read-email", "user-read-private");
    }

    @Test
    @DisplayName("the scopes needed to read the user's library are present")
    void includesLibraryScopes() {
        assertThat(scopes()).contains("playlist-read-private", "playlist-read-collaborative");
    }

    @Test
    @DisplayName("scopes are space-delimited, as OAuth requires")
    void isSpaceDelimited() {
        // Commas are the classic mistake, and they surface as the same
        // "Invalid token scopes" error, sending you hunting for a missing scope that
        // is right there.
        assertThat(SpotifyProperties.SCOPES).doesNotContain(",");
        assertThat(SpotifyProperties.SCOPES).doesNotContain("  ");
        assertThat(SpotifyProperties.SCOPES.trim()).isEqualTo(SpotifyProperties.SCOPES);
    }

    @Test
    @DisplayName("nothing that writes to the user's account is requested")
    void requestsNoWriteAccess() {
        // The app never writes back to a service; asking for permission it will not
        // use is both a lie to the user and a bigger blast radius if the token leaks.
        assertThat(SpotifyProperties.SCOPES).doesNotContain("modify");
        assertThat(SpotifyProperties.SCOPES).doesNotContain("upload");
        assertThat(SpotifyProperties.SCOPES).doesNotContain("follow-modify");
    }

    @Test
    void hasNoDuplicates() {
        assertThat(scopes()).doesNotHaveDuplicates();
    }
}
