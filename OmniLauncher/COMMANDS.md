# OmniLauncher IPC Contract

**Status**: binding for Phase 4 brick 7 onward. Changes require updating this file, the
Rust DTOs, the TypeScript transcription, and the pinning tests — in the same commit.

This is the single human-readable description of everything that crosses the boundary
between the Rust core and the web UI. It exists because that boundary is the one place
where two languages must agree about the same shapes, and drift there produces the worst
class of bug: a UI that silently renders nothing.

## Authority chain

```
SOUL v2.0.0
  └── OmniLauncher/ARCHITECTURE.md §1 (layering), §7 (surfaces), §8 (testing)
        └── omni-core/src/app/        the command API + DTOs   ← truth
              └── apps/launcher-shell  Tauri registration only  ← no logic
                    └── apps/launcher-ui  rendering only        ← no truth
```

**Where the logic lives.** ARCHITECTURE §1 assigns the command API and typed event stream
to `omni-core`, *not* to the shell. So:

- `omni-core::app::AppService` holds every decision, every validation, every registry.
- `apps/launcher-shell` contains only `#[tauri::command]` wrappers that dispatch into it.
- `apps/launcher-ui` renders DTOs and re-fetches on events. It patches nothing locally.

**Why it is arranged this way.** Because the shell has no logic, the entire command
surface is testable without a window, a WebView, or a network — see
`omni-core/tests/app.rs` (20 tests). If a behavior can only be tested by opening a
window, the layering has been violated and the fix is to move that behavior down a layer.

## How the pieces are kept honest

| Contract element | Defined in | Pinned by |
|---|---|---|
| DTO field names (`camelCase`) | `omni-core/src/app/dto.rs` | `dto_wire_shapes_are_camel_case` |
| Event variant shapes | `omni-core/src/events.rs` | `event_wire_shapes_are_stable` |
| TypeScript mirror | `apps/launcher-ui/src/api/types.ts` | `npm run build` (strict `vue-tsc`) |
| Command names/arguments | `apps/launcher-shell/src/commands.rs` | `apps/launcher-ui/src/api/index.ts` |

A change that breaks the wire has to break a Rust test first. That is deliberate: TypeScript
cannot see Rust, so the enforcement point is on the Rust side.

## Conventions that hold everywhere

1. **Commands return quickly.** Nothing long-running blocks a command. Installs, sign-in,
   and launches register work and return a handle; progress arrives as events.
2. **Commands never partially mutate.** Multi-row changes run inside store transactions.
3. **Every mutation emits an event.** The UI re-fetches on the event instead of patching —
   the only way "the UI holds no truth" can actually hold when background threads exist.
4. **Errors are typed.** Every rejection is `AppError { kind, message }`. `kind` is for
   branching; `message` is written to be shown to a human verbatim.
