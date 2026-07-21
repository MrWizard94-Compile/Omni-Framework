/**
 * The wire contract, transcribed from Rust.
 *
 * Source of truth: `omni-core/src/app/dto.rs` (DTOs) and
 * `omni-core/src/events.rs` (`CoreEvent`). Both shapes are pinned by
 * `omni-core/tests/app.rs` — `dto_wire_shapes_are_camel_case` and
 * `event_wire_shapes_are_stable`. A change that breaks this file has to break
 * those tests first, which is the point: the contract is enforced on the Rust
 * side, and this file exists so TypeScript can see it.
 *
 * See `OmniLauncher/COMMANDS.md` for the human-readable version.
 */

export type InstanceKind = "client" | "server";

export type Ecosystem = "omni" | "fabric" | "neoforge" | "forge" | "unknown";

export type PortStatus = "native" | "compat" | "ported" | "excluded" | "unknown";

export type JobState = "pending" | "running" | "paused" | "failed" | "done";

/** Machine-readable failure classes; the UI branches on these. */
export type ErrorKind =
  | "usage"
  | "notFound"
  | "store"
  | "io"
  | "auth"
  | "entitlement"
  | "network"
  | "internal";

/** Every command rejects with this shape. `message` is safe to render. */
export interface AppError {
  kind: ErrorKind;
  message: string;
}

export interface LoaderRef {
  kind: string;
  version: string;
}

export interface InstanceSummary {
  id: number;
  name: string;
  icon: string | null;
  kind: InstanceKind;
  gameVersion: string;
  createdAt: number;
  lastPlayedAt: number | null;
  modCount: number;
  loader: LoaderRef | null;
}

export interface StackEntry {
  position: number;
  uid: string;
  version: string;
  pinned: boolean;
}

export interface ModEntry {
  id: number;
  fileName: string;
  displayName: string | null;
  version: string | null;
  ecosystem: Ecosystem;
  enabled: boolean;
  platform: string | null;
  portStatus: PortStatus;
}

export interface InstanceDetail {
  summary: InstanceSummary;
  notes: string;
  settings: Record<string, unknown>;
  stack: StackEntry[];
  mods: ModEntry[];
  serverEulaAccepted: boolean;
}

export interface Account {
  id: number;
  uuid: string;
  username: string;
  entitlementValid: boolean;
  windowExpiresAt: number | null;
}

export interface DeviceCode {
  verificationUri: string;
  userCode: string;
  expiresInSecs: number;
}

export interface Job {
  id: number;
  kind: string;
  instanceId: number | null;
  state: JobState;
  progress: { phase?: string; completed?: number; total?: number } & Record<string, unknown>;
  error: { message?: string; retryable?: boolean } | null;
  running: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface VersionOption {
  version: string;
  releaseType: string;
  recommended: boolean;
  releaseTime: string;
}

export interface DetectedFormat {
  format: string;
  confidence: "certain" | "possible";
}

export interface ImportCandidate {
  format: string;
  name: string;
  root: string;
  key: string | null;
}

export interface ImportReport {
  instanceId: number;
  modsImported: number;
  skipped: { item: string; reason: string }[];
  notes: string[];
}

/** Phase 5 / ADR-0014 export outcome. */
export interface ExportReport {
  format: "mrpack" | "curseforge" | "omni" | string;
  path: string;
  modsExported: number;
  skipped: string[];
  notes: string[];
}

export interface ConsoleTail {
  path: string;
  lines: string[];
  running: boolean;
}

/**
 * Core events, externally tagged exactly as serde emits them. `AccountsChanged`
 * carries no fields, so it arrives as the bare string — hence the union of an
 * object type and a literal.
 */
export type CoreEvent =
  | { JobState: { job_id: number; kind: string; state: JobState } }
  | { JobProgress: { job_id: number; progress: Record<string, unknown> } }
  | { ProcessStarted: { instance_id: number; session_id: number; pid: number | null } }
  | { ProcessExited: { instance_id: number; session_id: number; exit_code: number } }
  | { InstanceChanged: { instance_id: number | null } }
  | { LoginFinished: { uuid: string | null; error: string | null } }
  | "JobsChanged"
  | "AccountsChanged";
