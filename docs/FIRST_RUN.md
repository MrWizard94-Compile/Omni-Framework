# First run — driving OmniLauncher for real

Walkthrough for opening the window on the director's machine (Windows 11).
**Meta CDN is live** (`https://omnimeta.wpaistudio.net/v1/`). Mojang client-ID
approval landed 2026-07-20.

Phase 4 **product exit is closed** (bricks 1–16, B1–B4, live meta, server smoke,
and **director-confirmed client vanilla 1.21.1** — playable 2026-07-21). This
document remains the **how-to** for opening the window and re-verifying.

## Before you start

Confirmed stack (update if your machine differs): WebView2, MSVC, Rust 1.96+,
Node 20+, `tauri-cli` 2.x.

```powershell
cd C:\WPAI\Gaming\Minecraft\Omni_Framework\OmniLauncher\apps\launcher-shell
cargo tauri dev
```

First compile of Tauri deps is several minutes; later runs are fast. Leave the
terminal open for Rust logs. Vite serves `http://localhost:5173`.

## What you should see

Dark window ~1280×820, six tabs: **Instances, Accounts, Jobs, Import, Console,
Settings**. Mojang disclaimer in the sidebar (COMPLIANCE §1).

## Ordered walkthrough

### 1. Accounts — sign in

**Accounts → Add account.** Open the Microsoft link, enter the code, complete
consent. Omni never sees your password.

Success: username, **verified** badge, 14-day offline window (ADR-0011).

> The CLI `auth_probe` uses a separate vault namespace — you still sign in once
> in the app.

### 2. Instances — create vanilla 1.21.1

**Instances → New** → name it → pick **1.21.1** from the version list (from live
meta) → kind **Client** → **Create**.

### 3. Install

Select instance → **Install / Repair** → watch **Jobs**. Expect libraries,
client jar, assets, Java 21. Pause/resume and re-run install to confirm cache.

### 4. Launch (Phase 4 client exit)

With account signed in and install done → **Launch**. Output in **Console**.

- No account → Launch disabled (client).
- Expired entitlement → typed refusal, no process.

### 5. Server path (no Microsoft account)

Create kind **Server** → **Install** → accept **EULA** → **Launch**. Servers
do **not** need MSA (D8); only EULA + install.

Automated proof (no window):

```powershell
cd C:\WPAI\Gaming\Minecraft\Omni_Framework
cargo run -p omni-core --example server_smoke --release -- --accept-eula --launch
```

(Only run `--accept-eula` after you have read https://aka.ms/MinecraftEULA.)

### 6. Import a mod pack (Prism / CurseForge app folder)

1. Build the loader dist once (if you have not):
   ```powershell
   cd C:\WPAI\Gaming\Minecraft\Omni_Framework\OmniLoader
   .\gradlew :loader-bootstrap:loaderDist
   ```
   The launcher auto-finds `OmniLoader/loader-bootstrap/build/loader-dist` when
   started from the monorepo, or set `$env:OMNI_LOADER_DIST` to that path.

2. **Import** → pick a Prism or CurseForge instance folder. Omni copies jars into
   `%APPDATA%\OmniLauncher\instances\<id>\game\mods\` and records the loader kind.

3. **Install** the instance (vanilla game + Java), then **Launch**.

**Honest limits (2026-07-21):** OmniLoader discovers those jars and runs Fabric
entrypoints (`main`/`client`) with a real `FabricLoader.getInstance()`. **Bytecode
is not yet remapped** (intermediary→official) and **Mixin is not yet installed on
the boot path**, so many published Fabric jars will still crash until that wiring
lands. Native Omni mods and fixture-class Fabric mods are the reliable path today.
NeoForge packs are claim+`@Mod` construct only.

## If something goes wrong

- Toasts show core messages; Rust logs are in the `cargo tauri dev` terminal.
- Data: `%APPDATA%\OmniLauncher` — delete that folder to reset.
- Meta 403 from Python-urllib is Cloudflare bot protection; the launcher UA is fine.

## Exit criterion status

| Criterion | Status |
|-----------|--------|
| Launcher install from live meta | ✅ `server_smoke` install (live) |
| Server spawn without MSA | ✅ DedicatedServer launch path + smoke `--launch` |
| Mixed-pack loader fixtures | ✅ `MixedPackBootTest` |
| Client window vanilla 1.21.1 | ✅ **director-confirmed playable** (2026-07-21) |
