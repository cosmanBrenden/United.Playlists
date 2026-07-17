package dev.unitedplaylists.web;

import dev.unitedplaylists.provider.newpipe.ExtractorUpdateService;
import dev.unitedplaylists.provider.newpipe.ExtractorUpdateService.UpdateStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Extractor freshness, for the UI.
 *
 * <p>The scraper providers depend on NewPipe, which needs to stay current. This lets
 * the app show whether an update has been downloaded (and a restart will apply it), so
 * a user hitting extraction failures has somewhere to look.
 */
@RestController
@RequestMapping("/api/v1/extractor")
@Tag(name = "Extractor", description = "NewPipe extractor version and updates")
public class ExtractorController {

    private final ExtractorUpdateService updateService;

    public ExtractorController(ExtractorUpdateService updateService) {
        this.updateService = updateService;
    }

    @GetMapping("/status")
    @Operation(summary = "The running extractor version and whether an update is waiting")
    public UpdateStatus status() {
        return updateService.status();
    }

    @PostMapping("/check")
    @Operation(
            summary = "Check for an extractor update now",
            description = "Downloads a newer jar to the cache if there is one. It applies on "
                    + "the next restart. Normally this runs on startup and daily on its own.")
    public UpdateStatus checkNow() {
        return updateService.checkForUpdate();
    }
}
