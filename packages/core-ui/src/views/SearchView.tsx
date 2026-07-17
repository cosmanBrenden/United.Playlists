import { useCallback, useEffect, useRef, useState } from "react";
import type { ApiClient } from "../api/client";
import { ApiError } from "../api/client";
import type { Playlist, SearchResponse, Track } from "../api/types";
import { PROVIDER_LABELS } from "../api/types";
import { ServiceBadge } from "../components/ServiceBadge";

/**
 * YouTube search costs 100 quota units against a 10,000/day allowance — roughly
 * 100 searches per day. Searching per keystroke would exhaust that in one session,
 * so typing is debounced rather than sent live.
 */
const SEARCH_DEBOUNCE_MS = 450;

export interface SearchViewProps {
  readonly client: ApiClient;
  readonly playlists: readonly Playlist[];
  readonly onPlay: (track: Track, results: readonly Track[]) => void;
  readonly onTrackAdded?: (playlist: Playlist) => void;
}

export function SearchView({
  client,
  playlists,
  onPlay,
  onTrackAdded,
}: SearchViewProps): JSX.Element {
  const [query, setQuery] = useState("");
  const [response, setResponse] = useState<SearchResponse | null>(null);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [addingKey, setAddingKey] = useState<string | null>(null);

  // Lets a slow earlier search be discarded when a newer one has already returned.
  const requestSeq = useRef(0);

  const runSearch = useCallback(
    async (text: string): Promise<void> => {
      const trimmed = text.trim();
      if (!trimmed) {
        setResponse(null);
        setError(null);
        return;
      }
      const seq = ++requestSeq.current;
      setSearching(true);
      setError(null);
      try {
        const result = await client.search(trimmed);
        // Out-of-order responses would otherwise show results for a query the user
        // has already typed past.
        if (seq === requestSeq.current) {
          setResponse(result);
        }
      } catch (cause) {
        if (seq === requestSeq.current) {
          setError(cause instanceof ApiError ? cause.message : "Search failed");
          setResponse(null);
        }
      } finally {
        if (seq === requestSeq.current) {
          setSearching(false);
        }
      }
    },
    [client],
  );

  useEffect(() => {
    const timer = setTimeout(() => {
      void runSearch(query);
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [query, runSearch]);

  const addToPlaylist = async (track: Track, playlistId: string): Promise<void> => {
    setAddingKey(track.key);
    try {
      const updated = await client.addTrack(playlistId, track);
      onTrackAdded?.(updated);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Could not add the track");
    } finally {
      setAddingKey(null);
    }
  };

  const results = response?.results ?? [];

  return (
    <section className="view search-view">
      <header className="view-header">
        <h2>Search</h2>
        <input
          type="search"
          className="search-input"
          placeholder="Search every connected service…"
          aria-label="Search every connected service"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </header>

      {searching && <p className="status">Searching…</p>}
      {error && <p className="status error" role="alert">{error}</p>}

      {/* A service being down must not look like "no results". */}
      {response?.partial && (
        <div className="status warning" role="status">
          <strong>Some services didn&apos;t respond.</strong> These results are incomplete.
          <ul>
            {response.failures.map((failure) => (
              <li key={failure.provider}>
                {PROVIDER_LABELS[failure.provider]}: {failure.message}
                {failure.requiresReconnect && " — reconnect it in Services."}
              </li>
            ))}
          </ul>
        </div>
      )}

      {response && results.length === 0 && !searching && (
        <p className="status">No results for “{response.query}”.</p>
      )}

      <ul className="track-list">
        {results.map((track) => (
          <li key={track.key} className="track-row">
            {track.artworkUrl ? (
              <img src={track.artworkUrl} alt="" className="artwork" loading="lazy" />
            ) : (
              <div className="artwork artwork-placeholder" aria-hidden="true" />
            )}

            <div className="track-meta">
              <span className="track-title">{track.title}</span>
              <span className="track-artist">{track.artistLine}</span>
            </div>

            {/* Spec 4: every result states where it came from. */}
            <ServiceBadge provider={track.provider} />

            <button
              type="button"
              className="play-button"
              onClick={() => onPlay(track, results)}
              disabled={!track.playable}
              title={track.playable ? "Play" : "Not playable on your account"}
              aria-label={`Play ${track.title}`}
            >
              ▶
            </button>

            {playlists.length > 0 && (
              <select
                className="add-select"
                aria-label={`Add ${track.title} to a playlist`}
                value=""
                disabled={addingKey === track.key}
                onChange={(event) => {
                  if (event.target.value) {
                    void addToPlaylist(track, event.target.value);
                  }
                }}
              >
                <option value="">Add to…</option>
                {playlists.map((playlist) => (
                  <option key={playlist.id} value={playlist.id}>
                    {playlist.name}
                  </option>
                ))}
              </select>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
