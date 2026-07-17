import type { ProviderId } from "../api/types";
import { PROVIDER_LABELS } from "../api/types";

/**
 * Colours are each service's own brand colour, so the badge is recognisable at a
 * glance rather than needing to be read.
 */
const PROVIDER_COLOURS: Readonly<Record<ProviderId, string>> = {
  SPOTIFY: "#1DB954",
  YOUTUBE: "#FF0000",
  APPLE_MUSIC: "#FC3C44",
  SOUNDCLOUD: "#FF5500",
};

export interface ServiceBadgeProps {
  readonly provider: ProviderId;
}

/**
 * Says which service something came from (spec 4).
 *
 * Carries a text label rather than colour alone: colour-blind users get nothing
 * from a green-vs-red dot, and this is load-bearing information.
 */
export function ServiceBadge({ provider }: ServiceBadgeProps): JSX.Element {
  return (
    <span
      className="service-badge"
      data-testid={`badge-${provider}`}
      style={{ backgroundColor: PROVIDER_COLOURS[provider] }}
    >
      {PROVIDER_LABELS[provider]}
    </span>
  );
}
