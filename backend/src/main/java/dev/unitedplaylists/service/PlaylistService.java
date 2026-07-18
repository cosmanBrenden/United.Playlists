package dev.unitedplaylists.service;

import dev.unitedplaylists.domain.Playlist;
import dev.unitedplaylists.domain.PlaylistEntry;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.repository.PlaylistRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local playlist CRUD.
 *
 * <p>Every operation here touches local storage only. Renaming an imported
 * playlist, adding a YouTube track to a playlist that came from Spotify, deleting
 * one entirely — none of it reaches the origin service, which is spec 6 and also
 * the safer default: the app can never damage a library it does not own.
 */
@Service
public class PlaylistService {

    private final PlaylistRepository repository;
    private final Clock clock;

    public PlaylistService(PlaylistRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Playlist> findAll() {
        return repository.findAllWithEntries();
    }

    @Transactional(readOnly = true)
    public Playlist findById(UUID id) {
        return repository.findByIdWithEntries(id)
                .orElseThrow(() -> new PlaylistNotFoundException(id));
    }

    @Transactional
    public Playlist create(String name, String description) {
        return repository.save(Playlist.createLocal(name, description, now()));
    }

    @Transactional
    public Playlist rename(UUID id, String name) {
        Playlist playlist = findById(id);
        playlist.rename(name, now());
        return repository.save(playlist);
    }

    @Transactional
    public Playlist describe(UUID id, String description) {
        Playlist playlist = findById(id);
        playlist.describe(description, now());
        return repository.save(playlist);
    }

    /** Adds a track from any service to any playlist. Mixing services is the point. */
    @Transactional
    public Playlist addTrack(UUID id, Track track) {
        Playlist playlist = findById(id);
        playlist.addTrack(track, now());
        return repository.save(playlist);
    }

    @Transactional
    public Playlist removeTrack(UUID id, int position) {
        Playlist playlist = findById(id);
        playlist.removeAt(position, now());
        return repository.save(playlist);
    }

    /**
     * Replaces the track at {@code position} with {@code track}, keeping its place
     * in the order. This is how cross-service migration swaps a song for its copy on
     * another service.
     *
     * @param expectedKey the key the caller believes is currently at that position,
     *     or null to skip the check. When given and it does not match, the call is
     *     rejected rather than replacing the wrong track — a guard against a playlist
     *     that changed under a stale view (a reorder while a migration dialog was
     *     open).
     * @throws StaleReplacementException if {@code expectedKey} no longer sits there
     */
    @Transactional
    public Playlist replaceTrack(UUID id, int position, Track track, String expectedKey) {
        Playlist playlist = findById(id);
        guardExpected(playlist, position, expectedKey);
        playlist.replaceAt(position, track, now());
        return repository.save(playlist);
    }

    /**
     * Applies several in-place replacements at once, in one transaction.
     *
     * <p>Used by a migration job so replacing ten tracks is one save and one
     * version bump, not ten. In-place replacement means the positions in the map
     * stay valid as the batch is applied — nothing shifts.
     *
     * @param replacements position → replacement track
     */
    @Transactional
    public Playlist replaceTracks(UUID id, Map<Integer, Track> replacements) {
        Playlist playlist = findById(id);
        Instant now = now();
        replacements.forEach((position, track) -> playlist.replaceAt(position, track, now));
        return repository.save(playlist);
    }

    private void guardExpected(Playlist playlist, int position, String expectedKey) {
        if (expectedKey == null) {
            return;
        }
        List<PlaylistEntry> entries = playlist.getEntries();
        if (position < 0 || position >= entries.size()) {
            throw new StaleReplacementException(position, expectedKey, null);
        }
        String actual = entries.get(position).getRef().toKey();
        if (!actual.equals(expectedKey)) {
            throw new StaleReplacementException(position, expectedKey, actual);
        }
    }

    @Transactional
    public Playlist moveTrack(UUID id, int from, int to) {
        Playlist playlist = findById(id);
        playlist.move(from, to, now());
        return repository.save(playlist);
    }

    /** Deletes the local copy. An imported playlist remains untouched on its service. */
    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new PlaylistNotFoundException(id);
        }
        repository.deleteById(id);
    }

    private Instant now() {
        return clock.instant();
    }

    public static class PlaylistNotFoundException extends RuntimeException {
        public PlaylistNotFoundException(UUID id) {
            super("No playlist with id " + id);
        }
    }

    /**
     * The track a replace was meant to overwrite is no longer at that position.
     *
     * <p>Signals a lost race — the playlist changed between the caller reading it and
     * asking to replace — so the caller should reload rather than clobber whatever is
     * now there.
     */
    public static class StaleReplacementException extends RuntimeException {
        public StaleReplacementException(int position, String expectedKey, String actualKey) {
            super("Position " + position + " no longer holds " + expectedKey
                    + (actualKey == null ? " (out of range)" : " (now " + actualKey + ")"));
        }
    }
}
