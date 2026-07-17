/**
 * Pure helpers for validating the system Java runtime.
 *
 * Kept apart from main.js so they can be unit-tested without importing Electron:
 * main.js owns the process spawning and the error dialog, this module owns the
 * version arithmetic and the user-facing wording.
 */

/** The oldest Java the backend supports; Spring Boot 3.5 needs 17+, our code uses 21 features. */
export const MINIMUM_JAVA_VERSION = 21;

/**
 * Extracts the major version from `java -version` output.
 *
 * Handles both the modern scheme (`"21.0.11"` -> 21) and the legacy one
 * (`"1.8.0_401"` -> 8), returning null if no version string is present.
 *
 * @param {string} output  combined stdout/stderr from `java -version`
 * @returns {number | null}
 */
export function parseJavaMajor(output) {
  const match = /version "(\d+)(?:\.(\d+))?[^"]*"/.exec(output);
  if (!match) {
    return null;
  }
  const first = Number(match[1]);
  // Legacy "1.x" versions encode the real major in the second component.
  return first === 1 ? Number(match[2] ?? 0) : first;
}

/** The message shown when no `java` is on PATH. */
export function javaMissingMessage() {
  return (
    `Java ${MINIMUM_JAVA_VERSION} is required but no "java" was found on your PATH.\n\n` +
    "Install a Java 21 runtime (for example Eclipse Temurin from adoptium.net), " +
    "then start UnitedPlaylists again."
  );
}

/**
 * Decides whether a detected Java major version is acceptable.
 *
 * @param {number | null} major  as returned by {@link parseJavaMajor}
 * @returns {string | null}  an error message if too old, or null if usable
 *   (including the unparseable case, which is left for the backend to try).
 */
export function javaVersionProblem(major) {
  if (major === null || major >= MINIMUM_JAVA_VERSION) {
    return null;
  }
  return (
    `UnitedPlaylists needs Java ${MINIMUM_JAVA_VERSION} or newer, but the Java on your ` +
    `PATH is version ${major}.\n\n` +
    `Install a Java ${MINIMUM_JAVA_VERSION} runtime (for example Eclipse Temurin from ` +
    "adoptium.net) and make sure it comes first on your PATH, then start UnitedPlaylists again."
  );
}
