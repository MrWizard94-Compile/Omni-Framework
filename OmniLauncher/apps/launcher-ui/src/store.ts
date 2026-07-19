/**
 * The UI's shared reactive cache.
 *
 * This is a *cache*, not a source of truth (ARCHITECTURE §1). The rule it
 * enforces: mutations go to the core, and the cache is only ever refilled by
 * refetching after a `CoreEvent`. Nothing here patches a record locally and
 * assumes the core agrees — which is why the launcher can have background job
 * threads and a live game process without the UI drifting out of sync.
 */

import { reactive, readonly } from "vue";
import * as api from "./api";
import type { Account, CoreEvent, InstanceSummary, Job } from "./api/types";

interface Toast {
  id: number;
  kind: "error" | "info";
  message: string;
}

interface State {
  instances: InstanceSummary[];
  accounts: Account[];
  jobs: Job[];
  /** UUID the user launches with; persisted as a core setting. */
  activeAccount: string | null;
  /** Session id of the most recent launch, for the console surface. */
  activeSession: number | null;
  toasts: Toast[];
  ready: boolean;
}

const state = reactive<State>({
  instances: [],
  accounts: [],
  jobs: [],
  activeAccount: null,
  activeSession: null,
  toasts: [],
  ready: false,
});

let nextToastId = 1;

/** Shows a transient message. Errors stay until dismissed; info self-clears. */
export function toast(kind: Toast["kind"], message: string): void {
  const id = nextToastId++;
  state.toasts.push({ id, kind, message });
  if (kind === "info") {
    setTimeout(() => dismissToast(id), 4000);
  }
}

export function dismissToast(id: number): void {
  const index = state.toasts.findIndex((t) => t.id === id);
  if (index >= 0) state.toasts.splice(index, 1);
}

/** Reports a rejected command to the user without swallowing it. */
export function reportError(error: unknown): void {
  toast("error", api.errorMessage(error));
}

export async function refreshInstances(): Promise<void> {
  try {
    state.instances = await api.listInstances();
  } catch (error) {
    reportError(error);
  }
}

export async function refreshAccounts(): Promise<void> {
  try {
    state.accounts = await api.listAccounts();
    // An account that disappeared cannot stay selected.
    if (state.activeAccount && !state.accounts.some((a) => a.uuid === state.activeAccount)) {
      await setActiveAccount(state.accounts[0]?.uuid ?? null);
    } else if (!state.activeAccount && state.accounts.length > 0) {
      await setActiveAccount(state.accounts[0].uuid);
    }
  } catch (error) {
    reportError(error);
  }
}

export async function refreshJobs(): Promise<void> {
  try {
    state.jobs = await api.listJobs();
  } catch (error) {
    reportError(error);
  }
}

/** Persists the launch account in the core so it survives a restart. */
export async function setActiveAccount(uuid: string | null): Promise<void> {
  state.activeAccount = uuid;
  try {
    await api.setSetting("activeAccount", uuid);
  } catch (error) {
    reportError(error);
  }
}

/**
 * Boots the UI: subscribe first, then load. Subscribing before the initial
 * fetch means an event that fires during startup cannot be missed.
 */
export async function initStore(): Promise<void> {
  await api.onCoreEvent(handleEvent);
  try {
    const stored = await api.getSetting("activeAccount");
    if (typeof stored === "string") state.activeAccount = stored;
  } catch (error) {
    reportError(error);
  }
  await Promise.all([refreshInstances(), refreshAccounts(), refreshJobs()]);
  state.ready = true;
}

function handleEvent(event: CoreEvent): void {
  if (event === "AccountsChanged") {
    void refreshAccounts();
    return;
  }
  if ("InstanceChanged" in event) {
    void refreshInstances();
    return;
  }
  if ("JobState" in event || "JobProgress" in event) {
    void refreshJobs();
    return;
  }
  if ("ProcessStarted" in event) {
    state.activeSession = event.ProcessStarted.session_id;
    return;
  }
  if ("ProcessExited" in event) {
    const code = event.ProcessExited.exit_code;
    toast(code === 0 ? "info" : "error", `Session ended (exit code ${code}).`);
    void refreshInstances();
    return;
  }
  if ("LoginFinished" in event) {
    const { uuid, error } = event.LoginFinished;
    if (error) toast("error", `Sign-in failed: ${error}`);
    else if (uuid) toast("info", "Signed in.");
  }
}

/** Read-only view for components; mutations go through the actions above. */
export const store = readonly(state);
/** Writable handle for the few places that own a field outright. */
export const mutableStore = state;
