package dev.unitedplaylists.provider.soundcloud;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.newpipe.NewPipeExtractionService;
import dev.unitedplaylists.provider.newpipe.NewPipeProvider;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SoundCloud via NewPipe.
 *
 * <p>Anonymous like the YouTube scraper, and for the same reasons: no OAuth, no tokens
 * to expire, no quota. SoundCloud's public catalogue is searchable and playable
 * directly; a playlist is imported by pasting its URL.
 */
@Component
public class SoundCloudProvider extends NewPipeProvider {

    public SoundCloudProvider(
            NewPipeExtractionService extraction,
            @Value("${unitedplaylists.scraper.enabled:true}") boolean enabled) {
        super(extraction, enabled);
    }

    @Override
    public ProviderId id() {
        return ProviderId.SOUNDCLOUD;
    }

    @Override
    public String displayName() {
        return "SoundCloud";
    }

    @Override
    protected StreamingService service() {
        return ServiceList.SoundCloud;
    }
}
