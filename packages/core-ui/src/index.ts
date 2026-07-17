/** Public surface of the shared UI core, consumed by the platform shells. */

export { App } from "./App";
export type { AppProps } from "./App";
export { ApiClient, ApiError } from "./api/client";
export type { ApiClientOptions } from "./api/client";
export { Player } from "./player/Player";
export type { PlayerOptions } from "./player/Player";
export { SpotifyAdapter } from "./player/SpotifyAdapter";
export { DirectAudioAdapter } from "./player/DirectAudioAdapter";
export type { PlayerAdapter, PlayerState, PlayerStatus } from "./player/types";
export * from "./api/types";
export type { UnitedPlaylistsBridge } from "./bridge";
