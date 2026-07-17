import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { ApiClient } from "./api/client";
import type { ProviderId } from "./api/types";
import { Player } from "./player/Player";
import { SpotifyAdapter } from "./player/SpotifyAdapter";
import { DirectAudioAdapter } from "./player/DirectAudioAdapter";
import "./styles.css";

/**
 * Bootstraps the app.
 *
 * The backend's port and shared secret are not known until the Electron main
 * process has started the Java backend, so they arrive over the preload bridge
 * rather than being baked in at build time.
 */
async function bootstrap(): Promise<void> {
  const root = document.getElementById("root");
  if (!root) {
    throw new Error("No #root element to mount into");
  }

  const bridge = window.unitedPlaylists;
  if (!bridge) {
    root.innerHTML =
      "<p style='padding:2rem;font-family:system-ui'>This page must run inside the " +
      "UnitedPlaylists desktop app.</p>";
    return;
  }

  const { baseUrl, token } = await bridge.getBackendInfo();
  const client = new ApiClient({ baseUrl, token });

  const player = new Player({
    adapters: [
      // Spotify's SDK asks for a token repeatedly over its lifetime, so it is given
      // a function rather than a value.
      new SpotifyAdapter(async () => bridge.getSpotifyAccessToken()),
      // One adapter serves every scraper-backed service: YouTube and SoundCloud
      // both play a direct stream URL, resolved by the backend.
      new DirectAudioAdapter(),
    ],
    fetchTicket: (trackKey) => client.playbackTicket(trackKey),
  });

  const authorize = (provider: ProviderId, url: string) => bridge.authorize(provider, url);
  const openExternal = (url: string) => void bridge.openExternal(url);

  createRoot(root).render(
    <StrictMode>
      <App
        client={client}
        player={player}
        authorize={authorize}
        openExternal={openExternal}
      />
    </StrictMode>,
  );

  window.addEventListener("beforeunload", () => {
    void player.dispose();
  });
}

void bootstrap();
