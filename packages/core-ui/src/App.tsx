import { useCallback, useEffect, useMemo, useState } from "react";
import type { ApiClient } from "./api/client";
import { ApiError } from "./api/client";
import type { Playlist, ProviderId, ProviderInfo, Track } from "./api/types";
import { CreatePlaylistDialog } from "./components/CreatePlaylistDialog";
import { PlayerBar } from "./components/PlayerBar";
import { QueuePanel } from "./components/QueuePanel";
import { Player } from "./player/Player";
import type { PlayerState } from "./player/types";
import { ConnectionsView } from "./views/ConnectionsView";
import { PlaylistView } from "./views/PlaylistView";
import { SearchView } from "./views/SearchView";

/** How often the progress position is refreshed from the live SDK. */
const POSITION_POLL_MS = 1000;

export interface AppProps {
  readonly client: ApiClient;
  readonly player: Player;
  readonly authorize: (provider: ProviderId, url: string) => Promise<{ code: string; state: string }>;
  /** Opens an https link in the user's browser. */
  readonly openExternal?: ((url: string) => void) | undefined;
}

type Tab = "playlists" | "search" | "services";

export function App({ client, player, authorize, openExternal }: AppProps): JSX.Element {
  const [tab, setTab] = useState<Tab>("playlists");
  const [playlists, setPlaylists] = useState<readonly Playlist[]>([]);
  const [providers, setProviders] = useState<readonly ProviderInfo[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selected, setSelected] = useState<Playlist | null>(null);
  const [playerState, setPlayerState] = useState<PlayerState>(player.getState());
  const [error, setError] = useState<string | null>(null);
  const [queueOpen, setQueueOpen] = useState(false);
  const [creating, setCreating] = useState(false);

  const refreshPlaylists = useCallback(async (): Promise<void> => {
    try {
      setPlaylists(await client.listPlaylists());
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Could not load playlists");
    }
  }, [client]);

  const refreshProviders = useCallback(async (): Promise<void> => {
    try {
      setProviders(await client.listProviders());
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Could not load services");
    }
  }, [client]);

  useEffect(() => {
    void refreshPlaylists();
    void refreshProviders();
  }, [refreshPlaylists, refreshProviders]);

  useEffect(() => player.subscribe(setPlayerState), [player]);

  // The list view carries no entries, so the full playlist is fetched on selection.
  useEffect(() => {
    if (!selectedId) {
      setSelected(null);
      return;
    }
    let cancelled = false;
    void client
      .getPlaylist(selectedId)
      .then((playlist) => {
        if (!cancelled) setSelected(playlist);
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof ApiError ? cause.message : "Could not load the playlist");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [client, selectedId]);

  // Only poll while something is actually playing; a timer running against an idle
  // player is pure wasted wakeups.
  useEffect(() => {
    if (playerState.status !== "playing") {
      return;
    }
    const timer = setInterval(() => void player.refreshProgress(), POSITION_POLL_MS);
    return () => clearInterval(timer);
  }, [player, playerState.status]);

  const playTrack = useCallback(
    (track: Track, queue: readonly Track[]) => {
      const index = queue.findIndex((candidate) => candidate.key === track.key);
      player.setQueue(queue, index < 0 ? 0 : index);
      void player.play(track);
    },
    [player],
  );

  const shufflePlay = useCallback(
    (tracks: readonly Track[]) => {
      void player.playShuffled(tracks);
    },
    [player],
  );

  // The dialog owns the create call so it can surface a failure inline and keep what
  // the user typed; App only refreshes and selects the result on success.
  const createPlaylist = useCallback(
    async (name: string, description: string | null): Promise<void> => {
      const created = await client.createPlaylist(name, description);
      setCreating(false);
      await refreshPlaylists();
      setTab("playlists");
      setSelectedId(created.id);
    },
    [client, refreshPlaylists],
  );

  const anyConnected = useMemo(() => providers.some((p) => p.connected), [providers]);

  return (
    <div className="app">
      <nav className="sidebar">
        <h1 className="brand">United.Playlists</h1>

        <div className="tabs">
          <button type="button" onClick={() => setTab("playlists")} aria-pressed={tab === "playlists"}>
            Playlists
          </button>
          <button type="button" onClick={() => setTab("search")} aria-pressed={tab === "search"}>
            Search
          </button>
          <button type="button" onClick={() => setTab("services")} aria-pressed={tab === "services"}>
            Services
          </button>
        </div>

        {tab === "playlists" && (
          <>
            <button type="button" className="new-playlist" onClick={() => setCreating(true)}>
              + New playlist
            </button>
            <ul className="playlist-list">
              {playlists.map((playlist) => (
                <li key={playlist.id}>
                  <button
                    type="button"
                    onClick={() => setSelectedId(playlist.id)}
                    aria-pressed={selectedId === playlist.id}
                  >
                    {playlist.name}
                    <span className="count">{playlist.trackCount}</span>
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
      </nav>

      <main className="content">
        {error && (
          <p className="status error" role="alert">
            {error}
          </p>
        )}

        {/* Nothing works until a service is connected, so say that plainly rather
            than showing empty views. */}
        {!anyConnected && providers.length > 0 && tab !== "services" && (
          <p className="status warning">
            No services connected yet. Open <strong>Services</strong> to connect Spotify or
            YouTube.
          </p>
        )}

        {tab === "playlists" &&
          (selected ? (
            <PlaylistView
              client={client}
              playlist={selected}
              onChanged={(updated) => {
                setSelected(updated);
                void refreshPlaylists();
              }}
              onDeleted={() => {
                setSelectedId(null);
                void refreshPlaylists();
              }}
              onPlay={playTrack}
              onShufflePlay={shufflePlay}
            />
          ) : (
            <p className="status">Pick a playlist, or create one.</p>
          ))}

        {tab === "search" && (
          <SearchView
            client={client}
            playlists={playlists}
            onPlay={playTrack}
            onTrackAdded={() => void refreshPlaylists()}
          />
        )}

        {tab === "services" && (
          <ConnectionsView
            client={client}
            providers={providers}
            authorize={authorize}
            openExternal={openExternal}
            onChanged={() => {
              void refreshProviders();
              void refreshPlaylists();
            }}
          />
        )}
      </main>

      {queueOpen && (
        <QueuePanel
          queue={playerState.queue}
          currentIndex={playerState.queueIndex}
          onClose={() => setQueueOpen(false)}
          onJump={(index) => void player.playQueueItem(index)}
          onRemove={(index) => player.removeFromQueue(index)}
          onMove={(from, to) => player.moveInQueue(from, to)}
        />
      )}

      <PlayerBar
        state={playerState}
        onToggle={() => void player.toggle()}
        onNext={() => void player.next()}
        onPrevious={() => void player.previous()}
        onVolume={(volume) => void player.setVolume(volume)}
        onSeek={(positionMs) => void player.seek(positionMs)}
        onToggleShuffle={() => player.setShuffle(!playerState.shuffle)}
        onToggleQueue={() => setQueueOpen((open) => !open)}
        queueOpen={queueOpen}
      />

      {creating && (
        <CreatePlaylistDialog onCancel={() => setCreating(false)} onCreate={createPlaylist} />
      )}
    </div>
  );
}
