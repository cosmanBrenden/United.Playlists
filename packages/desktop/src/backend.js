import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { once } from "node:events";
import { createInterface } from "node:readline";

/**
 * Supervises the Java backend child process.
 *
 * The backend is started on an ephemeral port rather than a fixed one: a fixed
 * port collides when a second copy runs, and a predictable port is easier for a
 * hostile local process to find. The port it actually chose is read back from its
 * startup log.
 *
 * @typedef {{ baseUrl: string, token: string }} BackendInfo
 */

/** Generous: a cold JVM on a slow disk genuinely can take this long. */
const STARTUP_TIMEOUT_MS = 60_000;

/** Spring Boot logs this line once Tomcat is listening. */
const PORT_PATTERN = /Tomcat started on port(?:\(s\))?:? (\d+)/;

export class BackendProcess {
  #child = null;
  /** @type {BackendInfo | null} */
  #info = null;
  #exited = false;

  /**
   * @param {object} options
   * @param {string} options.javaPath  path to the java binary
   * @param {string} options.jarPath   path to the backend jar
   * @param {string} options.dataDir   where the database lives
   * @param {string} options.tokenKey  base64 AES key for token encryption at rest
   * @param {string} [options.loaderPath]  a downloaded NewPipe jar to prefer over the bundled one
   * @param {Record<string, string>} [options.extraEnv]
   */
  constructor({ javaPath, jarPath, dataDir, tokenKey, loaderPath, extraEnv = {} }) {
    this.javaPath = javaPath;
    this.jarPath = jarPath;
    this.dataDir = dataDir;
    this.tokenKey = tokenKey;
    this.loaderPath = loaderPath;
    this.extraEnv = extraEnv;
  }

  /** A fresh secret per run, so a leaked one cannot be replayed at the next start. */
  static generateApiToken() {
    return randomBytes(32).toString("base64url");
  }

  /**
   * Starts the backend and resolves once it is listening.
   *
   * @returns {Promise<BackendInfo>}
   */
  async start() {
    const apiToken = BackendProcess.generateApiToken();

    const javaArgs = [];
    // A downloaded NewPipe extractor, if any, goes ahead of the bundled one. The jar
    // uses Spring Boot's PropertiesLauncher, which reads loader.path and puts these
    // jars first on the classpath, so their classes shadow the bundled BOOT-INF/lib
    // copy. This is how an extractor update applies without a rebuild.
    if (this.loaderPath) {
      javaArgs.push(`-Dloader.path=${this.loaderPath}`);
    }
    javaArgs.push("-jar", this.jarPath);

    this.#child = spawn(
      this.javaPath,
      javaArgs,
      {
        env: {
          ...process.env,
          UP_PORT: "0",
          UP_DATA_DIR: this.dataDir,
          // Passed by environment, never on the command line: argv is world-readable
          // via /proc on Linux and `ps` almost everywhere.
          UP_TOKEN_KEY: this.tokenKey,
          UP_API_TOKEN: apiToken,
          ...this.extraEnv,
        },
        stdio: ["ignore", "pipe", "pipe"],
      },
    );

    this.#child.on("exit", (code) => {
      this.#exited = true;
      if (code !== 0 && code !== null) {
        console.error(`[backend] exited unexpectedly with code ${code}`);
      }
    });

    const port = await this.#awaitPort();
    this.#info = { baseUrl: `http://127.0.0.1:${port}`, token: apiToken };
    return this.#info;
  }

  /** @returns {BackendInfo} */
  getInfo() {
    if (!this.#info) {
      throw new Error("Backend has not started yet");
    }
    return this.#info;
  }

  get isRunning() {
    return this.#child !== null && !this.#exited;
  }

  /** Stops the backend, escalating to SIGKILL if it will not go quietly. */
  async stop() {
    if (!this.#child || this.#exited) {
      return;
    }
    const child = this.#child;
    child.kill("SIGTERM");
    const timer = setTimeout(() => child.kill("SIGKILL"), 5000);
    try {
      await once(child, "exit");
    } finally {
      clearTimeout(timer);
      this.#child = null;
    }
  }

  /** Reads the chosen port out of the backend's startup log. */
  async #awaitPort() {
    const child = this.#child;
    if (!child?.stdout) {
      throw new Error("Backend produced no stdout");
    }

    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        cleanup();
        void this.stop();
        reject(new Error(`Backend did not start within ${STARTUP_TIMEOUT_MS / 1000}s`));
      }, STARTUP_TIMEOUT_MS);

      const stdout = createInterface({ input: child.stdout });
      const stderr = createInterface({ input: child.stderr });
      const errorLines = [];

      const cleanup = () => {
        clearTimeout(timeout);
        stdout.close();
        stderr.close();
      };

      stdout.on("line", (line) => {
        console.log(`[backend] ${line}`);
        const match = PORT_PATTERN.exec(line);
        if (match?.[1]) {
          cleanup();
          resolve(Number(match[1]));
        }
      });

      stderr.on("line", (line) => {
        console.error(`[backend] ${line}`);
        // Kept so a crash can report why, rather than just "it did not start".
        errorLines.push(line);
        if (errorLines.length > 20) {
          errorLines.shift();
        }
      });

      child.on("exit", (code) => {
        cleanup();
        reject(new Error(
          `Backend exited with code ${code} before it was ready.\n${errorLines.join("\n")}`,
        ));
      });

      child.on("error", (cause) => {
        cleanup();
        reject(new Error(`Could not start the backend: ${cause.message}`, { cause }));
      });
    });
  }
}
