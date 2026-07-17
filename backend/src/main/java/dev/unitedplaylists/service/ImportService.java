package dev.unitedplaylists.service;

import dev.unitedplaylists.domain.Playlist;
import dev.unitedplaylists.domain.PlaylistOrigin;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.provider.ImportedPlaylist;
import dev.unitedplaylists.provider.MusicProvider;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.ProviderRegistry;
import dev.unitedplaylists.repository.PlaylistRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copies playlists from a service into local storage (spec 3).
 *
 * <p>Import is a copy, not a sync. Re-importing a playlist that was already
 * brought in creates a second copy rather than overwriting the first, because the
 * first may have been edited locally and silently discarding the user's edits to
 * match an upstream change would be the single most destructive thing this app
 * could do. Reconciling the two is left to the user.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ProviderRegistry registry;
    private final ConnectionService connectionService;
    private final PlaylistRepository repository;
    private final Clock clock;

    public ImportService(
            ProviderRegistry registry,
            ConnectionService connectionService,
            PlaylistRepository repository,
            Clock clock) {
        this.registry = registry;
        this.connectionService = connectionService;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Imports every playlist the user has on {@code providerId}.
     *
     * @throws dev.unitedplaylists.provider.ProviderException if the service is not
     *     connected or the fetch fails outright
     */
    @Transactional
    public ImportSummary importFrom(ProviderId providerId) {
        MusicProvider provider = registry.require(providerId);
        ProviderCredentials credentials = connectionService.credentialsFor(providerId);
        Instant now = clock.instant();

        List<ImportedPlaylist> fetched;
        try {
            fetched = provider.fetchPlaylists(credentials);
        } catch (ProviderException e) {
            throw diagnose(provider, e);
        }
        return persist(providerId, fetched);
    }

    /**
     * Imports a single playlist from its public URL.
     *
     * <p>The import path for the anonymous scraper providers, which have no account to
     * enumerate. Pasting a link is the substitute for "import my playlists".
     *
     * @throws ProviderException if the provider does not support URL import, or the URL
     *     cannot be read
     */
    @Transactional
    public ImportSummary importFromUrl(ProviderId providerId, String url) {
        MusicProvider provider = registry.require(providerId);
        if (!(provider instanceof dev.unitedplaylists.provider.newpipe.NewPipeProvider scraper)) {
            throw new ProviderException(
                    providerId,
                    ProviderException.Kind.UNSUPPORTED,
                    provider.displayName() + " does not support importing a playlist by URL");
        }
        ImportedPlaylist playlist = scraper.importByUrl(url);
        return persist(providerId, List.of(playlist));
    }

    private ImportSummary persist(ProviderId providerId, List<ImportedPlaylist> fetched) {
        Instant now = clock.instant();
        List<Playlist> created = new ArrayList<>();
        int skipped = 0;
        int unreadable = 0;

        for (ImportedPlaylist source : fetched) {
            // Spotify lists playlists the user follows but refuses to hand over their
            // contents. Importing an empty shell of someone else's playlist would be
            // worse than leaving it out, so it is counted and reported instead.
            if (!source.readable()) {
                unreadable++;
                continue;
            }
            Optional<Playlist> existing =
                    repository.findByOrigin(providerId, source.providerPlaylistId());
            if (existing.isPresent()) {
                skipped++;
                continue;
            }
            Playlist playlist = Playlist.createImported(
                    source.name(),
                    source.description(),
                    new PlaylistOrigin(providerId, source.providerPlaylistId(), now),
                    now);
            for (Track track : source.tracks()) {
                playlist.addTrack(track, now);
            }
            created.add(repository.save(playlist));
        }

        log.info("Imported {} playlist(s) from {}; {} already present, {} not readable",
                created.size(), providerId, skipped, unreadable);
        return new ImportSummary(providerId, created, skipped, unreadable);
    }

    /**
     * Turns an opaque 403 into a diagnosis.
     *
     * <p>Spotify answers several unrelated problems with {@code 403 Forbidden} and no
     * further detail, so the status alone cannot tell a missing permission from an app
     * that is misconfigured in the dashboard. The two have completely different fixes,
     * and the user has no way to tell which they are looking at.
     *
     * <p>We can, though: the token records which scopes were actually granted. Compare
     * that against what the provider needs and the answer falls out — either a
     * permission is missing, and reconnecting fixes it, or every permission is present,
     * which rules the token out entirely and points at the service's own settings.
     */
    private ProviderException diagnose(MusicProvider provider, ProviderException failure) {
        if (failure.getKind() != ProviderException.Kind.FORBIDDEN) {
            return failure;
        }
        List<String> granted = connectionService.grantedScopesFor(provider.id());
        List<String> missing = provider.requiredScopes().stream()
                .filter(scope -> !granted.contains(scope))
                .toList();

        String detail = missing.isEmpty()
                ? ("The token has every permission this needs (" + String.join(", ", granted)
                        + "), so the problem is not sign-in. Check the app's settings in the "
                        + provider.displayName() + " dashboard: that the Web API is enabled, "
                        + "and that this account is on the app's allowed-users list.")
                : ("The token is missing " + String.join(", ", missing)
                        + " — it only granted " + (granted.isEmpty() ? "nothing" : String.join(", ", granted))
                        + ". Disconnect " + provider.displayName()
                        + " and connect again, approving every permission it asks for.");

        log.warn("{} refused the import. Granted scopes: {}. Missing: {}",
                provider.id(), granted, missing);
        return new ProviderException(
                provider.id(),
                ProviderException.Kind.FORBIDDEN,
                failure.getMessage() + " — " + detail,
                failure);
    }

    /**
     * What an import did.
     *
     * @param alreadyPresent playlists skipped because a copy already existed
     * @param unreadable     playlists the service listed but would not let us read.
     *                       On Spotify this is everything the user follows rather than
     *                       owns, which for most accounts is a large share of the list —
     *                       so "imported 3" with no explanation would look like a bug.
     */
    public record ImportSummary(
            ProviderId provider, List<Playlist> imported, int alreadyPresent, int unreadable) {

        public ImportSummary {
            imported = imported == null ? List.of() : List.copyOf(imported);
        }

        public int importedCount() {
            return imported.size();
        }

        public int trackCount() {
            return imported.stream().mapToInt(Playlist::size).sum();
        }
    }
}
