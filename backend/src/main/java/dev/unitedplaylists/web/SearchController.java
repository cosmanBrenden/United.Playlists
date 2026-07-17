package dev.unitedplaylists.web;

import dev.unitedplaylists.service.SearchService;
import dev.unitedplaylists.web.dto.Dtos.SearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@Validated
@Tag(name = "Search", description = "One query, every connected service")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @Operation(
            summary = "Search every connected service",
            description = """
                    Queries all connected services concurrently and merges the results.
                    Each result names the service it came from.

                    A service that fails does not fail the request: it appears in
                    `failures`, `partial` becomes true, and the other services' results
                    are still returned.

                    Note: YouTube search costs 100 quota units against a 10,000/day
                    allowance, so callers should debounce rather than search per keystroke.
                    """)
    public SearchResponse search(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "20") @Min(1) @Max(50) int limit) {
        return SearchResponse.from(searchService.searchAll(query, limit));
    }
}
