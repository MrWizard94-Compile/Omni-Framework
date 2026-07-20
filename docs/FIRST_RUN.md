# First run — driving OmniLauncher for real

A walkthrough for the first time the window actually opens. Written for the director's
machine (Windows 11, everything already installed and verified 2026-07-20).

This is the one part of Phase 4 that cannot be verified autonomously: it puts a window on
a screen and needs eyes on it.

## Before you start

Everything below was confirmed present via `cargo tauri info`: WebView2 150.0.4078.83,
MSVC Visual Studio Community 2022, Rust 1.96.0, Node 25.6.0, `tauri-cli` 2.11.4. Nothing
to install.

**Expect one long wait.** The first `cargo tauri dev` compiles the Tauri dependency tree
in debug mode — several minutes, once. After that it is seconds, and the web layer
hot-reloads without a restart at all.

## Starting it

```powershell
cd C:\WPAI\Gaming\Minecraft\Omni_Framework\OmniLauncher\apps\launcher-shell
cargo tauri dev
```

That command starts the Vite dev server (verified working: HTTP 200 on
`http://localhost:5173`), waits for it, builds the Rust shell, and opens the window.
Leave the terminal open — it is where Rust-side logs appear. `Ctrl+C` stops everything.

## What you should see

A dark window, 1280×820, with six tabs down the left: **Instances, Accounts, Jobs,
Import, Console, Settings**. The sidebar carries the Mojang disclaimer at the bottom;
that text is required on all materials (COMPLIANCE §1) and is not decoration.

## A first pass, in the order things depend on each other

### 1. Accounts — sign in

**Accounts → Add account.** A panel appears with a link to Microsoft's page and a code to
type there. That is the whole login: OmniLauncher never shows a password field and never
receives your password (COMPLIANCE §2). Complete it in the browser, and the panel closes
by itself when the core reports the result.

> **Expect this to fail at the last step, and that is fine.** Mojang's approval of the
> client ID is still pending (submitted 2026-07-19, weekly review). The Microsoft and
> Xbox links work today; `api.minecraftservices.com` will reject an unapproved app with a
> **403**, which surfaces as a message naming exactly that cause. Everything else below
> can still be exercised.

Once approval lands, a successful sign-in shows your username, a **verified** badge, and
how long offline play remains available (the 14-day window, ADR-0011).

### 2. Instances — create one

**Instances → New.** Name it, pick a version (the list comes from the meta service —
see the note below), leave kind as *Client*, and **Create**.

The instance appears in the grid with its component stack already seeded. Nothing has
been downloaded yet: installing is a separate, explicit step so you can watch it.

> **The version list needs the meta service.** Brick 9's tooling exists but the tree has
> not been published yet, so `omnimeta.wpaistudio.net` currently serves nothing and the
> list will be empty with a network error. Publishing it is the step below.

### 3. Publish the meta tree (one-time, unblocks version lists)

```powershell
cd C:\WPAI\Gaming\Minecraft\Omni_Framework
# Capture Mojang's data and generate the tree (~1 min):
cargo run -p omni-meta-gen -- snapshot --out .\meta-snapshot --versions 1.21.1
cargo run -p omni-meta-gen -- generate --snapshot .\meta-snapshot --out .\meta-tree

# See exactly what would be published — no credentials needed:
cargo run -p omni-meta-deploy -- --tree .\meta-tree --dry-run
```

To actually publish you need an **R2 API token**: Cloudflare dashboard → R2 → *Manage
API tokens* → create one with **Object Read & Write** on the `omni-meta` bucket. Then, in
that shell only:

```powershell
$env:R2_ACCOUNT_ID        = "0c915370ac5cdcdde90ffd6911b87321"
$env:R2_ACCESS_KEY_ID     = "<from the token>"
$env:R2_SECRET_ACCESS_KEY = "<from the token>"
cargo run -p omni-meta-deploy -- --tree .\meta-tree
```

It uploads deepest-first, then reads every object back through the public URL, so a green
run proves a launcher can actually fetch it. Close that shell afterwards — the
credentials live only in it.

### 4. Instances — install

Select the instance → **Install / Repair**, then switch to **Jobs**.

This is the ~600 MB download: libraries, the client jar, assets, and a Java 21 runtime.
The job center shows the live phase and progress bar, driven by events rather than
polling.

Two things worth trying deliberately, because they are the properties the design is
built around:

- **Press Pause mid-install**, then **Resume**. It continues from the last completed
  step; nothing already downloaded is fetched again.
- **Run Install / Repair again after it completes.** It should finish almost instantly
  with zero downloads — every file is verified from the local cache. That same pass is
  what repairs a corrupted install.

### 5. Launch

With an account signed in and the install complete, select the instance → **Launch**.
Output goes to **Console**, and the process is recorded (exit code, session log).

Without a signed-in account the button is disabled; with one whose entitlement cannot be
verified you get a typed refusal rather than a broken launch. There is no path to a game
process without a verified entitlement (CPL-2).

### 6. Import — bring a real pack across

**Import**, paste an instance path, **Scan**. For example:

```
C:\Users\Bulkl\curseforge\minecraft\Instances\All the Mods 10 To the Sky   ATM10SKY
```

It detects *CurseForge App* with **certain** confidence, lists the pack, and **Import**
maps it in. Expect 324 mods with their platform identity and hashes. Your original files
are read, never moved or modified.

The report panel lists anything that did not map and why — deliberately, because
migration tools that silently drop content are why people distrust them.

### 7. Servers (optional)

Create an instance with kind **Server**. The detail view shows an EULA panel; the server
cannot start until you explicitly accept, which writes `eula.txt` and records that you
did. That is a deliberate user action by design (D8) — never implied by pressing Launch.

## If something goes wrong

- **Errors appear as toasts** in the bottom-right, carrying the core's own message.
  Error text is written to be actionable; if one is not, that is a bug worth reporting.
- **The Rust-side log is the terminal** running `cargo tauri dev`.
- **Web-side inspection**: right-click → Inspect Element opens WebView2's devtools in a
  debug build.
- **Data lives in** `%APPDATA%\OmniLauncher` — `launcher.db`, `objects\` (the shared
  content cache), `instances\<id>\` (per-instance game dirs), `java\`. Deleting an
  instance in the UI removes its rows but leaves its files, on purpose.
- **Starting over**: close the app and delete `%APPDATA%\OmniLauncher`. Nothing outside
  that directory is touched.

## What this run is meant to establish

Phase 4's exit criterion: **vanilla 1.21.1 launches end-to-end from OmniLauncher**. Every
piece has been tested in isolation and against fixtures; this is the first time they run
together against real Mojang data and a real account. Anything that surfaces here is a
genuine finding — the kind that only appears when a system meets reality — and is the
first work item of Phase 5 (Stabilization).
