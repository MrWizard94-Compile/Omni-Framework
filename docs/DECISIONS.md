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

---

## ADR-0009 — Mixin distribution: pinned upstream 0.8.7-line build · 2026-07-18 · Accepted

**Context.** LDR-8 requires one bundled Mixin runtime. Options: track upstream SpongePowered (slow releases), adopt Fabric's fork (couples us to their cadence), or maintain an Omni build.

**Decision.** Bundle a pinned build of upstream SpongePowered Mixin (0.8.7 line, the ecosystem-wide baseline per the spongepowered-mixin dossier). Any patches Omni needs are maintained as a rebased patch set in-monorepo. Version bumps require a dedicated ADR with a compat-level impact assessment; per-config compat levels preserve old-mod behavior across bumps.

**Consequences.** No dependency on another loader's fork cadence; patch-set maintenance is Omni's cost; `MixinServiceOmni` (OmniLoader/ARCHITECTURE.md §8) is the single integration point so a future runtime swap stays contained.

---

## ADR-0010 — Transform-cache key, layout, and mapping artifacts · 2026-07-18 · Accepted

**Decision.** Cache key = SHA-256 over (jar bytes, mappings artifact id+hash, pipeline version, compat-layer version, config-relevant flags). Layout `<data>/transform-cache/<k[0:2]>/<key>/` with output jar + `audit.json` + `meta.json`; LRU + version-sweep GC; `--audit <class>` query surface. Mapping/translation data (official mappings per game version; intermediary→official for compat-fabric; SRG deferred) ship as versioned, hash-addressed artifacts through OmniMeta — never bundled into the loader jar.

**Consequences.** Byte-identical inputs never re-transform; any input change invalidates precisely; audit answers "who changed this class" (SOUL §16). Full detail in OmniLoader/ARCHITECTURE.md §6.

---

## ADR-0011 — Entitlement window: 14 days, vault-anchored · 2026-07-18 · Accepted

**Context.** CPL-2 requires offline play for verified owners within an explicit window.

**Decision.** Window = **14 days** from last successful entitlement verification. The verification record (timestamp + account binding) lives beside the tokens in the OS credential vault; the DB stores only the expiry timestamp for scheduling. Every online launch re-verifies opportunistically and slides the window; expiry blocks launch until re-auth. No configuration to extend it beyond 14 days.

**Consequences.** Flaky-connection owners play (SOUL §20 offline-first); revoked accounts age out within two weeks; the policy is auditable and defensible under COMPLIANCE §1. Directly implements the prism-launcher dossier's flagged gap with explicit semantics.

---

## ADR-0012 — Meta hosting: Cloudflare R2 + CDN, static-only · 2026-07-18 · Accepted

**Decision.** OmniMeta generator output publishes to Cloudflare R2 behind the CDN; no compute in the serving path; domain is a build-config value (placeholder `meta.omniframework.wpaistudio.net`, director confirms at Phase 4 deploy). Roots short-TTL + hashed; payloads immutable.

**Consequences.** Serving availability = CDN availability; costs near-zero at static scale; the (prism-launcher) `Launcher_META_URL` pattern keeps the URL swappable. Spec: OmniMeta/README.md.

---

## ADR-0013 — Native-mod SDK: in-monorepo Gradle plugin + template · 2026-07-18 · Accepted

**Decision.** `omni-sdk/` (Phase 4/5): a Gradle plugin (toolchain pinning, `omni.mod.json` validation at build, refmap generation, run configs against a dev instance) plus a template mod. Versioned with the loader in this monorepo (minecraftforge MDK finding: loader and mod-dev tooling as one unit).

**Consequences.** Native mod authors get a one-command start; manifest validation runs at mod-build time, catching errors before runtime; SDK releases ride the loader's cadence.

---

## ADR-0014 — Export scope: .mrpack + CurseForge manifest, Phase 5 · 2026-07-18 · Accepted

**Decision.** Instance/pack export ships in Phase 5: `.mrpack` and CurseForge `manifest.json` writers (the two platform lingua francas), plus Omni's native format. Import remains the MVP surface (D7); export completes the bidirectional-migration trust posture (atlauncher finding; MUST NOT #6 rationale).

**Consequences.** Phase 5 scope includes format-writer golden tests; the MUST-NOT-restrictive posture gains its concrete mechanism.

---

