import type {
  ApiErrorBody,
  ImportSummary,
  PlaybackTicket,
  Playlist,
  ProviderId,
  ProviderInfo,
  ProviderSetup,
  SearchResponse,
  Track,
} from "./types";

/** Header carrying the shared secret the local backend requires. */
const TOKEN_HEADER = "X-UnitedPlaylists-Token";

export interface ApiClientOptions {
  /** Backend origin, e.g. "http://127.0.0.1:8421". The port is chosen at runtime. */
  readonly baseUrl: string;
  /** Shared secret minted by the Electron main process at startup. */
  readonly token: string;
  /** Injectable for tests. */
  readonly fetchFn?: typeof fetch;
}

/**
 * A failed API call.
 *
 * `requiresReconnect` is carried through from the backend because it changes what
 * the UI should offer: a expired Spotify token needs a "Reconnect" button, while a
 * rate-limited YouTube needs "try again later".
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly provider: string | null;
  readonly requiresReconnect: boolean;
  readonly details: readonly string[];

  constructor(
    message: string,
    options: {
      status: number;
      code?: string;
      provider?: string | null;
      requiresReconnect?: boolean;
      details?: readonly string[];
      cause?: unknown;
    },
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = "ApiError";
    this.status = options.status;
    this.code = options.code ?? "unknown";
    this.provider = options.provider ?? null;
    this.requiresReconnect = options.requiresReconnect ?? false;
    this.details = options.details ?? [];
  }

  /** True when nothing reached the backend at all. */
  get isNetworkFailure(): boolean {
    return this.status === 0;
  }
}

/**
 * Typed client for the local backend.
 *
 * Every method rejects with {@link ApiError}; no other error type escapes, so
 * callers have exactly one thing to catch.
 */
export class ApiClient {
  readonly #baseUrl: string;
  readonly #token: string;
  readonly #fetch: typeof fetch;

  constructor(options: ApiClientOptions) {
    // Trailing slash would produce "//api/v1/..." on join.
    this.#baseUrl = options.baseUrl.replace(/\/+$/, "");
    this.#token = options.token;
    this.#fetch = options.fetchFn ?? globalThis.fetch.bind(globalThis);
  }

  async listPlaylists(): Promise<readonly Playlist[]> {
    return this.#request<readonly Playlist[]>("GET", "/api/v1/playlists");
  }

  async getPlaylist(id: string): Promise<Playlist> {
    return this.#request<Playlist>("GET", `/api/v1/playlists/${encodeURIComponent(id)}`);
  }

  async createPlaylist(name: string, description: string | null): Promise<Playlist> {
    return this.#request<Playlist>("POST", "/api/v1/playlists", { name, description });
  }

