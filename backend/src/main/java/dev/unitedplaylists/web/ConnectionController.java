package dev.unitedplaylists.web;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.MusicProvider;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.ProviderRegistry;
import dev.unitedplaylists.provider.oauth.OAuthProperties;
import dev.unitedplaylists.service.ConnectionService;
import dev.unitedplaylists.service.ImportService;
import dev.unitedplaylists.service.OAuthFlowService;
import dev.unitedplaylists.service.ProviderSettingsService;
import dev.unitedplaylists.web.dto.Dtos.ImportSummaryDto;
import dev.unitedplaylists.web.dto.Dtos.ImportUrlRequest;
import dev.unitedplaylists.web.dto.Dtos.PlaylistDto;
import dev.unitedplaylists.web.dto.Dtos.ProviderDto;
import dev.unitedplaylists.web.dto.Dtos.ProviderSetupDto;
import dev.unitedplaylists.web.dto.Dtos.SaveProviderSetupRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/connections")
@Tag(name = "Connections", description = "Connecting streaming services and importing from them")
public class ConnectionController {

    private final ProviderRegistry registry;
    private final ConnectionService connectionService;
    private final OAuthFlowService oauthFlowService;
    private final ImportService importService;
    private final ProviderSettingsService settingsService;
    /** Shown to the user to copy into the service's console; must match exactly. */
    private final String redirectUri;

    public ConnectionController(
            ProviderRegistry registry,
            ConnectionService connectionService,
            OAuthFlowService oauthFlowService,
            ImportService importService,
            ProviderSettingsService settingsService,
            OAuthProperties oauthProperties) {
        this.registry = registry;
        this.connectionService = connectionService;
        this.oauthFlowService = oauthFlowService;
        this.importService = importService;
        this.settingsService = settingsService;
        this.redirectUri = oauthProperties.redirectUri();
    }

