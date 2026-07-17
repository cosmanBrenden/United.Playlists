package dev.unitedplaylists.service;

import dev.unitedplaylists.domain.Playlist;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.repository.PlaylistRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
}