## ADR-0015 — omni-core persistence: rusqlite (bundled), sync core, migration pattern · 2026-07-19 · Accepted

**Context.** LNC-2 mandates one SQLite database with "sqlx-style migrations". sqlx the *crate* brings an async runtime, macro-driven query checking, and heavy compile costs into the foundation crate; the architecture's actual requirements are versioned migrations, typed access, and testability.

**Decision.** `rusqlite` 0.40 with the `bundled` SQLite (zero system dependency — reproducible, offline, SOUL §21/§33) plus `backup` and `serde_json` features. "sqlx-style" is implemented as the **pattern**: numbered SQL files embedded at compile time, applied forward-only in per-migration transactions, tracked in `schema_migrations`. The core stays **synchronous**: one mutex-guarded WAL connection; the Tauri shell and job workers call from their own threads (coarse lock revisited only on profiling evidence, SOUL §8). Conventions: timestamps INTEGER Unix seconds UTC; JSON documents in TEXT columns; every CHECK-constrained column mirrored by a typed Rust enum that rejects unknown values instead of defaulting. Job rows move only through a guarded state machine (`pending→running→{paused,failed,done}`, resume via `paused/failed→running`), which is what makes `resume_json` trustworthy.

**Consequences.** No async contagion in omni-core; bundled SQLite pins the exact database version for every user; migration history is append-only like this file. Cost: a future need for async DB access requires wrapping (spawn-blocking) rather than native async queries — accepted for the MVP.

---

## ADR-0016 — Meta hostname confirmed: `omnimeta.wpaistudio.net`; auth client ID recorded · 2026-07-19 · Accepted

**Context.** ADR-0012 left the meta hostname a placeholder (`meta.omniframework.wpaistudio.net`, two-level). During live setup (director + AI session, 2026-07-19) it surfaced that Cloudflare Universal SSL covers only one subdomain level — a two-level name would force Advanced Certificate Manager (~$10/month) for zero benefit.

**Decision.** Meta service hostname: **`omnimeta.wpaistudio.net`** (director-selected). Provisioned same session: R2 subscription activated, bucket `omni-meta` (Standard, ENAM), custom domain bound (auto-CNAME + cert), minimum TLS 1.2. The Azure app registration for LNC-4 auth also exists and is compliant: **client ID `ff86f866-0ef7-4208-aa66-c17d8124dcc3`** ("OmniLauncher", personal-Microsoft-accounts audience, no client secret, public-client loopback redirect `http://127.0.0.1`, "Allow public client flows" enabled). The client ID is public-by-design (embedded in every launcher build) and is recorded here as the build-config source of truth until the launcher config file lands (brick 4).

**Consequences.** Brick 9's deploy target exists; the hostname stays a build-config value (the ADR-0012 swap-friendliness is unchanged — this record supersedes only the placeholder detail). Live end-to-end login testing remains gated on Mojang's third-party-launcher approval of the client ID (ROADMAP external dependencies).

---

## ADR-0017 — OmniMC: original pure-Rust voxel game, parallel track · 2026-07-19 · Accepted

