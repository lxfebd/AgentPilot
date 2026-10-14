import fs from "node:fs";
import path from "node:path";
import type { DesktopServerConnection } from "./desktop-server";
import { readJsonFile, writeJsonFile } from "./json-store";
import { compareUpdateVersions } from "./update-version";
import { downloadDesktopInstaller } from "./update-download";
import type { DesktopUpdateState } from "../shared/desktop-updates";

type Options = {
  directory: string;
  currentVersion: string;
  /** Server version recorded at build time; a newer live Server means a newer Desktop exists. */
  serverVersion: string;
  downloadUrl: string;
  platform: string;
  fetcher: typeof fetch;
  openInstaller: (filePath: string) => Promise<void>;
  onState: (state: DesktopUpdateState) => void;
  healthTimeoutMs?: number;
  downloadTimeoutMs?: number;
};

/**
 * The installer URL an update can actually be delivered from.
 *
 * A self-hosted Server is usually newer than the Desktop build, but that says
 * nothing about a Desktop installer being available: `downloadUrl` is empty on
 * a default configuration.  Offering an update with nowhere to get it produces
 * a dialog whose only outcome is an error, so the same check gates the offer
 * and the download - the two can never disagree about what is installable.
 */
function usableDownloadUrl(raw: string): URL | null {
  try {
    const url = new URL(raw);
    if (url.protocol !== "https:" || url.hostname.endsWith(".invalid") || url.username || url.password) {
      return null;
    }
    return url;
  } catch {
    return null;
  }
}

/**
 * Where a Server publishes its own Desktop installer, next to `/apk/` for the
 * Android channel.  Derived from the origin the user is signed into rather than
 * configured, so a self-hosted deployment offers its own build and the shipped
 * configuration hardcodes no host - the same rule `AppConfig` uses on Android.
 */
const DERIVED_INSTALLER_PATH = "/desktop-installer/latest.exe";

function derivedInstallerUrl(serverUrl: string): URL | null {
  try {
    const origin = new URL(serverUrl);
    // Only https: the download itself refuses anything else.
    if (origin.protocol !== "https:") return null;
    return new URL(`${origin.origin}${DERIVED_INSTALLER_PATH}`);
  } catch {
    return null;
  }
}

export class DesktopUpdateService {
  private state: DesktopUpdateState;
  private serverKey = "";
  private generation = 0;
  private checkPromise: Promise<DesktopUpdateState> | null = null;
  private downloadPromise: Promise<DesktopUpdateState> | null = null;
  private healthAbort: AbortController | null = null;
  private downloadAbort: AbortController | null = null;
  private installerPath: string | null = null;
  private readonly configuredDownload: URL | null;
  /** The installer resolved for the Server of the current check, if there is one. */
  private downloadTarget: URL | null = null;

  constructor(private readonly options: Options) {
    this.state = this.initialState();
    this.configuredDownload = usableDownloadUrl(options.downloadUrl);
  }

  getState(): DesktopUpdateState { return { ...this.state }; }

  check(server: DesktopServerConnection | null): Promise<DesktopUpdateState> {
    if (!server?.serverUrl.trim()) {
      this.dispose();
      this.serverKey = "";
      this.checkPromise = null;
      this.downloadPromise = null;
      this.healthAbort = null;
      this.downloadAbort = null;
      this.installerPath = null;
      this.downloadTarget = null;
      this.publish(this.initialState());
      return Promise.resolve(this.getState());
    }
    const key = `${server.serverUrl}${server.apiNamespace}`;
    if (key === this.serverKey) {
      if (this.checkPromise) return this.checkPromise;
      if (this.downloadPromise) return Promise.resolve(this.getState());
    }
    this.healthAbort?.abort();
    this.downloadAbort?.abort();
    this.downloadAbort = null;
    this.downloadPromise = null;
    const generation = ++this.generation;
    this.serverKey = key;
    this.installerPath = null;
    this.publish({ ...this.initialState(), checking: true });
    const controller = new AbortController();
    this.healthAbort = controller;
    const timeout = setTimeout(() => controller.abort(), this.options.healthTimeoutMs ?? 10_000);
    const promise = (async () => {
      try {
        const response = await this.options.fetcher(`${key}/health`, {
          headers: { accept: "application/json" }, credentials: "omit", cache: "no-store",
          redirect: "error", signal: controller.signal,
        });
        if (!response.ok) throw new Error("Health request failed.");
        const payload = await readHealth(response, controller.signal);
        if (payload?.status !== "ok" || typeof payload.version !== "string") throw new Error("Health response has no version.");
        const latestVersion = payload.version.trim();
        const comparison = compareUpdateVersions(latestVersion, this.options.serverVersion);
        if (comparison === null) throw new Error("Unsupported version format.");
        if (generation !== this.generation) return this.getState();
        // A configured URL is operator intent and taken at face value.  Without
        // one, the Server's own published installer counts only if it is really
        // there: offering an update with nothing behind it produces a dialog
        // whose only outcome is an error.
        let target = this.configuredDownload;
        if (target === null && comparison > 0) {
          const derived = derivedInstallerUrl(server.serverUrl);
          if (derived !== null && await this.providesInstaller(derived, controller.signal)) target = derived;
        }
        if (generation !== this.generation) return this.getState();
        this.downloadTarget = target;
        const available = comparison > 0 && target !== null;
        const ignoredVersion = this.readIgnored()[key];
        const ignored = available && typeof ignoredVersion === "string" && compareUpdateVersions(ignoredVersion, latestVersion) === 0;
        this.publish({ checking: false, latestVersion, available, ignored, dialogOpen: available && !ignored, error: null });
      } catch {
        if (generation === this.generation) this.publish({ checking: false, error: "checkFailed" });
      } finally {
        clearTimeout(timeout);
        if (generation === this.generation) { this.checkPromise = null; this.healthAbort = null; }
      }
      return this.getState();
    })();
    this.checkPromise = promise;
    return promise;
  }

