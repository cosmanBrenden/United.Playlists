package dev.unitedplaylists.provider.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.provider.HttpSupport;
import dev.unitedplaylists.provider.ImportedPlaylist;
import dev.unitedplaylists.provider.MusicProvider;
import dev.unitedplaylists.provider.PlaybackMethod;
import dev.unitedplaylists.provider.PlaybackTicket;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.service.ProviderSettingsService;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * Spotify Web API integration.
 *
 * <p>Reads playlists and searches the catalogue. Playback is not performed here
 * and cannot be: Spotify licenses playback only through its own SDKs, so
 * {@link #resolvePlayback} hands the client a track URI for the Web Playback SDK
 * and stops there.
 */
@Component
public class SpotifyProvider implements MusicProvider {

    private static final Logger log = LoggerFactory.getLogger(SpotifyProvider.class);

    /**
     * Spotify's ceiling for {@code /v1/playlists/{id}/items}.
     *
     * <p>Was 100 on the old {@code /tracks} endpoint; the replacement caps at 50.
     * Anything higher comes back as a 400 "Invalid limit".
     */
    private static final int TRACK_PAGE_SIZE = 50;

    /** Spotify's ceiling for {@code /v1/me/playlists}. */
    private static final int PLAYLIST_PAGE_SIZE = 50;

    /**
     * Spotify's ceiling for {@code /v1/search}, which the February 2026 API migration
     * cut from 50 to 10 (and the default from 20 to 5).
     *
     * <p>This is why searching returned 400 "Invalid limit" while everything else
     * worked: the app asked for 20, which had been legal for years.
     *
     * <p>The consequence is user-visible — Spotify contributes at most 10 results per
     * search. Going beyond that now means paginating with {@code offset}, at one
     * request per 10 results.
     */
    private static final int SEARCH_PAGE_SIZE = 10;

    /**
     * Stops a runaway pagination loop. A user with more than this many playlists is
     * vanishingly rare; a provider that pages forever because of a malformed
     * {@code next} is not.
     */
    private static final int MAX_PLAYLIST_PAGES = 40;

    private static final int MAX_TRACK_PAGES = 100;

    private final RestClient http;
    private final SpotifyProperties properties;
    private final ProviderSettingsService settings;

    public SpotifyProvider(
            RestClient.Builder builder,
            SpotifyProperties properties,
            ProviderSettingsService settings) {
        this.properties = properties;
        this.settings = settings;
        this.http = HttpSupport.withErrorHandling(builder.clone(), ProviderId.SPOTIFY)
                .baseUrl(properties.apiBaseUrl())
                .build();
    }

    @Override
    public ProviderId id() {
        return ProviderId.SPOTIFY;
    }

    @Override
    public String displayName() {
        return "Spotify";
    }

    @Override
    public boolean isAvailable() {
        // Asked fresh each time, so a client ID entered in the app works immediately
        // rather than at the next restart.
        return properties.enabled() && settings.isConfigured(ProviderId.SPOTIFY);
    }

    @Override
    public String unavailableReason() {
        if (isAvailable()) {
            return null;
        }
        return properties.enabled()
                ? "Add your Spotify client ID to connect. Create a free app at "
                        + "developer.spotify.com/dashboard and add this redirect URI to it: "
                        + "http://127.0.0.1:8420/callback"
                : "Spotify is switched off in this build's configuration.";
    }

    @Override
    public boolean requiresClientSecret() {
        // Spotify uses Authorization Code + PKCE, which has no client secret. Asking
        // for one would send people hunting for a field that does not exist.
        return false;
    }

    @Override
    public String consoleUrl() {
        return "https://developer.spotify.com/dashboard";
    }

    @Override
    public List<String> requiredScopes() {
        // What /v1/me/playlists needs. Without the first of these it answers 403 —
        // while /v1/search, which needs no scope at all, keeps working, so the service
        // looks half-broken rather than mis-authorised.
        //
        // "streaming" is deliberately absent: it requires Premium, and a free account
        // should still be able to browse and import.
        return List.of("playlist-read-private", "playlist-read-collaborative");
    }

    @Override
    public List<String> setupInstructions(String redirectUri) {
        return List.of(
                "Spotify Premium is required. Since March 2026, apps in Development Mode only "
                        + "work if the account that owns the app has an active Premium "
                        + "subscription — on a free account every request comes back 403, with "
                        + "nothing to say why.",
                "Open the Spotify Developer Dashboard and log in with your normal Spotify account.",
                "Click \"Create app\". The name and description are yours to choose; they are "
                        + "only shown to you.",
                "Add this exact Redirect URI: " + redirectUri,
                "Tick \"Web API\" and \"Web Playback SDK\" when asked which APIs you plan to use. "
                        + "Leaving Web API unticked makes every request fail with a 403.",
                "Open the app's \"User Management\" tab and add your own Spotify account name "
                        + "and email. Development Mode apps only serve the users listed there.",
                "Open Settings and copy the Client ID.",
                "Paste it below. There is no client secret to copy — Spotify uses PKCE, "
                        + "which does not need one.");
    }

    @Override
    public List<ImportedPlaylist> fetchPlaylists(ProviderCredentials credentials) {
        try {
            List<ImportedPlaylist> out = new ArrayList<>();
            String currentUserId = fetchCurrentUserId(credentials);

            for (int page = 0; page < MAX_PLAYLIST_PAGES; page++) {
                int offset = page * PLAYLIST_PAGE_SIZE;
                JsonNode body = get(
                        uri -> uri.path("/v1/me/playlists")
                                .queryParam("limit", PLAYLIST_PAGE_SIZE)
                                .queryParam("offset", offset)
                                .build(),
                        credentials);

                for (JsonNode item : body.path("items")) {
                    if (item.isNull()) {
                        continue;
                    }
                    String playlistId = item.path("id").asText(null);
                    if (playlistId == null) {
                        continue;
                    }
                    out.add(toImportedPlaylist(credentials, item, playlistId, currentUserId));
                }
                if (!hasMore(body)) {
                    break;
                }
            }
            return List.copyOf(out);
        } catch (RestClientException e) {
            throw HttpSupport.transportFailure(ProviderId.SPOTIFY, e);
        }
    }

    /**
     * Builds one playlist, fetching its tracks only if Spotify will allow it.
     *
     * <p>{@code /v1/me/playlists} lists playlists the user <em>follows</em> as well as
     * their own, but {@code /v1/playlists/{id}/items} answers 403 for anything they
     * neither own nor collaborate on. Since almost every account follows something —
     * Discover Weekly, Release Radar, a friend's playlist — asking for the contents of
     * everything guarantees a 403, and it was taking the whole import down with it.
     */
    private ImportedPlaylist toImportedPlaylist(
            ProviderCredentials credentials,
            JsonNode item,
            String playlistId,
            String currentUserId) {

        String name = item.path("name").asText("Untitled");
        String description = emptyToNull(item.path("description").asText(null));
        String artwork = firstImageUrl(item.path("images"));

        String ownerId = item.path("owner").path("id").asText(null);
        boolean collaborative = item.path("collaborative").asBoolean(false);
        // When the owner cannot be established — Spotify omitted it, or /v1/me failed —
        // try anyway rather than skip. The catch below turns a wrong guess into a
        // skipped playlist instead of a failed import.
        boolean mightBeReadable = currentUserId == null
                || ownerId == null
                || currentUserId.equals(ownerId)
                || collaborative;

        if (!mightBeReadable) {
            log.debug("Skipping Spotify playlist {} ({}): owned by {}, not readable",
                    playlistId, name, ownerId);
            return ImportedPlaylist.unreadable(playlistId, name, description, artwork);
        }

        try {
            return ImportedPlaylist.readable(
                    playlistId, name, description, artwork,
                    fetchPlaylistTracks(credentials, playlistId));
        } catch (ProviderException e) {
            if (e.getKind() == ProviderException.Kind.FORBIDDEN
                    || e.getKind() == ProviderException.Kind.NOT_FOUND) {
                // One playlist the user cannot read must not cost them the other fifty.
                log.info("Spotify would not return the contents of playlist {} ({}): {}",
                        playlistId, name, e.getMessage());
                return ImportedPlaylist.unreadable(playlistId, name, description, artwork);
            }
            throw e;
        }
    }

    /**
     * The signed-in user's Spotify id, or null if it cannot be determined.
     *
     * <p>Used only to decide which playlists are worth asking for. A failure here is
     * not worth failing the import over: without it every playlist is simply attempted,
     * which is what happened before and works for owned playlists regardless.
     */
    private String fetchCurrentUserId(ProviderCredentials credentials) {
        try {
            JsonNode me = get(uri -> uri.path("/v1/me").build(), credentials);
            return emptyToNull(me.path("id").asText(null));
        } catch (ProviderException | RestClientException e) {
            log.info("Could not read the Spotify profile; importing without an ownership "
                    + "filter: {}", e.getMessage());
            return null;
        }
    }

    private List<Track> fetchPlaylistTracks(ProviderCredentials credentials, String playlistId) {
        List<Track> tracks = new ArrayList<>();

        for (int page = 0; page < MAX_TRACK_PAGES; page++) {
            int offset = page * TRACK_PAGE_SIZE;
            JsonNode body = get(
                    // "/tracks" was removed in the February 2026 migration and replaced
                    // by "/items". The old path is gone, not merely deprecated.
                    uri -> uri.path("/v1/playlists/{id}/items")
                            .queryParam("limit", TRACK_PAGE_SIZE)
                            .queryParam("offset", offset)
                            .queryParam("additional_types", "track")
                            .build(playlistId),
                    credentials);

            for (JsonNode entry : body.path("items")) {
                JsonNode trackNode = itemOf(entry);
                // Spotify returns a null item for removed songs, and podcast episodes
                // arrive here with type "episode". Both must be skipped rather than
                // parsed into a broken Track.
                if (trackNode.isMissingNode() || trackNode.isNull()) {
                    continue;
                }
                if (!"track".equals(trackNode.path("type").asText("track"))) {
                    continue;
                }
                Track track = toTrack(trackNode);
                if (track != null) {
                    tracks.add(track);
                }
            }
            if (!hasMore(body)) {
                break;
            }
        }
        return tracks;
    }

    /**
     * The track inside a playlist entry.
     *
     * <p>The {@code /items} endpoint nests it under {@code item}. It also still returns
     * the old {@code track} field for backwards compatibility, which is read as a
     * fallback — cheap insurance, since that field is deprecated and will presumably
     * disappear in a later migration.
     */
    private JsonNode itemOf(JsonNode entry) {
        JsonNode item = entry.path("item");
        return item.isMissingNode() || item.isNull() ? entry.path("track") : item;
    }

    @Override
    public List<Track> search(ProviderCredentials credentials, String query, int limit) {
        try {
            int pageSize = Math.min(limit, SEARCH_PAGE_SIZE);
            JsonNode body = get(
                    uri -> {
                        uri.path("/v1/search")
                                .queryParam("q", query)
                                .queryParam("type", "track")
                                .queryParam("limit", pageSize);
                        // Without a market, Spotify may return tracks the user cannot play.
                        if (credentials.userMarket() != null) {
                            uri.queryParam("market", credentials.userMarket());
                        }
                        return uri.build();
                    },
                    credentials);

            List<Track> tracks = new ArrayList<>();
            for (JsonNode item : body.path("tracks").path("items")) {
                Track track = toTrack(item);
                if (track != null) {
                    tracks.add(track);
                }
            }
            return List.copyOf(tracks);
        } catch (RestClientException e) {
            throw HttpSupport.transportFailure(ProviderId.SPOTIFY, e);
        }
    }

    @Override
    public PlaybackTicket resolvePlayback(ProviderCredentials credentials, TrackRef ref) {
        if (ref.provider() != ProviderId.SPOTIFY) {
            throw new ProviderException(
                    ProviderId.SPOTIFY,
                    ProviderException.Kind.UNSUPPORTED,
                    "Not a Spotify track: " + ref.toKey());
        }
        // No API call: the SDK takes the URI directly, and it is derivable from the
        // id. Calling /v1/tracks here would spend quota to learn nothing.
        return new PlaybackTicket(
                ref,
                PlaybackMethod.SPOTIFY_WEB_SDK,
                Map.of(
                        "uri", "spotify:track:" + ref.providerTrackId(),
                        "trackId", ref.providerTrackId()));
    }

    /**
     * Issues a GET, letting {@link RestClient} build and encode the URI.
     *
     * <p>The URI is built here rather than handed over as a string on purpose. Passing
     * a pre-encoded string means RestClient encodes it a second time — a space becomes
     * {@code %20} and then {@code %2520}, so Spotify searched for the literal text
     * "rick%20astley" and found nothing. Building through the callback encodes values
     * exactly once.
     */
    private JsonNode get(
            Function<UriBuilder, URI> uriFunction, ProviderCredentials credentials) {
        return http.get()
                .uri(uriFunction)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.accessToken())
                .retrieve()
                .body(JsonNode.class);
    }

    /**
     * Whether Spotify says another page exists.
     *
     * <p>Pagination walks by {@code offset} rather than following the absolute URL in
     * {@code next}, so every request is built the same way and none of them needs
     * re-encoding. {@code next} is used only as the stop signal.
     */
    private boolean hasMore(JsonNode body) {
        String next = body.path("next").asText(null);
        return next != null && !next.isBlank() && !"null".equals(next);
    }

    /** Maps a Spotify track object; null when it is too malformed to use. */
    private Track toTrack(JsonNode node) {
        String id = node.path("id").asText(null);
        if (id == null || id.isBlank()) {
            // Local files a user added to a Spotify playlist have no id and are not
            // playable through the API.
            return null;
        }
        List<String> artists = new ArrayList<>();
        for (JsonNode artist : node.path("artists")) {
            String name = artist.path("name").asText(null);
            if (name != null) {
                artists.add(name);
            }
        }
        long durationMs = node.path("duration_ms").asLong(0);
        JsonNode album = node.path("album");

        return new Track(
                new TrackRef(ProviderId.SPOTIFY, id),
                node.path("name").asText("Unknown"),
                artists,
                emptyToNull(album.path("name").asText(null)),
                durationMs > 0 ? Duration.ofMillis(durationMs) : null,
                firstImageUrl(album.path("images")),
                node.path("is_playable").asBoolean(true));
    }

    private String firstImageUrl(JsonNode images) {
        for (JsonNode image : images) {
            String url = image.path("url").asText(null);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
