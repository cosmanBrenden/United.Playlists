package dev.unitedplaylists.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A playlist owned by this app.
 *
 * <p>Every playlist here is local, including imported ones: importing copies the
 * track references in and then the copy goes its own way. Edits are never pushed
 * back to the origin service. That is enforced structurally rather than by
 * convention, because {@code MusicProvider} exposes no playlist-write operation
 * for a service to call in the first place.
 *
 * <p>A playlist may freely mix tracks from different services.
 */
@Entity
@Table(name = "playlist")
public class Playlist {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 2048)
    private String description;

    @Embedded
    private PlaylistOrigin origin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "playlist", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<PlaylistEntry> entries = new ArrayList<>();

    protected Playlist() {
        // JPA
    }

    private Playlist(String name, String description, PlaylistOrigin origin, Instant now) {
        this.name = requireName(name);
        this.description = description;
        this.origin = origin;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A playlist the user made in this app. */
    public static Playlist createLocal(String name, String description, Instant now) {
        return new Playlist(name, description, null, now);
    }

    /** A playlist copied in from a service; {@code origin} records where from. */
    public static Playlist createImported(
            String name, String description, PlaylistOrigin origin, Instant now) {
        Objects.requireNonNull(origin, "origin");
        return new Playlist(name, description, origin, now);
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Playlist name must not be blank");
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /** Empty for playlists created in this app. */
    public Optional<PlaylistOrigin> getOrigin() {
        return Optional.ofNullable(origin);
    }

    public boolean isImported() {
        return origin != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<PlaylistEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public int size() {
        return entries.size();
    }

    /** The distinct services this playlist draws on, for badge rendering. */
    public List<ProviderId> providersUsed() {
        return entries.stream()
                .map(e -> e.getRef().provider())
                .distinct()
                .sorted()
                .toList();
    }

    public void rename(String newName, Instant now) {
        this.name = requireName(newName);
        touch(now);
    }

    public void describe(String newDescription, Instant now) {
        this.description = newDescription;
        touch(now);
    }

    /** Appends to the end. Duplicates are allowed: playlists legitimately repeat tracks. */
    public PlaylistEntry addTrack(Track track, Instant now) {
        Objects.requireNonNull(track, "track");
        PlaylistEntry entry = new PlaylistEntry(this, entries.size(), track, now);
        entries.add(entry);
        touch(now);
        return entry;
    }

    /** Removes by position, closing the gap so positions stay dense. */
    public PlaylistEntry removeAt(int position, Instant now) {
        checkPosition(position, entries.size() - 1);
        PlaylistEntry removed = entries.remove(position);
        reindexFrom(position);
        touch(now);
        return removed;
    }

    /**
     * Swaps the track at {@code position} for {@code track}, keeping the slot's
     * place in the order. Used by cross-service migration to replace a track with
     * the same song on another service.
     *
     * @return the entry, now carrying the new track
     */
    public PlaylistEntry replaceAt(int position, Track track, Instant now) {
        Objects.requireNonNull(track, "track");
        checkPosition(position, entries.size() - 1);
        PlaylistEntry entry = entries.get(position);
        entry.replaceTrack(track);
        touch(now);
        return entry;
    }

    /** Moves one entry, shifting the rest. Used by drag-to-reorder in the UI. */
    public void move(int from, int to, Instant now) {
        checkPosition(from, entries.size() - 1);
        checkPosition(to, entries.size() - 1);
        if (from == to) {
            return;
        }
        PlaylistEntry moving = entries.remove(from);
        entries.add(to, moving);
        reindexFrom(Math.min(from, to));
        touch(now);
    }

    private void checkPosition(int position, int max) {
        if (position < 0 || position > max) {
            throw new IndexOutOfBoundsException(
                    "Position " + position + " outside playlist of size " + entries.size());
        }
    }

    private void reindexFrom(int start) {
        for (int i = start; i < entries.size(); i++) {
            entries.get(i).setPosition(i);
        }
    }

    private void touch(Instant now) {
        this.updatedAt = now;
    }
}
