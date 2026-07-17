import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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

/** How long the "added to playlist" confirmation stays up before fading out. */
const ADDED_NOTICE_MS = 2500;

/**
 * Ways to order search results. "relevance" keeps the backend's own ranking
 * (best matches first); the rest are client-side re-sorts of that same set.
 * There is deliberately no "date" option: search results carry no date — only
 * imported playlist entries do.
 */
type SortKey = "relevance" | "title" | "artist" | "service" | "duration";

/** Ascending or descending; ignored for "relevance", which has no direction. */
type SortDirection = "asc" | "desc";

const SORT_LABELS: Readonly<Record<SortKey, string>> = {
  relevance: "Relevance",
  title: "Title",
  artist: "Artist",
  service: "Service",
  duration: "Duration",
};

/**
 * The direction toggle's label, phrased for the field it applies to — "A–Z"
 * reads naturally for text but not for duration, where "Shortest/Longest" is
 * clearer.
 */
function directionLabel(sort: SortKey, direction: SortDirection): string {
  if (sort === "duration") {
    return direction === "asc" ? "Shortest first" : "Longest first";
  }
  return direction === "asc" ? "A–Z" : "Z–A";
}

/**
 * The ascending comparator for a field. Descending is this negated, so each
 * comparator is written once. Case-insensitive and locale-aware for text;
 * tracks with no duration sort last (ascending).
 */
function ascendingComparator(sort: Exclude<SortKey, "relevance">): (a: Track, b: Track) => number {
  switch (sort) {
    case "title":
      return (a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: "base" });
    case "artist":
      return (a, b) => a.artistLine.localeCompare(b.artistLine, undefined, { sensitivity: "base" });
    case "service":
      return (a, b) => PROVIDER_LABELS[a.provider].localeCompare(PROVIDER_LABELS[b.provider]);
    case "duration":
      return (a, b) => (a.durationMs ?? Infinity) - (b.durationMs ?? Infinity);
  }
}

/**
 * Returns results ordered by the chosen key and direction. "relevance" is the
 * identity order, so the backend's ranking is preserved (direction does not
 * apply). The others sort a copy — never mutating the response — flipping the
 * ascending comparator's sign for a descending sort.
 */
function sortResults(
  results: readonly Track[],
  sort: SortKey,
  direction: SortDirection,
): readonly Track[] {
  if (sort === "relevance") {
    return results;
  }
  const ascending = ascendingComparator(sort);
  const sign = direction === "asc" ? 1 : -1;
  return [...results].sort((a, b) => sign * ascending(a, b));
}

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
  const [addedNotice, setAddedNotice] = useState<string | null>(null);
  const [sort, setSort] = useState<SortKey>("relevance");
  const [direction, setDirection] = useState<SortDirection>("asc");

  // Lets a slow earlier search be discarded when a newer one has already returned.
  const requestSeq = useRef(0);
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  // Clear the pending confirmation timer if the view unmounts mid-notice.
  useEffect(() => () => clearTimeout(noticeTimer.current), []);

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
      // Confirm the add: without this there was no sign the track landed anywhere.
      clearTimeout(noticeTimer.current);
      setAddedNotice(`Added “${track.title}” to ${updated.name}`);
      noticeTimer.current = setTimeout(() => setAddedNotice(null), ADDED_NOTICE_MS);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Could not add the track");
    } finally {
      setAddingKey(null);
    }
  };

  const results = useMemo(
    () => sortResults(response?.results ?? [], sort, direction),
    [response, sort, direction],
  );

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

      {(response?.results.length ?? 0) > 0 && (
        <div className="search-toolbar">
          <label className="sort-control">
            <span>Sort by</span>
            <select
              className="sort-select"
              value={sort}
              onChange={(event) => setSort(event.target.value as SortKey)}
            >
              {(Object.keys(SORT_LABELS) as SortKey[]).map((key) => (
                <option key={key} value={key}>
                  {SORT_LABELS[key]}
                </option>
              ))}
            </select>
          </label>

          {/* Relevance is the backend's own ranking, which has no ascending or
              descending sense, so the toggle only appears for the real fields. */}
          {sort !== "relevance" && (
            <button
              type="button"
              className="sort-direction"
              aria-label={`Sort direction: ${directionLabel(sort, direction)}`}
              title={`Sort direction: ${directionLabel(sort, direction)}`}
              onClick={() => setDirection((d) => (d === "asc" ? "desc" : "asc"))}
            >
              <span aria-hidden="true">{direction === "asc" ? "↑" : "↓"}</span>
              {directionLabel(sort, direction)}
            </button>
          )}
        </div>
      )}

      {/* Transient confirmation that a track was added — a floating toast, polite
          so a screen reader announces it without stealing focus. */}
      {addedNotice && (
        <div className="toast" role="status" aria-live="polite">
          <span className="toast-icon" aria-hidden="true">
            ✓
          </span>
          {addedNotice}
        </div>
      )}

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