    @GetMapping("/providers")
    @Operation(
            summary = "List every known service and its connection state",
            description = "Includes services that are not implemented yet, marked available=false.")
    public List<ProviderDto> providers() {
        Map<ProviderId, ConnectionService.ConnectionStatus> connected =
                connectionService.listConnections().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                ConnectionService.ConnectionStatus::provider, s -> s));

        return registry.all().stream()
                .sorted(java.util.Comparator.comparing(MusicProvider::id))
                .map(provider -> toDto(provider, connected.get(provider.id()) != null,
                        connected.get(provider.id()) == null
                                ? null
                                : connected.get(provider.id()).accountLabel()))
                .toList();
    }

    @PostMapping("/{provider}/authorize")
    @Operation(
            summary = "Begin connecting a service",
            description = """
                    Returns the URL to open in a browser. Electron opens it in the user's
                    real browser rather than an embedded window, because the services
                    block sign-in from embedded webviews and because an embedded window
                    would give this app sight of the user's password.
                    """)
    public Map<String, String> authorize(@PathVariable ProviderId provider) {
        return Map.of("authorizationUrl", oauthFlowService.beginAuthorization(provider));
    }

    @PostMapping("/{provider}/callback")
    @Operation(summary = "Finish connecting a service with the code from the callback")
    public ProviderDto callback(
            @PathVariable ProviderId provider, @RequestBody Map<String, String> body) {
        String code = body.get("code");
        String state = body.get("state");
        if (code == null || state == null) {
            throw new ProviderException(
                    provider, ProviderException.Kind.UNAUTHORIZED,
                    "Callback is missing code or state");
        }
        oauthFlowService.completeAuthorization(provider, code, state);

        MusicProvider musicProvider = registry.require(provider);
        var status = connectionService.listConnections().stream()
                .filter(s -> s.provider() == provider)
                .findFirst()
                .orElse(null);
        return toDto(musicProvider, true, status == null ? null : status.accountLabel());
    }

    @GetMapping("/{provider}/setup")
    @Operation(
            summary = "What this service needs to be set up, and what is already set",
            description = "The client ID is returned as-is; it is not secret. The client "
                    + "secret is never returned, only whether one is stored.")
    public ProviderSetupDto setup(@PathVariable ProviderId provider) {
        return setupOf(registry.require(provider));
    }

    @PutMapping("/{provider}/setup")
    @Operation(
            summary = "Save credentials entered in the app",
            description = "Takes effect immediately; no restart. Overrides anything supplied "
                    + "by configuration at startup.")
    public ProviderSetupDto saveSetup(
            @PathVariable ProviderId provider,
            @Valid @RequestBody SaveProviderSetupRequest request) {
        MusicProvider musicProvider = registry.require(provider);
        if (!musicProvider.isSetupSupported()) {
            throw new ProviderException(
                    provider,
                    ProviderException.Kind.UNSUPPORTED,
                    musicProvider.displayName() + " cannot be set up with credentials yet");
        }
        if (musicProvider.requiresClientSecret()
                && (request.clientSecret() == null || request.clientSecret().isBlank())) {
            // Saving without it would produce a service that looks configured and then
            // fails at the token exchange with an opaque Google error.
            throw new IllegalArgumentException(
                    musicProvider.displayName() + " also needs a client secret");
        }
        settingsService.save(provider, request.clientId(), request.clientSecret());
        return setupOf(musicProvider);
    }

    @DeleteMapping("/{provider}/setup")
    @Operation(
            summary = "Forget credentials entered in the app",
            description = "Falls back to whatever was configured at startup, if anything. "
                    + "Does not sign the user out; use DELETE /{provider} for that.")
    public ProviderSetupDto clearSetup(@PathVariable ProviderId provider) {
        settingsService.clear(provider);
        return setupOf(registry.require(provider));
    }

    private ProviderDto toDto(MusicProvider provider, boolean connected, String accountLabel) {
        List<String> granted = connected
                ? connectionService.grantedScopesFor(provider.id())
                : List.of();
        // A scope that was asked for but not granted produces a 403 on one endpoint and
        // silence everywhere else. Naming it here turns that into something the user
        // can act on.
        List<String> missing = connected
                ? provider.requiredScopes().stream()
                        .filter(scope -> !granted.contains(scope))
                        .toList()
                : List.of();

        return new ProviderDto(
                provider.id(),
                provider.displayName(),
                provider.isAvailable(),
                provider.unavailableReason(),
                connected,
                accountLabel,
                provider.isSetupSupported(),
                provider.isSetupSupported() ? setupOf(provider) : null,
                granted,
                missing,
                provider.requiresAuthentication());
    }

    private ProviderSetupDto setupOf(MusicProvider provider) {
        return new ProviderSetupDto(
                settingsService.clientId(provider.id()).orElse(null),
                settingsService.clientSecret(provider.id()).isPresent(),
                provider.requiresClientSecret(),
                settingsService.sourceOf(provider.id()).name(),
                redirectUri,
                provider.consoleUrl(),
                provider.setupInstructions(redirectUri));
    }

    @GetMapping("/{provider}/access-token")
    @Operation(
            summary = "Get a current access token for a service's client-side SDK",
            description = """
                    Exists because the Spotify Web Playback SDK runs in the client and
                    demands an access token there; without this the SDK cannot play.

                    Only the short-lived access token is handed out, never the refresh
                    token, and it is refreshed first if it is near expiry. The refresh
                    token stays encrypted in the backend, so a compromised renderer
                    loses at most one hour of access rather than the account.
                    """)
    public Map<String, String> accessToken(@PathVariable ProviderId provider) {
        return Map.of("accessToken", connectionService.credentialsFor(provider).accessToken());
    }

    @DeleteMapping("/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Disconnect a service",
            description = """
                    Forgets the tokens. Playlists already imported from it are kept —
                    they are local copies, and deleting the user's library because they
                    unlinked an account would be a surprise. Their tracks stop being
                    playable until the service is reconnected.
                    """)
    public void disconnect(@PathVariable ProviderId provider) {
        connectionService.disconnect(provider);
    }

    @PostMapping("/{provider}/import")
    @Operation(
            summary = "Import all playlists from a connected service",
            description = """
                    Copies playlists in. A playlist that was already imported is skipped
                    rather than overwritten, since the local copy may have been edited.
                    """)
    public ImportSummaryDto importPlaylists(@PathVariable ProviderId provider) {
        return toDto(importService.importFrom(provider));
    }

    @PostMapping("/{provider}/import-url")
    @Operation(
            summary = "Import one playlist from its public URL",
            description = """
                    For the scraper-backed services (YouTube, SoundCloud), which have no
                    account to enumerate. Paste a public or unlisted playlist link. A
                    private playlist the scraper cannot see is reported as unreadable.
                    """)
    public ImportSummaryDto importByUrl(
            @PathVariable ProviderId provider,
            @Valid @RequestBody ImportUrlRequest request) {
        return toDto(importService.importFromUrl(provider, request.url()));
    }

    private ImportSummaryDto toDto(ImportService.ImportSummary summary) {
        return new ImportSummaryDto(
                summary.provider(),
                summary.importedCount(),
                summary.trackCount(),
                summary.alreadyPresent(),
                summary.unreadable(),
                summary.imported().stream().map(PlaylistDto::summary).toList());
    }
}
