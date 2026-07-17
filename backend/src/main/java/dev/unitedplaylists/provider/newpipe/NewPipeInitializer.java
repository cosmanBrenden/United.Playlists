package dev.unitedplaylists.provider.newpipe;

import jakarta.annotation.PostConstruct;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.springframework.stereotype.Component;

/**
 * Initialises NewPipeExtractor once, at startup.
 *
 * <p>{@link NewPipe#init} sets a single static downloader for the whole library, so
 * it must run before any extraction and exactly once. A Spring bean with
 * {@code @PostConstruct} is the natural fit: it runs at context start, and the
 * static-global nature is contained to this one place rather than scattered.
 */
@Component
public class NewPipeInitializer {

    private final NewPipeDownloader downloader;

    public NewPipeInitializer(NewPipeDownloader downloader) {
        this.downloader = downloader;
    }

    @PostConstruct
    public void init() {
        // English / US. Only affects which localised titles and region-restricted
        // results come back; it does not gate access.
        NewPipe.init(downloader, Localization.DEFAULT, ContentCountry.DEFAULT);
    }
}
