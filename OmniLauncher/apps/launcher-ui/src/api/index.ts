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
  ConsoleTail,
  CoreEvent,
  DetectedFormat,
  DeviceCode,
  ImportCandidate,
  ImportReport,
  InstanceDetail,
  InstanceKind,
  InstanceSummary,
  Job,
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

// --- settings --------------------------------------------------------------

export const getSetting = (key: string) => invoke<unknown | null>("get_setting", { key });

export const setSetting = (key: string, value: unknown) =>
  invoke<void>("set_setting", { key, value });

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