5. **Argument names are `camelCase` over IPC** (Tauri's convention) and `snake_case` in
   Rust. The `api/index.ts` wrappers are the only place that mapping appears.

### Error kinds

| `kind` | Meaning | What the UI should do |
|---|---|---|
| `usage` | Invalid argument or unsupported request | Show inline near the control |
| `notFound` | The row does not exist | Refresh the list; the record is gone |
| `store` | Database failure | Show the message; it is not the user's fault |
| `io` | Filesystem/download failure | Offer retry |
| `auth` | Sign-in required or refused | Send the user to the Accounts surface |
| `entitlement` | Offline window expired, or the account owns no copy | Explain and require sign-in |
| `network` | Endpoint unreachable | Offer retry; mention offline mode where relevant |
| `internal` | A command task itself failed | Should not happen; report it |

## Commands

Argument names below are as sent over IPC. All return `Promise<T>` rejecting with `AppError`.

### Instances

| Command | Arguments | Returns | Notes |
|---|---|---|---|
| `list_instances` | — | `InstanceSummary[]` | Sorted most-recently-played first, then by name |
| `instance_detail` | `instanceId` | `InstanceDetail` | Stack, mods, settings, EULA state |
| `create_instance` | `name`, `gameVersion`, `kind` | `InstanceSummary` | Seeds the stack; does **not** install |
| `rename_instance` | `instanceId`, `name` | `void` | Name is trimmed; blank rejected |
| `update_instance_settings` | `instanceId`, `settings` | `void` | Must be a JSON object |
| `delete_instance` | `instanceId` | `boolean` | Removes rows only — **files on disk are left alone** |
| `set_mod_enabled` | `modId`, `enabled` | `void` | |
| `delete_mod` | `modId` | `boolean` | |

`delete_instance` deliberately does not touch the filesystem. Deleting a user's worlds must
never be a side effect of a database operation.

### Versions

| Command | Arguments | Returns |
|---|---|---|
| `available_versions` | `releasesOnly` | `VersionOption[]` |

Reads the meta service. Works offline once the root index has been cached (SVC-2).

### Jobs

| Command | Arguments | Returns | Notes |
|---|---|---|---|
| `list_jobs` | — | `Job[]` | Newest first; `running` = owned by a thread in *this* process |
| `start_install` | `instanceId` | `number` (job id) | Returns immediately |
| `resume_job` | `jobId` | `void` | Valid for `pending`/`paused`/`failed` |
| `cancel_job` | `jobId` | `boolean` | Cooperative pause at the next step boundary |
| `clear_finished_jobs` | — | `number` (removed) | Deletes `done` and `failed` rows; never touches live work |

Installs persist a resume point after every step, so **resume continues rather than
restarting** and re-running a finished install is a free verify/repair pass.

`clear_finished_jobs` removes `done` and `failed` rows only — `pending`, `paused`, and
`running` survive, because those are work the user has not finished with. Clearing a
failed job is not lossy: what it downloaded lives in the content-addressed cache, so a
fresh install for the same instance resumes from exactly the same point.

### Accounts

| Command | Arguments | Returns | Notes |
|---|---|---|---|
| `list_accounts` | — | `Account[]` | `entitlementValid` reflects the ADR-0011 window |
| `start_device_login` | — | `DeviceCode` | Returns the prompt; completion arrives as `LoginFinished` |
| `sign_out` | `uuid` | `void` | Deletes the vault entry and the row |

The launcher never renders a password field and never receives a password
(COMPLIANCE §2). The user consents on Microsoft's own page.

### Launch and console

| Command | Arguments | Returns | Notes |
|---|---|---|---|
| `accept_server_eula` | `instanceId` | `void` | **Only** from an explicit user action (D8) |
| `launch_instance` | `instanceId`, `accountUuid` | `number` (session id) | Runs the CPL-2 gate first |
| `stop_process` | `sessionId` | `boolean` | `false` = not supervised here |
| `console_tail` | `sessionId`, `maxLines` | `ConsoleTail` | Capped at 2000 lines |

`launch_instance` has exactly three outcomes, pinned by
`launch_gate_distinguishes_its_three_outcomes`:

1. **Online and verified** → launches, and the 14-day offline window slides forward.
2. **Offline inside the window** → launches on the vault-anchored entitlement record.
3. **Otherwise** → refuses with `entitlement` (window expired) or `auth` (no credentials).

There is no fourth path. A launch never happens without a verified entitlement.

### Import

| Command | Arguments | Returns |
|---|---|---|
| `detect_import` | `path` | `DetectedFormat[]` (best confidence first) |
| `enumerate_import` | `path`, `format` | `ImportCandidate[]` |
| `run_import` | `candidate` | `ImportReport` |

Format slugs: `curseforge`, `prism-multimc`, `mrpack`, `official-launcher`. Source files
are read, never modified or moved. The report lists everything that did *not* map, with
reasons — migration tools that silently drop content are why users distrust them.

### Settings

| Command | Arguments | Returns |
|---|---|---|
| `get_setting` | `key` | `unknown \| null` |
| `set_setting` | `key`, `value` | `void` |

Known keys: `activeAccount` (string), `defaultMemoryMib` (number),
`showReleasesOnly` (boolean).

## Events

All events publish on the single Tauri channel **`omni://event`**. The payload is the
serialized `CoreEvent` with **no envelope** — what the Rust test pins is exactly what the
UI receives. Serde's external tagging means field-carrying variants arrive as
`{ VariantName: { ...fields } }` and unit variants as the bare string.

| Event | Payload | The UI should |
|---|---|---|
| `JobState` | `{ job_id, kind, state }` | Refresh jobs |
| `JobProgress` | `{ job_id, progress }` | Refresh jobs (progress bar) |
| `ProcessStarted` | `{ instance_id, session_id, pid }` | Note the session for the console |
| `ProcessExited` | `{ instance_id, session_id, exit_code }` | Notify; refresh instances |
| `InstanceChanged` | `{ instance_id }` (`null` = list changed) | Refresh instances |
| `JobsChanged` | *(bare string)* | Refresh the whole job list (rows added/removed) |
| `AccountsChanged` | *(bare string)* | Refresh accounts |
| `LoginFinished` | `{ uuid, error }` | Close the device-code panel; report outcome |

Field-less variants (`JobsChanged`, `AccountsChanged`) arrive as bare strings, so a
TypeScript handler must narrow those **before** any `in` check — otherwise the remaining
union still contains a string. The strict typecheck enforces this.

Event *payload* fields are `snake_case` — they come from the core's own enum rather than
the DTO layer, and the pinning test documents them exactly as they appear. This asymmetry
with the `camelCase` DTOs is intentional and load-bearing: it means the event contract
cannot silently drift into the DTO contract.

## Adding a command (checklist)

1. Add the method to `omni-core/src/app/commands.rs` with `# Errors` documentation.
2. Add DTOs to `app/dto.rs` if new shapes cross the wire.
3. Add a test to `omni-core/tests/app.rs` — behavior **and** wire shape.
4. Add the one-line wrapper in `apps/launcher-shell/src/commands.rs` and register it in
   `handler()`.
5. Add the typed wrapper in `apps/launcher-ui/src/api/index.ts` and types in `types.ts`.
6. Update this file.
7. `cargo test --workspace`, `cargo clippy --workspace --all-targets`, `npm run build`.

If step 1 feels like the wrong home for the logic, that is the signal something is being
put in the wrong layer — not a reason to put it in the shell.

## Security posture

- The web layer has **no** Tauri permissions beyond calling this app's own commands and
  listening to its own event channel (`capabilities/default.json`). No filesystem, shell,
  or HTTP access is granted to the UI: every privileged action happens in Rust behind a
  typed command.
- The CSP forbids remote script, style, and connection sources. The UI ships entirely
  from the bundle.
- Tokens never cross this boundary. The UI sees usernames, UUIDs, and entitlement state —
  never an access token, refresh token, or anything from the OS credential vault.
