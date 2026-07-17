package dev.unitedplaylists.provider.youtube;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.newpipe.NewPipeExtractionService;
import dev.unitedplaylists.provider.newpipe.NewPipeProvider;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * YouTube via NewPipe, replacing the old Data API integration.
 *
 * <p>The scraper trades the API's account access for everything else being better: no
 * 10,000-unit daily quota, no OAuth, no Google Cloud project, and real audio streams
 * instead of a hidden IFrame. Video ids are the same 11-character strings the Data API
 * used, so playlists imported under the old integration still resolve.
 *
 * <p>The one thing lost is reading the user's own YouTube playlists, which needs a
 * login the scraper does not do. Import is by pasting a playlist URL instead.
 */
@Component
public class YoutubeScraperProvider extends NewPipeProvider {

    public YoutubeScraperProvider(
            NewPipeExtractionService extraction,
            @Value("${unitedplaylists.scraper.enabled:true}") boolean enabled) {
        super(extraction, enabled);
    }

    @Override
    public ProviderId id() {
        return ProviderId.YOUTUBE;
    }

    @Override
    public String displayName() {
        return "YouTube";
    }

    @Override
    protected StreamingService service() {
        return ServiceList.YouTube;
    }
}
