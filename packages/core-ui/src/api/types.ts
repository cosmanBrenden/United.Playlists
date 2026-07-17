/**
 * Types mirroring the backend's API payloads.
 *
 * Hand-written rather than generated from the OpenAPI document: the surface is
 * small, and a generator would be another build step to keep alive. If this grows,
 * generating from `/v3/api-docs` is the escape hatch.
 */

export type ProviderId = "SPOTIFY" | "YOUTUBE" | "APPLE_MUSIC" | "SOUNDCLOUD";

/** Mirrors the backend's PlaybackMethod. Each value maps to one player adapter. */
export type PlaybackMethod =
  | "SPOTIFY_WEB_SDK"
  | "APPLE_MUSICKIT_JS"
  /** Scraper-backed services (YouTube, SoundCloud): a direct stream URL in an <audio> element. */
  | "DIRECT_AUDIO";

export interface Track {
  /** Stable key, e.g. "SPOTIFY:4iV5W9uYEdYUVa79Axb7Rh". */
  readonly key: string;
  readonly provider: ProviderId;
  readonly providerTrackId: string;
  readonly title: string;
  readonly artists: readonly string[];
  readonly artistLine: string;
  readonly album: string | null;
  readonly durationMs: number | null;
  readonly artworkUrl: string | null;
  readonly playable: boolean;
}

export interface PlaylistEntry {
  readonly id: string;
  readonly position: number;
  readonly track: Track;
  readonly addedAt: string;
}

export interface PlaylistOrigin {
  readonly provider: ProviderId;
  readonly originPlaylistId: string;
  readonly importedAt: string;
}

export interface Playlist {
  readonly id: string;
  readonly name: string;
  readonly description: string | null;
  /** Null for playlists created in this app. */
  readonly origin: PlaylistOrigin | null;
  readonly providersUsed: readonly ProviderId[];
  readonly trackCount: number;
  /** Empty in list responses; populated when fetching one playlist. */
  readonly entries: readonly PlaylistEntry[];
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ProviderFailure {
  readonly provider: ProviderId;
  readonly kind: string;
  readonly message: string;
  readonly requiresReconnect: boolean;
}

export interface SearchResponse {
  readonly query: string;
  readonly results: readonly Track[];
  readonly byProvider: Partial<Record<ProviderId, readonly Track[]>>;
  /** Services that failed. Non-empty means the results are incomplete. */
  readonly failures: readonly ProviderFailure[];
  readonly partial: boolean;
}

/**
 * Instructions for playing a track. Deliberately carries no audio: the backend
 * cannot legally serve it, so the client's SDK does the playing.
 */
export interface PlaybackTicket {
  readonly trackKey: string;
  readonly provider: ProviderId;
  readonly method: PlaybackMethod;
  readonly params: Readonly<Record<string, string>>;
}

/** Where a service's credentials came from. */
export type SetupSource = "APP" | "ENVIRONMENT" | "NONE";

export interface ProviderSetup {
  /** The client ID in use. Not secret — a PKCE client ID ships in the app. */
  readonly clientId: string | null;
  /** Whether a secret is stored. The secret itself is never sent to the client. */
  readonly clientSecretSet: boolean;
  /** Whether this service needs a secret at all. Spotify does not; Google does. */
  readonly requiresClientSecret: boolean;
  readonly source: SetupSource;
  /** The exact redirect URI the user must register with the service. */
  readonly redirectUri: string;
  /** Where to create the credentials. */
  readonly consoleUrl: string | null;
  /** Ordered setup steps, written by the provider that knows its own console. */
  readonly instructions: readonly string[];
}

export interface ProviderInfo {
  readonly id: ProviderId;
  readonly displayName: string;
  /** False when the service cannot be connected: not implemented, or not configured. */
  readonly available: boolean;
  /**
   * Why it is unavailable, phrased for the user; null when available.
   *
   * Comes from the backend rather than being inferred here, because the reasons
   * differ per service and only the provider knows which applies.
   */
  readonly unavailableReason: string | null;
  readonly connected: boolean;
  readonly accountLabel: string | null;
  /** False for services where entering credentials would not help, e.g. Apple Music. */
  readonly setupSupported: boolean;
  /** Null when setup is unsupported. */
  readonly setup: ProviderSetup | null;
  /**
   * False for the scraper-backed services (YouTube, SoundCloud): they need no sign-in,
   * so the UI shows them as ready and imports by URL instead of by connecting.
   */
  readonly requiresAuthentication: boolean;
  /** Permissions the service actually granted. Empty when not connected. */
  readonly grantedScopes: readonly string[];
  /**
   * Permissions this service needs but did not grant.
   *
   * Non-empty means importing will fail with a bare 403 until the user reconnects —
   * so this is worth saying loudly rather than waiting for them to hit it.
   */
  readonly missingScopes: readonly string[];
}

export interface ImportSummary {
  readonly provider: ProviderId;
  readonly importedCount: number;
  readonly trackCount: number;
  readonly alreadyPresent: number;
  /**
   * Playlists the service listed but would not let us read.
   *
   * On Spotify this is everything the user follows rather than owns — Discover
   * Weekly, Release Radar, other people's playlists. Often most of the list, so it
   * needs saying, or importing 3 of 40 looks broken.
   */
  readonly unreadable: number;
  readonly imported: readonly Playlist[];
}

export interface ApiErrorBody {
  readonly error: string;
  readonly message: string;
  readonly provider: string | null;
  readonly requiresReconnect: boolean;
  readonly details: readonly string[];
  readonly timestamp: string;
}

/** Display names, for badges on search results. */
export const PROVIDER_LABELS: Readonly<Record<ProviderId, string>> = {
  SPOTIFY: "Spotify",
  YOUTUBE: "YouTube",
  APPLE_MUSIC: "Apple Music",
  SOUNDCLOUD: "SoundCloud",
};
