package dev.unitedplaylists.web.dto;

import dev.unitedplaylists.domain.Playlist;
import dev.unitedplaylists.domain.PlaylistEntry;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.provider.PlaybackMethod;
import dev.unitedplaylists.provider.PlaybackTicket;
import dev.unitedplaylists.service.SearchService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API payloads.
 *
 * <p>Separate from the domain because the two change for different reasons, and
 * because serialising entities directly is how token fields and lazy collections
 * end up on the wire by accident.
 */
public final class Dtos {

    private Dtos() {
    }

    @Schema(description = "A track, always labelled with the service it came from")
    public record TrackDto(
            @Schema(description = "Stable key, e.g. SPOTIFY:4iV5W9uYEdYUVa79Axb7Rh") String key,
            ProviderId provider,
            @Schema(description = "Service's own id for this track") String providerTrackId,
            String title,
            List<String> artists,
            String artistLine,
            String album,
            Long durationMs,
            String artworkUrl,
            boolean playable) {

        public static TrackDto from(Track track) {
            return new TrackDto(
                    track.ref().toKey(),
                    track.provider(),
                    track.ref().providerTrackId(),
                    track.title(),
                    track.artists(),
                    track.artistLine(),
                    track.album(),
                    track.duration() == null ? null : track.duration().toMillis(),
                    track.artworkUrl(),
                    track.playable());
        }
    }

    public record PlaylistEntryDto(UUID id, int position, TrackDto track, Instant addedAt) {

        public static PlaylistEntryDto from(PlaylistEntry entry) {
            return new PlaylistEntryDto(
                    entry.getId(),
                    entry.getPosition(),
                    TrackDto.from(entry.toTrack()),
                    entry.getAddedAt());
        }
    }

    public record PlaylistOriginDto(ProviderId provider, String originPlaylistId, Instant importedAt) {
    }

    @Schema(description = "A local playlist; may mix tracks from several services")
    public record PlaylistDto(
            UUID id,
            String name,
            String description,
            @Schema(description = "Where it was imported from, or null if made here")
            PlaylistOriginDto origin,
            @Schema(description = "Distinct services this playlist draws on")
            List<ProviderId> providersUsed,
            int trackCount,
            List<PlaylistEntryDto> entries,
            Instant createdAt,
            Instant updatedAt) {

        public static PlaylistDto from(Playlist playlist) {
            return new PlaylistDto(
                    playlist.getId(),
                    playlist.getName(),
                    playlist.getDescription(),
                    playlist.getOrigin()
                            .map(o -> new PlaylistOriginDto(
                                    o.getProvider(), o.getOriginPlaylistId(), o.getImportedAt()))
                            .orElse(null),
                    playlist.providersUsed(),
                    playlist.size(),
                    playlist.getEntries().stream().map(PlaylistEntryDto::from).toList(),
                    playlist.getCreatedAt(),
                    playlist.getUpdatedAt());
        }

        /** Without entries, for list views. */
        public static PlaylistDto summary(Playlist playlist) {
            PlaylistDto full = from(playlist);
            return new PlaylistDto(
                    full.id(), full.name(), full.description(), full.origin(),
                    full.providersUsed(), full.trackCount(), List.of(),
                    full.createdAt(), full.updatedAt());
        }
    }

