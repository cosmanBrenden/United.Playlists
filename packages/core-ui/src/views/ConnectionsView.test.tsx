import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ConnectionsView } from "./ConnectionsView";
import type { ApiClient } from "../api/client";
import type { ProviderInfo, ProviderSetup } from "../api/types";

const setup = (over: Partial<ProviderSetup> = {}): ProviderSetup => ({
  clientId: null,
  clientSecretSet: false,
  requiresClientSecret: false,
  source: "NONE",
  redirectUri: "http://127.0.0.1:8420/callback",
  consoleUrl: "https://developer.spotify.com/dashboard",
  instructions: ["Do the first thing", "Then the second thing"],
  ...over,
});

const provider = (over: Partial<ProviderInfo> & Pick<ProviderInfo, "id">): ProviderInfo => ({
  displayName: over.id,
  available: true,
  unavailableReason: null,
  connected: false,
  accountLabel: null,
  setupSupported: true,
  setup: setup(),
  grantedScopes: [],
  missingScopes: [],
  requiresAuthentication: true,
  ...over,
});

describe("ConnectionsView", () => {
  let client: {
    beginAuthorization: ReturnType<typeof vi.fn>;
    completeAuthorization: ReturnType<typeof vi.fn>;
    disconnect: ReturnType<typeof vi.fn>;
    importPlaylists: ReturnType<typeof vi.fn>;
    importPlaylistByUrl: ReturnType<typeof vi.fn>;
    saveSetup: ReturnType<typeof vi.fn>;
    clearSetup: ReturnType<typeof vi.fn>;
    extractorStatus: ReturnType<typeof vi.fn>;
  };
  let authorize: ReturnType<typeof vi.fn>;
  let onChanged: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    client = {
      beginAuthorization: vi.fn(),
      completeAuthorization: vi.fn(),
      disconnect: vi.fn(),
      importPlaylists: vi.fn(),
      importPlaylistByUrl: vi.fn(),
      saveSetup: vi.fn(),
      clearSetup: vi.fn(),
      // Non-essential UI extra; default to a never-resolving promise so it stays inert.
      extractorStatus: vi.fn(() => new Promise(() => {})),
    };
    authorize = vi.fn();
    onChanged = vi.fn();
  });

  const renderView = (providers: readonly ProviderInfo[]) =>
    render(
      <ConnectionsView
        client={client as unknown as ApiClient}
        providers={providers}
        onChanged={onChanged}
        authorize={authorize}
      />,
    );

  describe("why a service is unavailable", () => {
    /**
     * The regression. The view used to hard-code one explanation for every
     * unavailable service, so an unconfigured Spotify told the user to go and buy an
     * Apple Developer membership.
     */
    it("shows each service's own reason, not one reason for all of them", () => {
      renderView([
        provider({
          id: "SPOTIFY",
          displayName: "Spotify",
          available: false,
          unavailableReason: "No Spotify client ID is configured. Set UP_SPOTIFY_CLIENT_ID.",
        }),
        provider({
          id: "YOUTUBE",
          displayName: "YouTube",
          available: false,
          unavailableReason: "No Google client ID is configured. Set UP_YOUTUBE_CLIENT_ID.",
        }),
        provider({
          id: "APPLE_MUSIC",
          displayName: "Apple Music",
          available: false,
          unavailableReason: "Not supported yet. Apple Music needs a paid Apple Developer membership.",
        }),
      ]);

      expect(screen.getByText(/UP_SPOTIFY_CLIENT_ID/)).toBeInTheDocument();
      expect(screen.getByText(/UP_YOUTUBE_CLIENT_ID/)).toBeInTheDocument();
      // Exactly one service may mention the membership.
      expect(screen.getAllByText(/Apple Developer membership/)).toHaveLength(1);
    });

    it("does not invent a reason when the backend gives none", () => {
      renderView([
        provider({ id: "SPOTIFY", displayName: "Spotify", available: false, unavailableReason: null }),
      ]);

      expect(screen.queryByText(/Apple/)).not.toBeInTheDocument();
      expect(screen.queryByText(/client ID/)).not.toBeInTheDocument();
    });

    it("offers a way out rather than a dead end for a built-but-unconfigured service", () => {
      renderView([
        provider({
          id: "SPOTIFY",
          displayName: "Spotify",
          available: false,
          unavailableReason: "Add your Spotify client ID to connect.",
        }),
      ]);

      // Spotify is fully built and only missing keys, so the user can fix it here.
      // A disabled "Unavailable" button — or "Coming soon" — would strand them.
      expect(screen.getByRole("button", { name: "Add keys" })).toBeEnabled();
      expect(screen.queryByRole("button", { name: "Unavailable" })).not.toBeInTheDocument();
      expect(screen.queryByText(/Coming soon/)).not.toBeInTheDocument();
      expect(screen.getByText("Add your Spotify client ID to connect.")).toBeInTheDocument();
    });

    it("offers no reason for an available service", () => {
      renderView([provider({ id: "SPOTIFY", displayName: "Spotify", available: true })]);

      expect(screen.getByRole("button", { name: "Connect" })).toBeEnabled();
      expect(screen.queryByText(/Unavailable/)).not.toBeInTheDocument();
    });
  });

  describe("entering keys in the app", () => {
    it("opens a setup form with the service's own instructions", async () => {
      renderView([
        provider({
          id: "SPOTIFY",
          displayName: "Spotify",
          available: false,
          setup: setup({ instructions: ["Open the Spotify dashboard", "Copy the Client ID"] }),
        }),
      ]);

      await userEvent.click(screen.getByRole("button", { name: "Add keys" }));

      expect(screen.getByText("Open the Spotify dashboard")).toBeInTheDocument();
      expect(screen.getByLabelText(/Client ID/)).toBeInTheDocument();
    });

    it("shows the redirect URI that has to be registered", async () => {
      renderView([provider({ id: "SPOTIFY", displayName: "Spotify", available: false })]);

      await userEvent.click(screen.getByRole("button", { name: "Add keys" }));

      // Getting this wrong is the most common reason sign-in fails, and the service
      // never explains it, so it must be right there to copy.
      expect(screen.getByText("http://127.0.0.1:8420/callback")).toBeInTheDocument();
    });

    it("saves the client ID and reports the change", async () => {
      client.saveSetup.mockResolvedValue(setup({ clientId: "typed-id", source: "APP" }));
      renderView([provider({ id: "SPOTIFY", displayName: "Spotify", available: false })]);

      await userEvent.click(screen.getByRole("button", { name: "Add keys" }));
      await userEvent.type(screen.getByLabelText(/Client ID/), "typed-id");
      await userEvent.click(screen.getByRole("button", { name: "Save" }));

      await waitFor(() => {
        expect(client.saveSetup).toHaveBeenCalledWith("SPOTIFY", "typed-id", null);
      });
      // Refreshing is what makes the service become available without a restart.
      expect(onChanged).toHaveBeenCalled();
    });

    it("trims a pasted client ID", async () => {
      client.saveSetup.mockResolvedValue(setup());
      renderView([provider({ id: "SPOTIFY", displayName: "Spotify", available: false })]);

      await userEvent.click(screen.getByRole("button", { name: "Add keys" }));
      // Copying from a web console routinely picks up whitespace.
      await userEvent.type(screen.getByLabelText(/Client ID/), "  padded-id  ");
      await userEvent.click(screen.getByRole("button", { name: "Save" }));

      await waitFor(() => {
        expect(client.saveSetup).toHaveBeenCalledWith("SPOTIFY", "padded-id", null);
      });
    });

    it("will not save an empty client ID", async () => {
      renderView([provider({ id: "SPOTIFY", displayName: "Spotify", available: false })]);

      await userEvent.click(screen.getByRole("button", { name: "Add keys" }));

      expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    });

    describe("services that need a secret", () => {
      const youtube = provider({
        id: "YOUTUBE",
        displayName: "YouTube",
        available: false,
        setup: setup({ requiresClientSecret: true, consoleUrl: "https://console.cloud.google.com" }),
      });

      it("asks for the secret Google requires", async () => {
        renderView([youtube]);

        await userEvent.click(screen.getByRole("button", { name: "Add keys" }));

        expect(screen.getByLabelText(/Client secret/)).toBeInTheDocument();
      });

      it("will not save without it, since the token exchange would fail later", async () => {
        renderView([youtube]);

        await userEvent.click(screen.getByRole("button", { name: "Add keys" }));
        await userEvent.type(screen.getByLabelText(/Client ID/), "an-id");

        expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
      });

      it("saves both once the secret is given", async () => {
        client.saveSetup.mockResolvedValue(setup({ requiresClientSecret: true }));
        renderView([youtube]);

        await userEvent.click(screen.getByRole("button", { name: "Add keys" }));
        await userEvent.type(screen.getByLabelText(/Client ID/), "an-id");
        await userEvent.type(screen.getByLabelText(/Client secret/), "a-secret");
        await userEvent.click(screen.getByRole("button", { name: "Save" }));

        await waitFor(() => {
          expect(client.saveSetup).toHaveBeenCalledWith("YOUTUBE", "an-id", "a-secret");
        });
      });

      it("keeps an already-saved secret when the field is left blank", async () => {
        client.saveSetup.mockResolvedValue(setup({ requiresClientSecret: true }));
        renderView([
          provider({
            id: "YOUTUBE",
            displayName: "YouTube",
            setup: setup({
              requiresClientSecret: true,
              clientSecretSet: true,
              clientId: "saved-id",
              source: "APP",
            }),
          }),
        ]);

        await userEvent.click(screen.getByRole("button", { name: "Edit keys" }));
        // The stored secret is never sent back to us, so an untouched field must mean
        // "keep it" rather than "wipe it".
        expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
        await userEvent.click(screen.getByRole("button", { name: "Save" }));

        await waitFor(() => {
          expect(client.saveSetup).toHaveBeenCalledWith("YOUTUBE", "saved-id", null);
        });
      });
    });

    it("does not offer a secret field to Spotify, which has none", async () => {
      renderView([provider({ id: "SPOTIFY", displayName: "Spotify", available: false })]);

      await userEvent.click(screen.getByRole("button", { name: "Add keys" }));

      expect(screen.queryByLabelText(/Client secret/)).not.toBeInTheDocument();
    });

    it("offers no setup at all for a service credentials cannot fix", () => {
      renderView([
        provider({
          id: "APPLE_MUSIC",
          displayName: "Apple Music",
          available: false,
          setupSupported: false,
          setup: null,
          unavailableReason: "Not supported yet.",
        }),
      ]);

      expect(screen.queryByRole("button", { name: /keys/ })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Unavailable" })).toBeDisabled();
    });

    it("can forget keys that were entered in the app", async () => {
      client.clearSetup.mockResolvedValue(setup());
      renderView([
        provider({
          id: "SPOTIFY",
          displayName: "Spotify",
          setup: setup({ clientId: "saved-id", source: "APP" }),
        }),
      ]);

      await userEvent.click(screen.getByRole("button", { name: "Edit keys" }));
      await userEvent.click(screen.getByRole("button", { name: /Forget these/ }));

      await waitFor(() => {
        expect(client.clearSetup).toHaveBeenCalledWith("SPOTIFY");
      });
    });

    it("says when the build's own credentials are in use", async () => {
      renderView([
        provider({
          id: "SPOTIFY",
          displayName: "Spotify",
          setup: setup({ clientId: "shipped-id", source: "ENVIRONMENT" }),
        }),
      ]);

      await userEvent.click(screen.getByRole("button", { name: "Edit keys" }));

      expect(screen.getByText(/build's configuration/)).toBeInTheDocument();
      // Nothing was entered in the app, so there is nothing to forget.
      expect(screen.queryByRole("button", { name: /Forget these/ })).not.toBeInTheDocument();
    });

    it("reports a save that the backend rejects", async () => {
      const { ApiError } = await import("../api/client");
      client.saveSetup.mockRejectedValue(
        new ApiError("YouTube also needs a client secret", { status: 400 }),
      );
      renderView([provider({ id: "SPOTIFY", displayName: "Spotify", available: false })]);

      await userEvent.click(screen.getByRole("button", { name: "Add keys" }));
      await userEvent.type(screen.getByLabelText(/Client ID/), "an-id");
      await userEvent.click(screen.getByRole("button", { name: "Save" }));

      await waitFor(() => {
        expect(screen.getByRole("alert")).toHaveTextContent("YouTube also needs a client secret");
      });
    });
  });

  describe("scraper services (no sign-in)", () => {
    const youtube = provider({
      id: "YOUTUBE",
      displayName: "YouTube",
      requiresAuthentication: false,
      setupSupported: false,
      setup: null,
    });

    it("shows a scraper service as ready, with no connect button", () => {
      renderView([youtube]);

      expect(screen.getByText(/Ready — no sign-in needed/)).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Connect" })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: /Import by URL/ })).toBeEnabled();
    });

    it("imports a playlist from a pasted URL", async () => {
      client.importPlaylistByUrl.mockResolvedValue({
        provider: "YOUTUBE",
        importedCount: 1,
        trackCount: 100,
        alreadyPresent: 0,
        unreadable: 0,
        imported: [],
      });
      renderView([youtube]);

      await userEvent.click(screen.getByRole("button", { name: /Import by URL/ }));
      await userEvent.type(
        screen.getByLabelText(/Playlist URL/),
        "https://www.youtube.com/playlist?list=PLabc",
      );
      await userEvent.click(screen.getByRole("button", { name: "Import" }));

      await waitFor(() => {
        expect(client.importPlaylistByUrl).toHaveBeenCalledWith(
          "YOUTUBE",
          "https://www.youtube.com/playlist?list=PLabc",
        );
      });
      expect(screen.getByRole("status")).toHaveTextContent(/Imported 1 playlist \(100 tracks\)/);
    });

    it("does not offer a scraper service a credentials form", async () => {
      renderView([youtube]);

      await userEvent.click(screen.getByRole("button", { name: /Import by URL/ }));

      expect(screen.queryByLabelText(/Client ID/)).not.toBeInTheDocument();
      expect(screen.getByLabelText(/Playlist URL/)).toBeInTheDocument();
    });
  });

  describe("extractor freshness", () => {
    it("says when an update has been downloaded", async () => {
      client.extractorStatus.mockResolvedValue({
        runningVersion: "0.26.3",
        latestVersion: "v0.27.0",
        updateDownloaded: true,
        updateAvailable: true,
      });
      renderView([]);

      await waitFor(() => {
        expect(screen.getByText(/applies when you restart/)).toBeInTheDocument();
      });
    });

    it("says up to date when there is nothing newer", async () => {
      client.extractorStatus.mockResolvedValue({
        runningVersion: "0.26.3",
        latestVersion: "v0.26.3",
        updateDownloaded: false,
        updateAvailable: false,
      });
      renderView([]);

      await waitFor(() => {
        expect(screen.getByText(/up to date/)).toBeInTheDocument();
      });
    });
  });

  describe("connecting", () => {
    it("opens the service's sign-in page and completes with the callback", async () => {
      client.beginAuthorization.mockResolvedValue("https://accounts.spotify.com/authorize?x=1");
      authorize.mockResolvedValue({ code: "the-code", state: "the-state" });
      client.completeAuthorization.mockResolvedValue({});
      renderView([provider({ id: "SPOTIFY", displayName: "Spotify" })]);

      await userEvent.click(screen.getByRole("button", { name: "Connect" }));

      await waitFor(() => {
        expect(client.completeAuthorization).toHaveBeenCalledWith(
          "SPOTIFY", "the-code", "the-state");
      });
      expect(authorize).toHaveBeenCalledWith(
        "SPOTIFY", "https://accounts.spotify.com/authorize?x=1");
      expect(onChanged).toHaveBeenCalled();
    });

    it("reports a refused sign-in without breaking the view", async () => {
      client.beginAuthorization.mockResolvedValue("https://accounts.spotify.com/authorize");
      authorize.mockRejectedValue(new Error("Authorization was refused: access_denied"));
      renderView([provider({ id: "SPOTIFY", displayName: "Spotify" })]);

      await userEvent.click(screen.getByRole("button", { name: "Connect" }));

      await waitFor(() => {
        expect(screen.getByRole("alert")).toBeInTheDocument();
      });
      expect(client.completeAuthorization).not.toHaveBeenCalled();
    });
  });

  describe("permissions the service did not grant", () => {
    /**
     * The failure this exists for: Spotify hands back a token without
     * playlist-read-private, search keeps working because it needs no scope, and
     * import 403s with nothing to indicate that reconnecting is the fix.
     */
    it("warns before the user hits the 403, and says what to do", () => {
      renderView([
        provider({
          id: "SPOTIFY",
          displayName: "Spotify",
          connected: true,
          accountLabel: "user@example.invalid",
          grantedScopes: ["user-read-email", "streaming"],
          missingScopes: ["playlist-read-private", "playlist-read-collaborative"],
        }),
      ]);

      const warning = screen.getByRole("status");
      expect(warning).toHaveTextContent(/did not grant/);
      expect(warning).toHaveTextContent(/playlist-read-private/);
      expect(warning).toHaveTextContent(/Disconnect and connect again/);
    });

    it("stays quiet when every needed permission was granted", () => {
      renderView([
        provider({
          id: "SPOTIFY",
          displayName: "Spotify",
          connected: true,
          accountLabel: "user@example.invalid",
          grantedScopes: ["playlist-read-private", "playlist-read-collaborative"],
          missingScopes: [],
        }),
      ]);

      expect(screen.queryByText(/did not grant/)).not.toBeInTheDocument();
    });

    it("says nothing about permissions for a service that is not connected", () => {
      renderView([
        provider({
          id: "SPOTIFY",
          displayName: "Spotify",
          connected: false,
          missingScopes: [],
        }),
      ]);

      expect(screen.queryByText(/did not grant/)).not.toBeInTheDocument();
    });
  });

  describe("connected services", () => {
    const connected = provider({
      id: "SPOTIFY",
      displayName: "Spotify",
      connected: true,
      accountLabel: "user@example.invalid",
    });

    it("shows the account and offers import and disconnect", () => {
      renderView([connected]);

      expect(screen.getByText("user@example.invalid")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /Import playlists/ })).toBeEnabled();
      expect(screen.getByRole("button", { name: /Disconnect/ })).toBeEnabled();
    });

    it("reports what an import did, including what it left alone", async () => {
      client.importPlaylists.mockResolvedValue({
        provider: "SPOTIFY",
        importedCount: 3,
        trackCount: 42,
        alreadyPresent: 2,
        imported: [],
      });
      renderView([connected]);

      await userEvent.click(screen.getByRole("button", { name: /Import playlists/ }));

      await waitFor(() => {
        expect(screen.getByRole("status")).toHaveTextContent(/Imported 3 playlists \(42 tracks\)/);
      });
      // The user needs to know their edited copies were not overwritten.
      expect(screen.getByRole("status")).toHaveTextContent(/2 already imported and left untouched/);
    });
  });
});
