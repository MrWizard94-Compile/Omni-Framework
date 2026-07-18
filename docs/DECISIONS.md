# Architecture Decision Records

Append-only log. Every entry: context → decision → consequences. Never delete an ADR; supersede it with a new one. Governed by `../SOULv2.0.0.md` §31.

---

## ADR-0001 — OmniLauncher on Tauri (Rust core + TypeScript UI) · 2026-07-18 · Accepted

**Context.** The launcher runs alongside a memory-hungry game JVM on constrained hardware (director's machine: 6 GB VRAM class). Candidates: Electron (director's HellForge experience), Tauri (Modrinth App, GDLauncher's rewrite), JVM UI (ATLauncher/HMCL). Director mandate: "absolute best for future-proofing and performance."

**Decision.** Tauri: Rust core, TypeScript/web UI. OmniLoader is necessarily JVM (Java 21 — the Minecraft 1.21.1 requirement).

**Consequences.** Smallest RAM/disk footprint of the candidates; memory-safe systems core; web-UI skills transfer from HellForge. Cost: Rust learning curve is absorbed by the AI corps per SOUL's division of labor. The ecosystem signal (Modrinth shipped on Tauri; GDLauncher migrated off Electron to it) supports the future-proofing requirement.

---

## ADR-0002 — Reverse-engineering policy: open-source deep, proprietary black-box · 2026-07-18 · Accepted

**Context.** Phase 1 vivisects existing launchers/loaders. All major loaders and most launchers are open source. The CurseForge app and official Minecraft launcher are proprietary; decompiling them risks ToS violation for a project that must remain EULA-clean.

**Decision.** Open-source subjects: full source-level vivisection at pinned commits. Proprietary subjects: black-box study only — documented file layouts, public API surfaces, officially documented auth flows. No decompilation of proprietary binaries, ever.

**Consequences.** Zero ToS/DMCA exposure. Negligible information loss: the open-source corpus covers ~95% of the feature space, and the proprietary apps' auth flow is the same publicly documented Microsoft OAuth chain everyone uses.

---

## ADR-0003 — Hybrid mod strategy: compat layers + permission-gated native ports · 2026-07-18 · Accepted

**Context.** End-goal is running the best ≤50,000 mods on the Omni-Framework. Roughly 60% of mods carry licenses forbidding derivative redistribution, so "port everything" is legally impossible. Sinytra Connector proves cross-loader runtime compatibility is feasible.

**Decision.** OmniLoader runs existing Fabric/NeoForge/Forge jars **unmodified** through runtime compatibility layers — legally clean for every license because nothing is redistributed or modified. Native Omni ports are produced only where the license permits, or where the author grants written permission after direct outreach. A hard "no" permanently ends native porting for that mod.

**Consequences.** Coverage is guaranteed by compat; quality is raised by ports where allowed. The autonomous porting pipeline (Phase 7) needs a license-classification step and an outreach-tracking system. Compat-layer engineering becomes a core OmniLoader competency — Sinytra Connector joins the Phase 1 corpus as load-bearing prior art.

---

## ADR-0004 — Intelligence pipeline: Python 3.14 + SQLite + TOML sources of truth · 2026-07-18 · Accepted

**Context.** The vivisection database needs zero-cost, offline, deterministic tooling (SOUL §21, §33). Machine has Python 3.10 and 3.14 installed; 3.11+ provides stdlib `tomllib`.

**Decision.** Python 3.14, stdlib-only at runtime (`sqlite3`, `tomllib`, `subprocess` for git). Hand-audited analyses live in `intelligence/analysis/*.toml` (versioned source of truth). `omni_intel.db` is a git-ignored build artifact, rebuilt deterministically. Generated `dossiers/*.md` ARE committed (reviewable on GitHub; regeneration is deterministic, so drift shows up as a git diff). Dev tools (pytest/ruff/mypy) live in a local `.venv`.

**Consequences.** Zero runtime dependencies, fully offline after corpus fetch, reproducible from a clean checkout. TOML multiline strings hold prose comfortably; `tomllib` needs no third-party parser. The plan's original "Python 3.12" was updated to the installed 3.14 — newer, supported, no downside.

---

## ADR-0005 — Name is "Omni-Framework"; "Framwork" typo eradicated · 2026-07-18 · Accepted

**Context.** The project was created with a spelling typo: folder `Omni_Framwork`, GitHub repo `Omni-Framwork`. Director mandated correction.

**Decision.** Canonical name everywhere: **Omni-Framework** (`Omni_Framework` for the folder, `MrWizard94-Compile/Omni-Framework` for the repo). GitHub repo renamed 2026-07-18 (GitHub redirects the old URL). Local folder rename is blocked while a session process holds the directory handle; executed as the final action of the founding session or by the director immediately after.

**Consequences.** Zero tolerance for the old spelling in all content going forward; ADRs and this entry are the only sanctioned historical mentions. Verification gate: repo-wide grep for the typo must return only rename-history notes.

---

## ADR-0006 — OmniLoader core architecture · 2026-07-18 · Accepted

**Context.** Phase 2 cherry-pick session over the completed 14-subject corpus; loader decisions D1–D4 (see docs/REQUIREMENTS.md §1 for the session record and §2 for the binding requirements).

**Decision.** Cached AOT transformation with per-class audit log; Mojang official names as the native namespace; single transforming class loader with logical partitions (no mandatory JPMS); extensible SAT resolution with ordering constraints and cross-ecosystem dedupe.

**Consequences.** Startup cost scales with cache hits rather than mod count; every hosted ecosystem needs a remapping step to official names (intermediary→official proven by Sinytra); compat layers mount as resolver/transform plugins. Corpus evidence cited per-requirement in REQUIREMENTS.md §2.

---

## ADR-0007 — OmniLauncher architecture · 2026-07-18 · Accepted

**Context.** Cherry-pick decisions D5–D8 (REQUIREMENTS.md §4–5).

**Decision.** Component-stack instances persisted in SQLite (mods as entities); Omni-owned static meta service from day one; all four migration importers in MVP; **full server-instance support in MVP** (director override — recommendation was to phase it; MrWizard94 chose MVP inclusion accepting the scope cost).

**Consequences.** Phase 4 scope includes server process management, server EULA flow, and console UX. The meta generator becomes a Phase 3/4 deliverable alongside the launcher. File formats of other launchers become import/export surfaces only.

---

## ADR-0008 — Target version, compat order, compliance posture · 2026-07-18 · Accepted

**Context.** Cherry-pick decisions D9–D12 (REQUIREMENTS.md §1, §6–8).

**Decision.** Minecraft 1.21.1 first with a version-agnostic core; Fabric-family compat layer before NeoForge-family; the six corpus avoid-items are binding MUST NOTs; offline play via a cached-entitlement window only.

**Consequences.** The corpus's 26.x-freshest evidence (FML, Sinytra) is applied through the version-agnostic requirement (TGT-2) rather than by moving the target; the compat SPI is validated on the cheap (metadata-level) case first; COMPLIANCE.md gains the entitlement-window mechanism as its offline policy (details in a Phase 3 ADR).
