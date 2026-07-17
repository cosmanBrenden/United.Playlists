package dev.unitedplaylists.provider.newpipe;

import static org.assertj.core.api.Assertions.assertThat;

import dev.unitedplaylists.provider.newpipe.ExtractorUpdateService.UpdateStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The extractor updater's decision logic.
 *
 * <p>These cover version comparison and cache selection without any network: the
 * GitHub/JitPack calls are exercised by hand, not in the suite. The point being pinned
 * is that the updater never applies anything in the running process — it only downloads
 * — and that it picks the newest cached jar for the launcher to apply next start.
 */
class ExtractorUpdateServiceTest {

    private ExtractorUpdateService service(Path cache) {
        // auto-update off, so constructing it never reaches out.
        return new ExtractorUpdateService(cache.toString(), false, "v0.26.3");
    }

    @Test
    @DisplayName("with auto-update off, checking is a no-op that still reports status")
    void disabledCheckIsInert(@TempDir Path cache) {
        UpdateStatus status = service(cache).checkForUpdate();

        assertThat(status.runningVersion()).isNotBlank();
        assertThat(status.updateDownloaded()).isFalse();
    }

    @Test
    @DisplayName("picks the highest-versioned cached jar")
    void selectsNewestCachedJar(@TempDir Path cache) throws Exception {
        Files.createFile(cache.resolve("NewPipeExtractor-v0.26.1.jar"));
        Files.createFile(cache.resolve("NewPipeExtractor-v0.26.3.jar"));
        Files.createFile(cache.resolve("NewPipeExtractor-v0.24.9.jar"));

        Path newest = service(cache).newestCachedJar().orElseThrow();

        assertThat(newest.getFileName().toString()).isEqualTo("NewPipeExtractor-v0.26.3.jar");
    }

    @Test
    @DisplayName("ignores non-jar files when picking the newest")
    void ignoresNonJars(@TempDir Path cache) throws Exception {
        Files.createFile(cache.resolve("NewPipeExtractor-v0.26.3.jar"));
        Files.createFile(cache.resolve("download-123.jar.part"));
        Files.createFile(cache.resolve("notes.txt"));

        Path newest = service(cache).newestCachedJar().orElseThrow();

        assertThat(newest.getFileName().toString()).isEqualTo("NewPipeExtractor-v0.26.3.jar");
    }

    @Test
    @DisplayName("no cache directory yields no jar rather than an error")
    void missingCacheIsEmpty(@TempDir Path parent) {
        ExtractorUpdateService service = service(parent.resolve("does-not-exist"));

        assertThat(service.newestCachedJar()).isEmpty();
    }

    @Test
    @DisplayName("with no cache, the running version is the bundled one")
    void runningVersionIsBundledWithNoCache(@TempDir Path cache) {
        assertThat(service(cache).runningVersion()).isEqualTo("0.26.3");
    }

    @Test
    @DisplayName("a newer cached jar becomes the running version, since the launcher applies it")
    void runningVersionReflectsANewerCachedJar(@TempDir Path cache) throws Exception {
        Files.createFile(cache.resolve("NewPipeExtractor-v0.27.0.jar"));

        assertThat(service(cache).runningVersion()).isEqualTo("0.27.0");
    }

    @Test
    @DisplayName("an older cached jar does not downgrade the running version")
    void olderCachedJarIsIgnoredForRunningVersion(@TempDir Path cache) throws Exception {
        Files.createFile(cache.resolve("NewPipeExtractor-v0.25.0.jar"));

        // The launcher would never prefer an older jar over the bundled one.
        assertThat(service(cache).runningVersion()).isEqualTo("0.26.3");
    }

    @Test
    @DisplayName("compares versions numerically, so 0.26.10 beats 0.26.9")
    void comparesVersionsNumerically(@TempDir Path cache) throws Exception {
        Files.createFile(cache.resolve("NewPipeExtractor-v0.26.9.jar"));
        Files.createFile(cache.resolve("NewPipeExtractor-v0.26.10.jar"));

        Path newest = service(cache).newestCachedJar().orElseThrow();

        // A string sort would put v0.26.9 last; a numeric one must not.
        assertThat(newest.getFileName().toString()).isEqualTo("NewPipeExtractor-v0.26.10.jar");
    }
}
