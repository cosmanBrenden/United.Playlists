package dev.unitedplaylists.provider.spotify;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.provider.ImportedPlaylist;
import dev.unitedplaylists.provider.PlaybackMethod;
import dev.unitedplaylists.provider.PlaybackTicket;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.support.Fixtures;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SpotifyProviderTest {

    private WireMockServer server;
    private SpotifyProvider provider;
    private final ProviderCredentials credentials = Fixtures.credentials(ProviderId.SPOTIFY);

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        provider = new SpotifyProvider(
                RestClient.builder(),
                new SpotifyProperties("test-client-id", server.baseUrl(), server.baseUrl(), true),
                Fixtures.configuredSettings(ProviderId.SPOTIFY));
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private void stubJson(String path, String body) {
        server.stubFor(get(urlPathEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        void mapsTracksAndTagsThemAsSpotify() {
            stubJson("/v1/search", """
                    {
                      "tracks": {
                        "items": [
                          {
                            "id": "4iV5W9uYEdYUVa79Axb7Rh",
                            "type": "track",
                            "name": "Never Gonna Give You Up",
                            "duration_ms": 213573,
                            "is_playable": true,
                            "artists": [{"name": "Rick Astley"}],
                            "album": {
                              "name": "Whenever You Need Somebody",
                              "images": [{"url": "https://img.example/large.jpg"}]
                            }
                          }
                        ]
                      }
                    }
                    """);

            List<Track> tracks = provider.search(credentials, "rick astley", 10);

            assertThat(tracks).singleElement().satisfies(track -> {
                assertThat(track.provider()).isEqualTo(ProviderId.SPOTIFY);
                assertThat(track.ref().providerTrackId()).isEqualTo("4iV5W9uYEdYUVa79Axb7Rh");
                assertThat(track.title()).isEqualTo("Never Gonna Give You Up");
                assertThat(track.artists()).containsExactly("Rick Astley");
                assertThat(track.album()).isEqualTo("Whenever You Need Somebody");
                assertThat(track.duration()).isEqualTo(Duration.ofMillis(213573));
                assertThat(track.artworkUrl()).isEqualTo("https://img.example/large.jpg");
                assertThat(track.playable()).isTrue();
            });
        }

        @Test
        void keepsEveryArtistOfACollaboration() {
            stubJson("/v1/search", """
                    {"tracks": {"items": [{
                      "id": "t1", "type": "track", "name": "Under Pressure", "duration_ms": 1000,
                      "artists": [{"name": "Queen"}, {"name": "David Bowie"}],
                      "album": {"name": "Hot Space", "images": []}
                    }]}}
                    """);

            List<Track> tracks = provider.search(credentials, "under pressure", 10);

            assertThat(tracks).singleElement().satisfies(t ->
                    assertThat(t.artists()).containsExactly("Queen", "David Bowie"));
        }

        @Test
        @DisplayName("sends the user's market so results are actually playable")
        void passesMarket() {
            stubJson("/v1/search", """
                    {"tracks": {"items": []}}
                    """);

            provider.search(credentials, "anything", 10);

            server.verify(com.github.tomakehurst.wiremock.client.WireMock
                    .getRequestedFor(urlPathEqualTo("/v1/search"))
                    .withQueryParam("market", equalTo("US"))
                    .withQueryParam("type", equalTo("track")));
        }

        @Test
        void sendsTheBearerToken() {
            stubJson("/v1/search", """
                    {"tracks": {"items": []}}
                    """);

            provider.search(credentials, "anything", 10);

            server.verify(com.github.tomakehurst.wiremock.client.WireMock
                    .getRequestedFor(urlPathEqualTo("/v1/search"))
                    .withHeader("Authorization", equalTo("Bearer test-access-token")));
        }

        @Test
        @DisplayName("a local file, which has no id, is skipped rather than half-mapped")
        void skipsTracksWithoutAnId() {
            stubJson("/v1/search", """
                    {"tracks": {"items": [
                      {"id": null, "type": "track", "name": "Local File", "artists": [], "album": {}},
                      {"id": "good", "type": "track", "name": "Real Track", "artists": [], "album": {}}
                    ]}}
                    """);

            List<Track> tracks = provider.search(credentials, "anything", 10);

            assertThat(tracks).extracting(Track::title).containsExactly("Real Track");
        }

        @Test
        void toleratesMissingOptionalFields() {
            stubJson("/v1/search", """
                    {"tracks": {"items": [{"id": "bare", "type": "track", "name": "Bare"}]}}
                    """);

            List<Track> tracks = provider.search(credentials, "anything", 10);

            assertThat(tracks).singleElement().satisfies(track -> {
                assertThat(track.artists()).isEmpty();
                assertThat(track.album()).isNull();
                assertThat(track.duration()).isNull();
                assertThat(track.artworkUrl()).isNull();
            });
        }

        @Test
        void emptyResultsAreNotAnError() {
            stubJson("/v1/search", """
                    {"tracks": {"items": []}}
                    """);

            assertThat(provider.search(credentials, "zzzznomatch", 10)).isEmpty();
        }
    }

    @Nested
    @DisplayName("error mapping")
    class Errors {

        private void stubStatus(int status, String body) {
            server.stubFor(get(urlPathEqualTo("/v1/search"))
                    .willReturn(aResponse()
                            .withStatus(status)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));
        }

        @Test
        @DisplayName("401 asks for a reconnect")
        void unauthorized() {
            stubStatus(401, "{\"error\":{\"status\":401,\"message\":\"The access token expired\"}}");

            assertThatThrownBy(() -> provider.search(credentials, "x", 10))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(e -> {
                        ProviderException pe = (ProviderException) e;
                        assertThat(pe.getKind()).isEqualTo(ProviderException.Kind.UNAUTHORIZED);
                        assertThat(pe.getProvider()).isEqualTo(ProviderId.SPOTIFY);
                        assertThat(pe.requiresReconnect()).isTrue();
                    });
        }

        @Test
        void rateLimited() {
            stubStatus(429, "{\"error\":{\"status\":429}}");

            assertThatThrownBy(() -> provider.search(credentials, "x", 10))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(e -> assertThat(((ProviderException) e).getKind())
                            .isEqualTo(ProviderException.Kind.RATE_LIMITED));
        }

        @Test
        void serverErrorIsUnavailableAndNotAReconnectPrompt() {
            stubStatus(503, "");

            assertThatThrownBy(() -> provider.search(credentials, "x", 10))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(e -> {
                        ProviderException pe = (ProviderException) e;
                        assertThat(pe.getKind()).isEqualTo(ProviderException.Kind.UNAVAILABLE);
                        assertThat(pe.requiresReconnect()).isFalse();
                    });
        }

        @Test
        void connectionRefusedBecomesUnavailable() {
            server.stop();

            assertThatThrownBy(() -> provider.search(credentials, "x", 10))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(e -> assertThat(((ProviderException) e).getKind())
                            .isEqualTo(ProviderException.Kind.UNAVAILABLE));
        }
    }

    @Nested
    @DisplayName("playlist import")
    class PlaylistImport {

        @Test
        void importsPlaylistsWithTheirTracks() {
            stubJson("/v1/me/playlists", """
                    {
                      "next": null,
                      "items": [{
                        "id": "pl1",
                        "name": "Road Trip",
                        "description": "for the car",
                        "images": [{"url": "https://img.example/pl.jpg"}]
                      }]
                    }
                    """);
            stubJson("/v1/playlists/pl1/items", """
                    {
                      "next": null,
                      "items": [
                        {"item": {"id": "t1", "type": "track", "name": "One",
                                   "duration_ms": 1000, "artists": [{"name": "A"}],
                                   "album": {"name": "Alb", "images": []}}},
                        {"item": {"id": "t2", "type": "track", "name": "Two",
                                   "duration_ms": 2000, "artists": [{"name": "B"}],
                                   "album": {"name": "Alb", "images": []}}}
                      ]
                    }
                    """);

            List<ImportedPlaylist> playlists = provider.fetchPlaylists(credentials);

            assertThat(playlists).singleElement().satisfies(playlist -> {
                assertThat(playlist.providerPlaylistId()).isEqualTo("pl1");
                assertThat(playlist.name()).isEqualTo("Road Trip");
                assertThat(playlist.description()).isEqualTo("for the car");
                assertThat(playlist.artworkUrl()).isEqualTo("https://img.example/pl.jpg");
                assertThat(playlist.tracks()).extracting(Track::title).containsExactly("One", "Two");
            });
        }

        @Test
        @DisplayName("removed songs arrive as a null track and must not crash the import")
        void skipsNullTracks() {
            stubJson("/v1/me/playlists", """
                    {"next": null, "items": [{"id": "pl1", "name": "Has Gaps", "images": []}]}
                    """);
            stubJson("/v1/playlists/pl1/items", """
                    {"next": null, "items": [
                      {"item": null},
                      {"item": {"id": "t1", "type": "track", "name": "Survivor",
                                 "artists": [], "album": {}}}
                    ]}
                    """);

            List<ImportedPlaylist> playlists = provider.fetchPlaylists(credentials);

            assertThat(playlists.get(0).tracks()).extracting(Track::title).containsExactly("Survivor");
        }

        @Test
        @DisplayName("podcast episodes in a playlist are not tracks")
        void skipsEpisodes() {
            stubJson("/v1/me/playlists", """
                    {"next": null, "items": [{"id": "pl1", "name": "Mixed Media", "images": []}]}
                    """);
            stubJson("/v1/playlists/pl1/items", """
                    {"next": null, "items": [
                      {"item": {"id": "ep1", "type": "episode", "name": "Some Podcast",
                                 "artists": [], "album": {}}},
                      {"item": {"id": "t1", "type": "track", "name": "Real Song",
                                 "artists": [], "album": {}}}
                    ]}
                    """);

            List<ImportedPlaylist> playlists = provider.fetchPlaylists(credentials);

            assertThat(playlists.get(0).tracks()).extracting(Track::title).containsExactly("Real Song");
        }

        @Test
        @DisplayName("follows pagination to the end")
        void followsNextPages() {
            server.stubFor(get(urlPathEqualTo("/v1/me/playlists"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {"next": null, "items": [{"id": "pl1", "name": "Long", "images": []}]}
                                    """)));
            server.stubFor(get(urlPathEqualTo("/v1/playlists/pl1/items"))
                    .withQueryParam("offset", equalTo("50"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {"next": null, "items": [
                                      {"item": {"id": "t2", "type": "track", "name": "Page Two",
                                                 "artists": [], "album": {}}}
                                    ]}
                                    """)));
            // Pagination walks by explicit offset, so page one is offset=0 rather than
            // an absent parameter. These two stubs must stay mutually exclusive, or the
            // provider is served page one forever.
            server.stubFor(get(urlPathEqualTo("/v1/playlists/pl1/items"))
                    .withQueryParam("offset", equalTo("0"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {"next": "https://api.spotify.com/v1/playlists/pl1/items?offset=50&limit=50",
                                     "items": [
                                       {"item": {"id": "t1", "type": "track", "name": "Page One",
                                                  "artists": [], "album": {}}}
                                     ]}
                                    """)));

            List<ImportedPlaylist> playlists = provider.fetchPlaylists(credentials);

            assertThat(playlists.get(0).tracks())
                    .extracting(Track::title)
                    .containsExactly("Page One", "Page Two");
        }

        @Test
        @DisplayName("reads the track from 'item', which is where /items puts it")
        void readsTheItemField() {
            stubJson("/v1/me/playlists", """
                    {"next": null, "items": [{"id": "pl1", "name": "Mine", "images": []}]}
                    """);
            stubJson("/v1/playlists/pl1/items", """
                    {"next": null, "items": [
                      {"added_at": "2026-01-01T00:00:00Z", "is_local": false,
                       "item": {"id": "t1", "type": "track", "name": "From Item Field",
                                "duration_ms": 1000, "artists": [{"name": "A"}],
                                "album": {"name": "Alb", "images": []}}}
                    ]}
                    """);

            List<ImportedPlaylist> playlists = provider.fetchPlaylists(credentials);

            assertThat(playlists.get(0).tracks()).extracting(Track::title)
                    .containsExactly("From Item Field");
        }

        @Test
        @DisplayName("still reads the deprecated 'track' field if that is all there is")
        void fallsBackToTheDeprecatedTrackField() {
            stubJson("/v1/me/playlists", """
                    {"next": null, "items": [{"id": "pl1", "name": "Mine", "images": []}]}
                    """);
            // Spotify still returns this alongside "item" for backwards compatibility.
            // Reading it as a fallback costs nothing and survives an odd response.
            stubJson("/v1/playlists/pl1/items", """
                    {"next": null, "items": [
                      {"track": {"id": "t1", "type": "track", "name": "From Track Field",
                                 "artists": [], "album": {}}}
                    ]}
                    """);

            List<ImportedPlaylist> playlists = provider.fetchPlaylists(credentials);

            assertThat(playlists.get(0).tracks()).extracting(Track::title)
                    .containsExactly("From Track Field");
        }

        /**
         * The bug this class of test exists for.
         *
         * <p>{@code /v1/me/playlists} returns playlists the user follows as well as
         * their own, but Spotify refuses to hand over the contents of anything they do
         * not own. Since practically every account follows something — Discover Weekly
         * arrives whether you asked for it or not — asking for every playlist's tracks
         * guaranteed a 403, and an uncaught one took the entire import with it. The
         * user's own playlists never had a chance to import.
         */
        @Test
        @DisplayName("a followed playlist does not take the whole import down")
        void oneUnreadablePlaylistDoesNotFailTheImport() {
            stubJson("/v1/me", """
                    {"id": "me", "display_name": "Me"}
                    """);
            stubJson("/v1/me/playlists", """
                    {"next": null, "items": [
                      {"id": "mine", "name": "My Playlist", "images": [],
                       "owner": {"id": "me"}, "collaborative": false},
                      {"id": "followed", "name": "Discover Weekly", "images": [],
                       "owner": {"id": "spotify"}, "collaborative": false}
                    ]}
                    """);
            stubJson("/v1/playlists/mine/items", """
                    {"next": null, "items": [
                      {"item": {"id": "t1", "type": "track", "name": "Mine",
                                "artists": [], "album": {}}}
                    ]}
                    """);
            // Spotify's answer for a playlist the user only follows.
            server.stubFor(get(urlPathEqualTo("/v1/playlists/followed/items"))
                    .willReturn(aResponse().withStatus(403)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":{\"status\":403,\"message\":\"Forbidden\"}}")));

            List<ImportedPlaylist> playlists = provider.fetchPlaylists(credentials);

            assertThat(playlists).hasSize(2);
            assertThat(playlists).filteredOn(p -> p.providerPlaylistId().equals("mine"))
                    .singleElement()
                    .satisfies(p -> {
                        assertThat(p.readable()).isTrue();
                        assertThat(p.tracks()).extracting(Track::title).containsExactly("Mine");
                    });
            // Still reported, so the user can be told why it did not come across.
            assertThat(playlists).filteredOn(p -> p.providerPlaylistId().equals("followed"))
                    .singleElement()
                    .satisfies(p -> {
                        assertThat(p.readable()).isFalse();
                        assertThat(p.tracks()).isEmpty();
                        assertThat(p.name()).isEqualTo("Discover Weekly");
                    });
        }

        @Test
        @DisplayName("a playlist owned by someone else is not even asked for")
        void doesNotRequestTracksForPlaylistsTheUserDoesNotOwn() {
            stubJson("/v1/me", """
                    {"id": "me"}
                    """);
            stubJson("/v1/me/playlists", """
                    {"next": null, "items": [
                      {"id": "followed", "name": "Someone Else's", "images": [],
                       "owner": {"id": "another-user"}, "collaborative": false}
                    ]}
                    """);

            provider.fetchPlaylists(credentials);

            // Spending a request to be told 403 helps nobody.
            server.verify(0, com.github.tomakehurst.wiremock.client.WireMock
                    .getRequestedFor(urlPathEqualTo("/v1/playlists/followed/items")));
        }

        @Test
        @DisplayName("a collaborative playlist is readable even though someone else owns it")
        void importsCollaborativePlaylists() {
            stubJson("/v1/me", """
                    {"id": "me"}
                    """);
            stubJson("/v1/me/playlists", """
                    {"next": null, "items": [
                      {"id": "collab", "name": "Shared", "images": [],
                       "owner": {"id": "a-friend"}, "collaborative": true}
                    ]}
                    """);
            stubJson("/v1/playlists/collab/items", """
                    {"next": null, "items": [
                      {"item": {"id": "t1", "type": "track", "name": "Shared Song",
                                "artists": [], "album": {}}}
                    ]}
                    """);

            List<ImportedPlaylist> playlists = provider.fetchPlaylists(credentials);

            assertThat(playlists).singleElement().satisfies(p -> {
                assertThat(p.readable()).isTrue();
                assertThat(p.tracks()).extracting(Track::title).containsExactly("Shared Song");
            });
        }

        @Test
        @DisplayName("if the profile cannot be read, playlists are attempted rather than skipped")
        void fallsBackToTryingWhenTheOwnerIsUnknown() {
            server.stubFor(get(urlPathEqualTo("/v1/me"))
                    .willReturn(aResponse().withStatus(403).withBody("{}")));
            stubJson("/v1/me/playlists", """
                    {"next": null, "items": [
                      {"id": "mine", "name": "My Playlist", "images": []}
                    ]}
                    """);
            stubJson("/v1/playlists/mine/items", """
                    {"next": null, "items": [
                      {"item": {"id": "t1", "type": "track", "name": "Mine",
                                "artists": [], "album": {}}}
                    ]}
                    """);

            List<ImportedPlaylist> playlists = provider.fetchPlaylists(credentials);

            // Guessing wrong costs one wasted request; refusing to guess costs the user
            // their whole library.
            assertThat(playlists).singleElement()
                    .satisfies(p -> assertThat(p.tracks()).hasSize(1));
        }

        @Test
        void noPlaylistsIsNotAnError() {
            stubJson("/v1/me/playlists", """
                    {"next": null, "items": []}
                    """);

            assertThat(provider.fetchPlaylists(credentials)).isEmpty();
        }

        @Test
        @DisplayName("a self-referential next page terminates instead of looping forever")
        void stopsAtThePageCap() {
            stubJson("/v1/me/playlists", """
                    {"next": null, "items": [{"id": "pl1", "name": "Cursed", "images": []}]}
                    """);
            // A next that points back at the same page. Real services should never do
            // this, but an import that hangs the app is a worse outcome than one that
            // returns a truncated playlist.
            stubJson("/v1/playlists/pl1/items", """
                    {"next": "https://api.spotify.com/v1/playlists/pl1/items?limit=50",
                     "items": [{"item": {"id": "t1", "type": "track", "name": "Loop",
                                          "artists": [], "album": {}}}]}
                    """);

            List<ImportedPlaylist> playlists = provider.fetchPlaylists(credentials);

            assertThat(playlists.get(0).tracks()).hasSize(100);
        }
    }

    @Nested
    @DisplayName("playback")
    class Playback {

        @Test
        @DisplayName("hands back an SDK instruction, never audio")
        void buildsAWebSdkTicket() {
            TrackRef ref = new TrackRef(ProviderId.SPOTIFY, "4iV5W9uYEdYUVa79Axb7Rh");

            PlaybackTicket ticket = provider.resolvePlayback(credentials, ref);

            assertThat(ticket.method()).isEqualTo(PlaybackMethod.SPOTIFY_WEB_SDK);
            assertThat(ticket.params()).containsEntry("uri", "spotify:track:4iV5W9uYEdYUVa79Axb7Rh");
            assertThat(ticket.ref()).isEqualTo(ref);
        }

        @Test
        void doesNotSpendQuotaResolvingPlayback() {
            provider.resolvePlayback(credentials, new TrackRef(ProviderId.SPOTIFY, "abc"));

            assertThat(server.getAllServeEvents()).isEmpty();
        }

        @Test
        void refusesAnotherServicesTrack() {
            TrackRef youtubeRef = new TrackRef(ProviderId.YOUTUBE, "dQw4w9WgXcQ");

            assertThatThrownBy(() -> provider.resolvePlayback(credentials, youtubeRef))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(e -> assertThat(((ProviderException) e).getKind())
                            .isEqualTo(ProviderException.Kind.UNSUPPORTED));
        }
    }

    @Nested
    @DisplayName("availability")
    class Availability {

        @Test
        void unavailableWithoutAClientId() {
            SpotifyProvider unconfigured = new SpotifyProvider(
                    RestClient.builder(),
                    new SpotifyProperties(null, null, null, true),
                    Fixtures.unconfiguredSettings(ProviderId.SPOTIFY));

            assertThat(unconfigured.isAvailable()).isFalse();
        }

        @Test
        void unavailableWhenDisabled() {
            SpotifyProvider disabled = new SpotifyProvider(
                    RestClient.builder(),
                    new SpotifyProperties("id", null, null, false),
                    Fixtures.configuredSettings(ProviderId.SPOTIFY));

            assertThat(disabled.isAvailable()).isFalse();
        }

        @Test
        void availableWhenConfigured() {
            assertThat(provider.isAvailable()).isTrue();
            assertThat(provider.id()).isEqualTo(ProviderId.SPOTIFY);
            assertThat(provider.displayName()).isEqualTo("Spotify");
        }
    }
}
