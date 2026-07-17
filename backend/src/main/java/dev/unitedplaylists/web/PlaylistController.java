package dev.unitedplaylists.web;

import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.service.PlaylistService;
import dev.unitedplaylists.web.dto.Dtos.AddTrackRequest;
import dev.unitedplaylists.web.dto.Dtos.CreatePlaylistRequest;
import dev.unitedplaylists.web.dto.Dtos.MoveTrackRequest;
import dev.unitedplaylists.web.dto.Dtos.PlaylistDto;
import dev.unitedplaylists.web.dto.Dtos.UpdatePlaylistRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/playlists")
@Tag(name = "Playlists", description = "Local playlists. Edits never reach the origin service.")
public class PlaylistController {

    private final PlaylistService playlistService;

    public PlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @GetMapping
    @Operation(summary = "List playlists without their tracks")
    public List<PlaylistDto> list() {
        return playlistService.findAll().stream().map(PlaylistDto::summary).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one playlist with its tracks")
    public PlaylistDto get(@PathVariable UUID id) {
        return PlaylistDto.from(playlistService.findById(id));
    }

    @PostMapping
    @Operation(summary = "Create an empty playlist")
    public ResponseEntity<PlaylistDto> create(@Valid @RequestBody CreatePlaylistRequest request) {
        PlaylistDto created = PlaylistDto.from(
                playlistService.create(request.name(), request.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Rename or re-describe a playlist",
            description = "Local only. An imported playlist keeps its original name on its service.")
    public PlaylistDto update(@PathVariable UUID id, @Valid @RequestBody UpdatePlaylistRequest request) {
        playlistService.rename(id, request.name());
        return PlaylistDto.from(playlistService.describe(id, request.description()));
    }

    @PostMapping("/{id}/tracks")
    @Operation(
            summary = "Add a track from any service",
            description = "The track may come from a different service to the rest of the playlist.")
    public ResponseEntity<PlaylistDto> addTrack(
            @PathVariable UUID id, @Valid @RequestBody AddTrackRequest request) {
        TrackRef ref = TrackRef.fromKey(request.trackKey());
        Track track = new Track(
                ref,
                request.title(),
                request.artists() == null ? List.of() : request.artists(),
                request.album(),
                request.durationMs() == null ? null : Duration.ofMillis(request.durationMs()),
                request.artworkUrl(),
                true);
        PlaylistDto updated = PlaylistDto.from(playlistService.addTrack(id, track));
        return ResponseEntity.status(HttpStatus.CREATED).body(updated);
    }

    @DeleteMapping("/{id}/tracks/{position}")
    @Operation(summary = "Remove the track at a position")
    public PlaylistDto removeTrack(@PathVariable UUID id, @PathVariable int position) {
        return PlaylistDto.from(playlistService.removeTrack(id, position));
    }

    @PostMapping("/{id}/tracks/move")
    @Operation(summary = "Reorder a track")
    public PlaylistDto moveTrack(@PathVariable UUID id, @Valid @RequestBody MoveTrackRequest request) {
        return PlaylistDto.from(playlistService.moveTrack(id, request.from(), request.to()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete the local playlist",
            description = "Does not delete anything on the origin service.")
    public void delete(@PathVariable UUID id) {
        playlistService.delete(id);
    }
}