**Context.** The director proposed rebuilding Minecraft in Rust (later framed as an engine plus a Java-mod-to-WASM translation layer). The compliance escalation (2026-07-19 session, COMPLIANCE §6) established: a reimplementation under any name reproduces Mojang content/behavior and endangers the Framework's EULA-clean posture and the pending launcher allowlist approval; and a Java-mod compat layer on a foreign engine silently re-imports the whole problem, because mods require Minecraft's API surface and behavioral semantics (plus research-grade technical risk — Mixin's runtime patching does not survive AOT-to-WASM; no working precedent exists).

**Decision (director).** The original plan stands: OmniLauncher/OmniLoader serve real Minecraft and unify its ecosystem — Phases 4–7 remain the critical path, unchanged. In parallel, **OmniMC** is developed as a **pure native Rust, fully original voxel game**. Binding guardrails:

1. **Original content only** — no Mojang assets, textures, sounds, music, or close clones of their expression; original name, world identity, and content.
2. **Clean-room** — no vanilla source, decompiled output, or Mojang mappings as implementation reference (the ADR-0002 discipline extends to OmniMC).
3. **Mod API**: first-class, sandboxed, language-agnostic **WASM modding** is the recommended native format; ecosystem growth runs through the ADR-0003 permission/outreach pipeline (authors port by consent).
4. **Java-mod interop is out of scope** and may only enter through a future dedicated ADR with explicit legal review.
5. **Branding** carries no Minecraft trademark and no compatibility claims; showcase materials present engine benchmarks and the open modding platform, never substitute-product claims.

**Consequences.** OmniMC receives its own architecture phase (engine direction: wgpu rendering with multidraw indirect, decoupled simulation/render/IO loops) gated behind Phase 4 MVP completion for capacity; `OmniMC_Rust Edition/` is chartered by its README pointing here; the two products complement rather than endanger each other — real-Minecraft modding through the Framework, original modding through OmniMC.

---

## ADR-0018 — Resolution mechanism: bespoke backtracking search, not pseudo-boolean SAT · 2026-07-20 · Accepted

**Supersedes** the mechanism clause of LDR-5 / decision D4 (REQUIREMENTS.md §2) and the "SAT resolver" cell in OmniLoader/ARCHITECTURE.md §2. The behavioural clauses of LDR-5 are unchanged and are all delivered.

**Context.** LDR-5 specifies "pseudo-boolean SAT resolution ... and unsat-core → human-quality error rendering", derived from the corpus (fabric-loader and quilt-loader both relocate Sat4j). Implementing brick 11 surfaced a tension between the two halves of that sentence. A SAT solver answers *whether* a satisfying assignment exists and, on failure, returns an unsat core as a set of clauses over encoded variables. But the requirement's stated purpose is a message a player can act on. Recovering "Create needs Flywheel 1.0 or newer, but the copy bundled inside Valkyrien Skies is 0.6" from clause indices means maintaining a full reverse mapping from every encoded variable back to the mod, dependency, and candidate that produced it — the error-rendering problem is not solved by the solver, only relocated behind it.

Searching the constraint graph directly keeps every failure at the point where the loader still knows what it was attempting, so the message is written from live domain objects rather than reconstructed.

**Decision.** `loader-resolve` implements resolution as a **bespoke backtracking constraint search** over mod candidates, not as a reduction to pseudo-boolean SAT. No SAT library is vendored. Specifically:

1. Candidates are tried **newest-first**, with user-installed copies beating bundled ones at equal version; ids are visited in a stable order, so resolution is **deterministic** across runs.
2. Constraints are enforced **symmetrically** — a candidate must satisfy the requirements of already-selected mods, not merely have its own satisfied.
3. The **common failures** (missing dependency, version mismatch, explicit conflict) are detected *before* search so their messages name the mod, the requirement, and what is actually installed.
4. A genuine search failure is narrowed to a **minimal unsatisfiable subset** by deletion — one re-solve per root — so the error names only mods that are actually in conflict, never bystanders. This is LDR-5's "unsat core", delivered in domain terms.
5. Ordering is a separate pass with **hard explicit edges** (`before`/`after`: a cycle is a reported error) and **soft implied edges** (`depends`: broken to resolve a cycle, with a warning), because the canonical model deliberately separates requirement from ordering and mutual requirement is legal.

Every behavioural clause of LDR-5 remains satisfied: per-ecosystem rule plugins (the `ResolverRule` SPI), BEFORE/AFTER ordering constraints, cross-ecosystem identity/provides dedupe, and unsat-core → human-quality error rendering.

**Consequences.**

- *Accepted risk:* chronological backtracking has no conflict-driven clause learning, so its worst case is worse than Sat4j's on adversarially-structured inputs. This is a real difference, not a wash.
- *Measured, not assumed:* a seeded 448-candidate fixture — 400 content mods over 24 libraries each bundled at two competing versions, with roughly one mod in eight declaring `breaks` against its library's 2.x line so the search must genuinely back out of its newest-first choice — resolves in **~42 ms**. That is above the 324 mods of the ATM10 pack used for launcher validation. `ResolverScaleTest` keeps this honest with a loose 10-second tripwire that exponential blowup would blow past rather than nudge, and asserts as a property that whatever assignment the search returns violates no declared rule (ARCHITECTURE §11).
- *Revisit trigger:* if a real pack is ever measured resolving in more than ~2 seconds, or the minimal-subset core proves unhelpful in practice, revisit with a superseding ADR. Because the search is behind `Resolver`'s public surface, swapping the mechanism would not change callers.
- The unsat-core path costs one re-solve per root. It runs only on failure, where correctness of the message outweighs the milliseconds.
- Corpus divergence is deliberate and documented here so it is not later mistaken for an oversight.
