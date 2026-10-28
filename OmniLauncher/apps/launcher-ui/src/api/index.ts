/**
 * The only module allowed to talk to the Rust core.
 *
 * Everything else in the UI imports from here, which keeps the "UI holds no
 * truth" rule mechanically checkable: if a component reaches for `invoke`
 * directly, it is bypassing the contract.
 */

import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type {
  Account,
  BootIssue,
  ConsoleTail,
  ContentHit,
  CoreEvent,
  DetectedFormat,
  DeviceCode,
  ExportReport,
  ImportCandidate,
  ImportReport,
  InstanceDetail,
  InstanceKind,
  InstanceSummary,
  JavaRuntime,
  Job,
  ResourceItem,
  ServerConfig,
  VersionOption,
} from "./types";

/** The single event channel the shell publishes on (`lib.rs::EVENT_CHANNEL`). */
export const EVENT_CHANNEL = "omni://event";

// --- instances -------------------------------------------------------------

export const listInstances = () => invoke<InstanceSummary[]>("list_instances");

export const instanceDetail = (instanceId: number) =>
  invoke<InstanceDetail>("instance_detail", { instanceId });

export const createInstance = (name: string, gameVersion: string, kind: InstanceKind) =>
  invoke<InstanceSummary>("create_instance", { name, gameVersion, kind });

export const renameInstance = (instanceId: number, name: string) =>
  invoke<void>("rename_instance", { instanceId, name });

export const updateInstanceSettings = (instanceId: number, settings: Record<string, unknown>) =>
  invoke<void>("update_instance_settings", { instanceId, settings });

export const deleteInstance = (instanceId: number) =>
  invoke<boolean>("delete_instance", { instanceId });

export const setModEnabled = (modId: number, enabled: boolean) =>
  invoke<void>("set_mod_enabled", { modId, enabled });

export const deleteMod = (modId: number) => invoke<boolean>("delete_mod", { modId });

// --- versions --------------------------------------------------------------

export const availableVersions = (releasesOnly: boolean) =>
  invoke<VersionOption[]>("available_versions", { releasesOnly });

// --- jobs ------------------------------------------------------------------

export const listJobs = () => invoke<Job[]>("list_jobs");

export const startInstall = (instanceId: number) =>
  invoke<number>("start_install", { instanceId });

export const resumeJob = (jobId: number) => invoke<void>("resume_job", { jobId });

export const cancelJob = (jobId: number) => invoke<boolean>("cancel_job", { jobId });

/** Clears finished (done/failed) jobs; resolves with how many were removed. */
export const clearFinishedJobs = () => invoke<number>("clear_finished_jobs");

// --- accounts --------------------------------------------------------------

export const listAccounts = () => invoke<Account[]>("list_accounts");

export const startDeviceLogin = () => invoke<DeviceCode>("start_device_login");

export const signOut = (uuid: string) => invoke<void>("sign_out", { uuid });

/**
 * Opens the Microsoft sign-in page in the system browser.
 *
 * The web layer cannot open a browser itself — the window's capability grants
 * it no opener permission by design — so this goes through the shell, which
 * refuses any URL that is not a known Microsoft sign-in host. The `url` is the
 * one the core handed us in the device-code prompt.
 */
export const openSignInPage = (url: string) =>
  invoke<void>("open_sign_in_page", { url });

// --- launch & console ------------------------------------------------------

export const acceptServerEula = (instanceId: number) =>
  invoke<void>("accept_server_eula", { instanceId });

export const launchInstance = (instanceId: number, accountUuid: string) =>
  invoke<number>("launch_instance", { instanceId, accountUuid });

export const stopProcess = (sessionId: number) =>
  invoke<boolean>("stop_process", { sessionId });

export const consoleTail = (sessionId: number, maxLines: number) =>
  invoke<ConsoleTail>("console_tail", { sessionId, maxLines });

// --- import ----------------------------------------------------------------

export const detectImport = (path: string) =>
  invoke<DetectedFormat[]>("detect_import", { path });

export const enumerateImport = (path: string, format: string) =>
  invoke<ImportCandidate[]>("enumerate_import", { path, format });

export const runImport = (candidate: ImportCandidate) =>
  invoke<ImportReport>("run_import", { candidate });

/** Export instance as mrpack / curseforge / omni (Phase 5 / ADR-0014). */
export const exportInstance = (
  instanceId: number,
  format: string,
  dest: string,
  includeModFiles: boolean,
) =>
  invoke<ExportReport>("export_instance", {
    instanceId,
    format,
    dest,
    includeModFiles,
  });

// --- settings --------------------------------------------------------------

export const getSetting = (key: string) => invoke<unknown | null>("get_setting", { key });

export const setSetting = (key: string, value: unknown) =>
  invoke<void>("set_setting", { key, value });

// --- LNC-9 / LNC-10 / server / java / boot issues --------------------------

export const listResources = (instanceId: number, kind: string) =>
  invoke<ResourceItem[]>("list_resources", { instanceId, kind });

export const setResourceEnabled = (
  instanceId: number,
  kind: string,
  name: string,
  enabled: boolean,
) => invoke<void>("set_resource_enabled", { instanceId, kind, name, enabled });

export const searchContent = (query: string, gameVersion: string | null, limit: number) =>
  invoke<ContentHit[]>("search_content", { query, gameVersion, limit });

export const installContent = (
  instanceId: number,
  file: {
    platform: string;
    projectId: string;
    fileId: string;
    fileName: string;
    downloadUrl: string;
    sha1?: string | null;
  },
) => invoke<string>("install_content", { instanceId, file });

/** Resolve latest installable file for a browse hit and install into the instance. */
export const installBrowseHit = (
  instanceId: number,
  platform: string,
  projectId: string,
  gameVersion: string | null,
) =>
  invoke<string>("install_browse_hit", {
    instanceId,
    platform,
    projectId,
    gameVersion,
  });

export const identifyJar = (path: string) =>
  invoke<Record<string, unknown> | null>("identify_jar", { path });

export const discoverJavaRuntimes = (register: boolean) =>
  invoke<JavaRuntime[]>("discover_java_runtimes", { register });

export const getServerConfig = (instanceId: number) =>
  invoke<ServerConfig>("get_server_config", { instanceId });

export const setServerConfig = (instanceId: number, config: ServerConfig) =>
  invoke<void>("set_server_config", { instanceId, config });

export const bootIssues = (instanceId: number) =>
  invoke<BootIssue[]>("boot_issues", { instanceId });

// --- events ----------------------------------------------------------------

/** Subscribes to the core event stream. Returns an unsubscribe function. */
export const onCoreEvent = (handler: (event: CoreEvent) => void) =>
  listen<CoreEvent>(EVENT_CHANNEL, (message) => handler(message.payload));

/**
 * Normalizes a rejected command into a renderable message. Tauri rejects with
 * the serialized `AppError`, but a transport-level failure can still surface as
 * a plain string, so both are handled.
 */
export function errorMessage(error: unknown): string {
  if (typeof error === "string") return error;
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return "Something went wrong.";
}

/** The failure class, when the core supplied one. */
export function errorKind(error: unknown): string | null {
  if (error && typeof error === "object" && "kind" in error) {
    return String((error as { kind: unknown }).kind);
  }
  return null;
}
