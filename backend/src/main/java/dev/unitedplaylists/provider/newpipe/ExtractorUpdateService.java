package dev.unitedplaylists.provider.newpipe;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.schabi.newpipe.extractor.NewPipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the NewPipe extractor up to date.
 *
 * <p>The extractor breaks whenever YouTube changes something, and the fix is always a
 * newer release. So this checks GitHub for the latest NewPipeExtractor tag — on startup
 * and daily — and downloads the jar to a cache directory when it is newer than what is
 * running.
 *
 * <p>Downloading is all this does. Applying the update is the launcher's job: on the
 * next start, the Electron shell puts the newest cached jar ahead of the bundled one on
 * the classpath (Spring Boot's {@code loader.path}), so it shadows the bundled version
 * with no rebuild. A compile-time dependency cannot be swapped in a running JVM, so
 * "download now, apply on next launch" is the honest contract — and an extractor update
 * the user will not notice until their next session anyway.
 *
 * <p>Every failure here is non-fatal: no network, GitHub down, a malformed response —
 * the app keeps running on whatever extractor it already has.
 */
@Service
public class ExtractorUpdateService {

    private static final Logger log = LoggerFactory.getLogger(ExtractorUpdateService.class);

    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest";
    private static final String JAR_URL_TEMPLATE =
            "https://jitpack.io/com/github/TeamNewPipe/NewPipeExtractor/%s/NewPipeExtractor-%s.jar";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final Path cacheDir;
    private final boolean enabled;

    /** The running extractor's version, and whatever the check last found, for the UI. */
    private volatile String runningVersion;
    private volatile String latestKnownVersion;
    private volatile boolean updateDownloaded;

    public ExtractorUpdateService(
            @Value("${unitedplaylists.newpipe.cache-dir:${user.home}/.unitedplaylists/newpipe}")
            String cacheDir,
            @Value("${unitedplaylists.newpipe.auto-update:true}") boolean enabled) {
        this.cacheDir = Path.of(cacheDir);
        this.enabled = enabled;
        this.runningVersion = readRunningVersion();
    }

    /** Runs shortly after startup, off the main thread so boot is not delayed. */
    @Async
    @Scheduled(initialDelay = 5_000, fixedDelay = Long.MAX_VALUE)
    public void checkOnStartup() {
        checkForUpdate();
    }

    /** And once a day thereafter, for long-running sessions. */
    @Scheduled(fixedRate = 24 * 60 * 60 * 1000L)
    public void checkDaily() {
        checkForUpdate();
    }

    /**
     * Checks GitHub and downloads a newer jar if there is one.
     *
     * <p>Package-visible and returning its outcome so a test or an admin endpoint can
     * trigger it directly rather than waiting for the schedule.
     */
    public synchronized UpdateStatus checkForUpdate() {
        if (!enabled) {
            return status();
        }
        try {
            String latest = fetchLatestTag();
            this.latestKnownVersion = latest;

            if (isAlreadyCurrent(latest)) {
                log.debug("NewPipe extractor is current ({})", latest);
                return status();
            }
            if (cachedJarFor(latest).map(Files::exists).orElse(false)) {
                log.info("NewPipe {} is already downloaded; it applies on the next restart", latest);
                this.updateDownloaded = true;
                return status();
            }

            downloadJar(latest);
            this.updateDownloaded = true;
            log.info("Downloaded NewPipe extractor {}. It will be used after the next restart.",
                    latest);
        } catch (Exception e) {
            // Never fatal: the app runs on whatever extractor it has.
            log.warn("Could not check for a NewPipe extractor update: {}", e.getMessage());
        }
        return status();
    }

    /** The newest cached jar, for the launcher to put on the classpath. Empty if none. */
    public Optional<Path> newestCachedJar() {
        if (!Files.isDirectory(cacheDir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(cacheDir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .max(Comparator.comparing(this::versionOf));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public UpdateStatus status() {
        return new UpdateStatus(
                runningVersion,
                latestKnownVersion,
                updateDownloaded,
                latestKnownVersion != null && !isAlreadyCurrent(latestKnownVersion));
    }

    private String fetchLatestTag() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_URL))
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub returned HTTP " + response.statusCode());
        }
        JsonNode body = mapper.readTree(response.body());
        String tag = body.path("tag_name").asText(null);
        if (tag == null || tag.isBlank()) {
            throw new IOException("GitHub response had no tag_name");
        }
        return tag;
    }

    private void downloadJar(String tag) throws IOException, InterruptedException {
        Files.createDirectories(cacheDir);
        String url = JAR_URL_TEMPLATE.formatted(tag, tag);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();

        // Download to a temp file first, then move into place, so a half-written jar is
        // never picked up by the launcher.
        Path target = cacheDir.resolve("NewPipeExtractor-" + tag + ".jar");
        Path temp = Files.createTempFile(cacheDir, "download-", ".jar.part");
        try {
            HttpResponse<Path> response =
                    http.send(request, HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() != 200) {
                throw new IOException("JitPack returned HTTP " + response.statusCode() + " for " + url);
            }
            if (Files.size(temp) < 100_000) {
                // A real extractor jar is ~800KB; anything tiny is an error page.
                throw new IOException("Downloaded jar is implausibly small; treating as a failure");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private boolean isAlreadyCurrent(String latest) {
        // The running version is either the bundled jar's, or a cached one the launcher
        // applied. Compare on the normalised version string.
        return versionOf(latest).equals(versionOf(runningVersion));
    }

    private Optional<Path> cachedJarFor(String tag) {
        return Optional.of(cacheDir.resolve("NewPipeExtractor-" + tag + ".jar"));
    }

    /** Extracts a comparable version from a tag or filename, e.g. "v0.26.3" -> "0.26.3". */
    private String versionOf(Object tagOrPath) {
        if (tagOrPath == null) {
            return "";
        }
        String s = tagOrPath.toString();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(s);
        return m.find() ? m.group(1) : s;
    }

    private String readRunningVersion() {
        Package pkg = NewPipe.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null ? "bundled" : version;
    }

    /** What the UI needs to show about extractor freshness. */
    public record UpdateStatus(
            String runningVersion,
            String latestVersion,
            boolean updateDownloaded,
            boolean updateAvailable) {
    }
}