    public record CreatePlaylistRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 2048) String description) {
    }

    public record UpdatePlaylistRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 2048) String description) {
    }

    public record AddTrackRequest(
            @Schema(description = "Track key from a search result, e.g. YOUTUBE:dQw4w9WgXcQ")
            @NotBlank String trackKey,
            @NotBlank @Size(max = 512) String title,
            List<String> artists,
            @Size(max = 512) String album,
            Long durationMs,
            @Size(max = 1024) String artworkUrl) {
    }

    public record MoveTrackRequest(@PositiveOrZero int from, @PositiveOrZero int to) {
    }

    @Schema(description = "Ask to move some or all of a playlist's tracks onto one service")
    public record MigrateRequest(
            @Schema(description = "The service to move the tracks onto")
            @jakarta.validation.constraints.NotNull ProviderId targetProvider,
            @Schema(description = "0-based positions to migrate. Empty or omitted means the "
                    + "whole playlist. Positions already on the target service are skipped.")
            List<@PositiveOrZero Integer> positions) {

        public MigrateRequest {
            positions = positions == null ? List.of() : List.copyOf(positions);
        }
    }

    @Schema(description = "Replace one track in place with the same song on another service")
    public record ReplaceTrackRequest(
            @Schema(description = "Track key to put in the slot, e.g. YOUTUBE:dQw4w9WgXcQ")
            @NotBlank String trackKey,
            @NotBlank @Size(max = 512) String title,
            List<String> artists,
            @Size(max = 512) String album,
            Long durationMs,
            @Size(max = 1024) String artworkUrl,
            @Schema(description = "The key the caller believes is currently at that position. "
                    + "When given, the replace is rejected if the slot no longer holds it — "
                    + "a guard against replacing the wrong track after the playlist changed.")
            String expectedKey) {
    }

    @Schema(description = "A track swapped automatically during migration")
    public record ReplacementDto(int position, TrackDto from, TrackDto to) {

        public static ReplacementDto from(dev.unitedplaylists.service.MigrationService.Replacement r) {
            return new ReplacementDto(r.position(), TrackDto.from(r.from()), TrackDto.from(r.to()));
        }
    }

    @Schema(description = "A track migration could not confidently match, with candidates to pick from")
    public record UnresolvedMatchDto(
            int position,
            TrackDto source,
            @Schema(description = "Candidate matches from every service, best first")
            List<TrackDto> candidates) {

        public static UnresolvedMatchDto from(dev.unitedplaylists.service.MigrationService.Unresolved u) {
            return new UnresolvedMatchDto(
                    u.position(),
                    TrackDto.from(u.source()),
                    u.candidates().stream().map(TrackDto::from).toList());
        }
    }

    @Schema(description = "The outcome of a migration job")
    public record MigrationResultDto(
            ProviderId target,
            @Schema(description = "The playlist after the automatic replacements")
            PlaylistDto playlist,
            @Schema(description = "Tracks replaced automatically")
            List<ReplacementDto> replaced,
            @Schema(description = "Tracks that need a manual choice")
            List<UnresolvedMatchDto> unresolved,
            @Schema(description = "Selected tracks already on the target service, left untouched")
            int alreadyOnTarget,
            @Schema(description = "Services that failed to answer; results may be incomplete")
            List<ProviderFailureDto> failures) {

        public static MigrationResultDto from(
                dev.unitedplaylists.service.MigrationService.MigrationResult result) {
            return new MigrationResultDto(
                    result.target(),
                    PlaylistDto.from(result.playlist()),
                    result.replaced().stream().map(ReplacementDto::from).toList(),
                    result.unresolved().stream().map(UnresolvedMatchDto::from).toList(),
                    result.alreadyOnTarget(),
                    result.failures().stream()
                            .map(f -> new ProviderFailureDto(
                                    f.provider(), f.kind().name(), f.message(), f.requiresReconnect()))
                            .toList());
        }
    }

    @Schema(description = "Merged results from every connected service")
    public record SearchResponse(
            String query,
            List<TrackDto> results,
            @Schema(description = "Results grouped by service")
            Map<ProviderId, List<TrackDto>> byProvider,
            @Schema(description = "Services that failed; results are incomplete when non-empty")
            List<ProviderFailureDto> failures,
            @Schema(description = "True when at least one service failed to answer")
            boolean partial) {

        public static SearchResponse from(SearchService.SearchResults results) {
            return new SearchResponse(
                    results.query(),
                    results.tracks().stream().map(TrackDto::from).toList(),
                    results.byProvider().entrySet().stream().collect(
                            java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> e.getValue().stream().map(TrackDto::from).toList(),
                                    (a, b) -> a,
                                    java.util.LinkedHashMap::new)),
                    results.failures().stream()
                            .map(f -> new ProviderFailureDto(
                                    f.provider(), f.kind().name(), f.message(), f.requiresReconnect()))
                            .toList(),
                    results.isPartial());
        }
    }

    public record ProviderFailureDto(
            ProviderId provider, String kind, String message, boolean requiresReconnect) {
    }

    @Schema(description = "How to play a track. Contains no audio: the client's SDK does the playing.")
    public record PlaybackTicketDto(
            String trackKey,
            ProviderId provider,
            PlaybackMethod method,
            @Schema(description = "SDK-specific arguments") Map<String, String> params) {

        public static PlaybackTicketDto from(PlaybackTicket ticket) {
            return new PlaybackTicketDto(
                    ticket.ref().toKey(),
                    ticket.ref().provider(),
                    ticket.method(),
                    ticket.params());
        }
    }

    public record ProviderDto(
            ProviderId id,
            String displayName,
            @Schema(description = "False when the service cannot be connected: either not "
                    + "implemented, or not configured")
            boolean available,
            @Schema(description = "Why it is unavailable, phrased for the user. Null when available.")
            String unavailableReason,
            boolean connected,
            String accountLabel,
            @Schema(description = "Whether the user can set this service up in the app. False "
                    + "for services that are not implemented, where credentials would not help.")
            boolean setupSupported,
            @Schema(description = "The service's own credentials setup, or null when unsupported")
            ProviderSetupDto setup,
            @Schema(description = "Permissions the service actually granted. Empty when not connected.")
            List<String> grantedScopes,
            @Schema(description = "Permissions this service needs but did not grant. Non-empty "
                    + "means importing will fail with a 403 until the user reconnects.")
            List<String> missingScopes,
            @Schema(description = "False for scraper-backed services that need no sign-in: they "
                    + "are usable immediately, with no connect button and import by URL.")
            boolean requiresAuthentication) {

        public ProviderDto {
            grantedScopes = grantedScopes == null ? List.of() : List.copyOf(grantedScopes);
            missingScopes = missingScopes == null ? List.of() : List.copyOf(missingScopes);
        }
    }

    @Schema(description = "What this service needs, and what is currently set up")
    public record ProviderSetupDto(
            @Schema(description = "The client ID currently in use. Not secret: a PKCE client ID "
                    + "ships in every copy of the app. Null when none is set.")
            String clientId,
            @Schema(description = "Whether a client secret is stored. The secret itself is never "
                    + "returned.")
            boolean clientSecretSet,
            @Schema(description = "Whether this service needs a client secret at all")
            boolean requiresClientSecret,
            @Schema(description = "APP when entered in the app, ENVIRONMENT when supplied at "
                    + "startup, NONE when unset")
            String source,
            @Schema(description = "The redirect URI that must be registered with the service")
            String redirectUri,
            @Schema(description = "Where to create the credentials")
            String consoleUrl,
            @Schema(description = "Step-by-step setup instructions for this service")
            List<String> instructions) {
    }

    public record SaveProviderSetupRequest(
            @NotBlank @Size(max = 512) String clientId,
            @Size(max = 512) String clientSecret) {
    }

    public record ImportUrlRequest(
            @Schema(description = "Public or unlisted playlist URL, e.g. "
                    + "https://www.youtube.com/playlist?list=…")
            @NotBlank @Size(max = 2048) String url) {
    }

    public record ImportSummaryDto(
            ProviderId provider,
            int importedCount,
            int trackCount,
            @Schema(description = "Playlists skipped because a copy already existed")
            int alreadyPresent,
            @Schema(description = "Playlists the service listed but refused to let us read. "
                    + "On Spotify this is everything the user follows rather than owns.")
            int unreadable,
            List<PlaylistDto> imported) {
    }
}