  async updatePlaylist(id: string, name: string, description: string | null): Promise<Playlist> {
    return this.#request<Playlist>("PUT", `/api/v1/playlists/${encodeURIComponent(id)}`, {
      name,
      description,
    });
  }

  /**
   * Adds a track to a playlist.
   *
   * The whole {@link Track} is sent rather than just its key so the backend can
   * cache the display metadata; that is what lets a playlist render without a
   * per-track API call.
   */
  async addTrack(playlistId: string, track: Track): Promise<Playlist> {
    return this.#request<Playlist>(
      "POST",
      `/api/v1/playlists/${encodeURIComponent(playlistId)}/tracks`,
      {
        trackKey: track.key,
        title: track.title,
        artists: track.artists,
        album: track.album,
        durationMs: track.durationMs,
        artworkUrl: track.artworkUrl,
      },
    );
  }

  async removeTrack(playlistId: string, position: number): Promise<Playlist> {
    return this.#request<Playlist>(
      "DELETE",
      `/api/v1/playlists/${encodeURIComponent(playlistId)}/tracks/${position}`,
    );
  }

  async moveTrack(playlistId: string, from: number, to: number): Promise<Playlist> {
    return this.#request<Playlist>(
      "POST",
      `/api/v1/playlists/${encodeURIComponent(playlistId)}/tracks/move`,
      { from, to },
    );
  }

  async deletePlaylist(id: string): Promise<void> {
    await this.#request<void>("DELETE", `/api/v1/playlists/${encodeURIComponent(id)}`);
  }

  /**
   * Searches every connected service.
   *
   * Resolves even when some services failed: check `partial` and `failures`. It
   * only rejects if the request itself failed.
   */
  async search(query: string, limit = 20): Promise<SearchResponse> {
    const params = new URLSearchParams({ q: query, limit: String(limit) });
    return this.#request<SearchResponse>("GET", `/api/v1/search?${params}`);
  }

  async listProviders(): Promise<readonly ProviderInfo[]> {
    return this.#request<readonly ProviderInfo[]>("GET", "/api/v1/connections/providers");
  }

  async getSetup(provider: ProviderId): Promise<ProviderSetup> {
    return this.#request<ProviderSetup>("GET", `/api/v1/connections/${provider}/setup`);
  }

  /**
   * Saves credentials the user entered. Takes effect immediately — no restart.
   *
   * @param clientSecret null for services that need none, like Spotify
   */
  async saveSetup(
    provider: ProviderId,
    clientId: string,
    clientSecret: string | null,
  ): Promise<ProviderSetup> {
    return this.#request<ProviderSetup>("PUT", `/api/v1/connections/${provider}/setup`, {
      clientId,
      clientSecret,
    });
  }

  /** Forgets app-entered credentials, reverting to whatever the build shipped. */
  async clearSetup(provider: ProviderId): Promise<ProviderSetup> {
    return this.#request<ProviderSetup>("DELETE", `/api/v1/connections/${provider}/setup`);
  }

  async beginAuthorization(provider: ProviderId): Promise<string> {
    const body = await this.#request<{ authorizationUrl: string }>(
      "POST",
      `/api/v1/connections/${provider}/authorize`,
    );
    return body.authorizationUrl;
  }

  async completeAuthorization(
    provider: ProviderId,
    code: string,
    state: string,
  ): Promise<ProviderInfo> {
    return this.#request<ProviderInfo>("POST", `/api/v1/connections/${provider}/callback`, {
      code,
      state,
    });
  }

  async disconnect(provider: ProviderId): Promise<void> {
    await this.#request<void>("DELETE", `/api/v1/connections/${provider}`);
  }

  async importPlaylists(provider: ProviderId): Promise<ImportSummary> {
    return this.#request<ImportSummary>("POST", `/api/v1/connections/${provider}/import`);
  }

  /**
   * Imports one playlist from its public URL.
   *
   * The import path for the scraper-backed services, which have no account to
   * enumerate.
   */
  async importPlaylistByUrl(provider: ProviderId, url: string): Promise<ImportSummary> {
    return this.#request<ImportSummary>("POST", `/api/v1/connections/${provider}/import-url`, {
      url,
    });
  }

  async playbackTicket(trackKey: string): Promise<PlaybackTicket> {
    const params = new URLSearchParams({ trackKey });
    return this.#request<PlaybackTicket>("GET", `/api/v1/playback/ticket?${params}`);
  }

  async #request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { [TOKEN_HEADER]: this.#token };
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
    }

    const request = new Request(`${this.#baseUrl}${path}`, {
      method,
      headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });

    let response: Response;
    try {
      response = await this.#fetch(request);
    } catch (cause) {
      // fetch only rejects for transport failures. In practice this means the
      // backend process died or has not finished starting.
      throw new ApiError("Could not reach the UnitedPlaylists backend", {
        status: 0,
        code: "network_error",
        cause,
      });
    }

    if (!response.ok) {
      throw await this.#toApiError(response);
    }
    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }

  async #toApiError(response: Response): Promise<ApiError> {
    let parsed: Partial<ApiErrorBody> | null = null;
    try {
      parsed = (await response.json()) as Partial<ApiErrorBody>;
    } catch {
      // A proxy or a crash can produce a non-JSON error body. Falling over while
      // building the error would hide the real failure.
      parsed = null;
    }
    return new ApiError(parsed?.message ?? `Request failed with HTTP ${response.status}`, {
      status: response.status,
      code: parsed?.error ?? "http_error",
      provider: parsed?.provider ?? null,
      requiresReconnect: parsed?.requiresReconnect ?? false,
      details: parsed?.details ?? [],
    });
  }
}
