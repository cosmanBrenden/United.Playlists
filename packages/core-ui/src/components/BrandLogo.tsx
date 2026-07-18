/**
 * The app wordmark: UNITED · emblem · PLAYLISTS.
 *
 * The emblem riffs on the UN's olive-branch wreath, but the polar world map at
 * its centre is swapped for a vinyl record — "united playlists" rather than
 * united nations. Drawn as inline SVG (no external asset) so it inherits theme
 * colours via CSS custom properties and stays crisp at any size.
 */

/** One olive branch, arcing up the left side; the right side is its mirror. */
function OliveBranch(): JSX.Element {
  // Leaves placed along the stem, each rotated to point outward from the centre.
  const leaves: ReadonlyArray<readonly [number, number, number]> = [
    [30, 74, 130],
    [23, 64, 153],
    [20, 53, 174],
    [21, 43, -166],
    [25, 33, -146],
    [32, 25, -126],
  ];
  return (
    <g>
      <path
        d="M 47 82 C 30 74, 20 58, 21 42 C 22 32, 30 24, 41 20"
        fill="none"
        strokeWidth="1.4"
        strokeLinecap="round"
      />
      {leaves.map(([cx, cy, angle]) => (
        <ellipse
          key={`${cx}-${cy}`}
          cx={cx}
          cy={cy}
          rx="5"
          ry="2.2"
          transform={`rotate(${angle} ${cx} ${cy})`}
        />
      ))}
    </g>
  );
}

/** Grooves of the record, from the outer edge inward. */
const GROOVE_RADII = [21, 18, 15, 12] as const;

export function BrandLogo(): JSX.Element {
  return (
    <h1 className="brand">
      <span className="word">United</span>

      {/* The span carries the soft aura (a blurred ::before); SVG elements can't
          host pseudo-elements, so the glow lives on the wrapper. */}
      <span className="logo-mark">
        <svg
          className="logo-icon"
          /* Cropped to the artwork's own bounds (not the full 0–100 box) so the
             emblem fills its slot between the words instead of floating small in
             empty margin. */
          viewBox="12 14 76 76"
          role="img"
          aria-label="United Playlists"
        >
        {/* Vinyl record in place of the UN world map. */}
        <circle cx="50" cy="50" r="25" fill="#141418" />
        <circle cx="50" cy="50" r="25" fill="none" stroke="var(--logo-wreath)" strokeWidth="0.8" />
        {GROOVE_RADII.map((r) => (
          <circle
            key={r}
            cx="50"
            cy="50"
            r={r}
            fill="none"
            stroke="rgba(255,255,255,0.12)"
            strokeWidth="0.6"
          />
        ))}
        <circle cx="50" cy="50" r="8.5" fill="var(--accent)" />
        <circle cx="50" cy="50" r="1.8" fill="var(--bg-raised)" />

        {/* Olive wreath: one branch, mirrored across the vertical centre line. */}
        <g fill="var(--logo-wreath)" stroke="var(--logo-wreath)">
          <OliveBranch />
          <g transform="translate(100,0) scale(-1,1)">
            <OliveBranch />
          </g>
        </g>
        </svg>
      </span>

      <span className="word">Playlists</span>
    </h1>
  );
}
