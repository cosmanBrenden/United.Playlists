package dev.unitedplaylists.web;

import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.service.PlaybackService;
import dev.unitedplaylists.web.dto.Dtos.PlaybackTicketDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/playback")
@Tag(name = "Playback", description = "Client-side playback instructions")
public class PlaybackController {

    private final PlaybackService playbackService;

    public PlaybackController(PlaybackService playbackService) {
        this.playbackService = playbackService;
    }

    @GetMapping("/ticket")
    @Operation(
            summary = "Get playback instructions for a track",
            description = """
                    Returns which client-side SDK to play the track with and the
                    arguments to hand it — a Spotify URI for the Web Playback SDK, a
                    video id for the YouTube IFrame player, and so on.

                    This endpoint never returns audio or a URL that yields audio. None of
                    the supported services license server-side redistribution of their
                    streams, so playback happens in the client through the service's own
                    SDK, which handles licensing and playback reporting itself.
                    """)
    public PlaybackTicketDto ticket(
            @RequestParam("trackKey") String trackKey) {
        return PlaybackTicketDto.from(playbackService.ticketFor(TrackRef.fromKey(trackKey)));
    }
}