  /** Whether this Server actually publishes the installer derived from its origin. */
  private async providesInstaller(url: URL, signal: AbortSignal): Promise<boolean> {
    try {
      const response = await this.options.fetcher(url.href, {
        method: "HEAD", credentials: "omit", cache: "no-store", redirect: "error", signal,
      });
      return response.ok;
    } catch {
      return false;
    }
  }

  showPrompt(): DesktopUpdateState {
    if (this.state.available) this.publish({ dialogOpen: true });
    return this.getState();
  }

  ignoreVersion(): DesktopUpdateState {
    if (!this.state.available || !this.state.latestVersion || this.downloadPromise) return this.getState();
    try {
      writeJsonFile(path.join(this.options.directory, "state.json"), {
        ignoredVersions: { ...this.readIgnored(), [this.serverKey]: this.state.latestVersion },
      });
      this.publish({ ignored: true, dialogOpen: false, error: null });
    } catch {
      this.publish({ error: "ignoreFailed" });
    }
    return this.getState();
  }

  download(): Promise<DesktopUpdateState> {
    if (this.downloadPromise) return this.downloadPromise;
    if (!this.state.available) return Promise.resolve(this.getState());
    const target = this.downloadTarget;
    if (target === null) {
      this.publish({ dialogOpen: true, error: "downloadNotConfigured" });
      return Promise.resolve(this.getState());
    }
    const generation = this.generation;
    const controller = new AbortController();
    this.downloadAbort = controller;
    const timeout = setTimeout(() => controller.abort(), this.options.downloadTimeoutMs ?? 30 * 60_000);
    this.publish({ dialogOpen: true, phase: "downloading", error: null, downloadedBytes: 0, totalBytes: null });
    const promise = (async () => {
      try {
        if (!this.installerPath || !fs.existsSync(this.installerPath)) {
          const downloadedPath = await downloadDesktopInstaller({
            url: target.href, directory: path.join(this.options.directory, "downloads"),
            platform: this.options.platform, fetcher: this.options.fetcher, signal: controller.signal,
            onProgress: (downloadedBytes, totalBytes) => {
              if (generation === this.generation) this.publish({ downloadedBytes, totalBytes });
            },
          });
          if (generation !== this.generation || controller.signal.aborted) {
            fs.rmSync(downloadedPath, { force: true });
            return this.getState();
          }
          this.installerPath = downloadedPath;
        }
        controller.signal.throwIfAborted();
        if (generation !== this.generation) return this.getState();
        this.publish({ phase: "opening" });
        await this.options.openInstaller(this.installerPath);
        if (generation === this.generation) this.publish({ phase: "downloaded", dialogOpen: false });
      } catch {
        if (generation === this.generation) this.publish({ phase: "idle", error: this.state.phase === "opening" ? "openFailed" : "downloadFailed" });
      } finally {
        clearTimeout(timeout);
        if (this.downloadAbort === controller) {
          this.downloadPromise = null;
          this.downloadAbort = null;
        }
      }
      return this.getState();
    })();
    this.downloadPromise = promise;
    return promise;
  }

  dispose(): void {
    ++this.generation;
    this.healthAbort?.abort();
    this.downloadAbort?.abort();
  }

  private readIgnored(): Record<string, unknown> {
    const value = readJsonFile<{ ignoredVersions?: unknown } | null>(path.join(this.options.directory, "state.json"), null)?.ignoredVersions;
    return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
  }

  private initialState(): DesktopUpdateState {
    return {
      revision: 0, currentVersion: this.options.currentVersion, latestVersion: null,
      checking: false, available: false, ignored: false, dialogOpen: false,
      phase: "idle", downloadedBytes: 0, totalBytes: null, error: null,
    };
  }

  private publish(patch: Partial<DesktopUpdateState>): void {
    this.state = { ...this.state, ...patch, revision: this.state.revision + 1 };
    this.options.onState(this.getState());
  }
}

async function readHealth(response: Response, signal: AbortSignal): Promise<{ status?: unknown; version?: unknown }> {
  const reader = response.body?.getReader();
  if (!reader) throw new Error("Empty health response.");
  const cancel = () => { void reader.cancel().catch(() => undefined); };
  signal.addEventListener("abort", cancel, { once: true });
  const decoder = new TextDecoder();
  let content = "";
  try {
    while (true) {
      signal.throwIfAborted();
      const { done, value } = await reader.read();
      signal.throwIfAborted();
      if (done) break;
      content += decoder.decode(value, { stream: true });
      if (content.length > 65_536) throw new Error("Health response is too large.");
    }
    return JSON.parse(content + decoder.decode());
  } finally {
    signal.removeEventListener("abort", cancel);
    await reader.cancel().catch(() => undefined);
  }
}
