# Omni-Framework

**A next-generation Minecraft launcher (OmniLauncher) and mod loader (OmniLoader), engineered from a systematic vivisection of every launcher and loader that came before it.**

By **Wizard Productions AI Studio** ([wpaistudio.net](https://wpaistudio.net)) · Director: **MrWizard94**
Target: **Minecraft 1.21.1** first, then forward.

> **SOUL IS LAW.** Every contribution to this repository is governed by [`SOULv2.0.0.md`](SOULv2.0.0.md) — the constitution. Read it before touching anything.

---

## Vision

The modding ecosystem is fragmented across loaders (Fabric, NeoForge, Forge, Quilt) and launchers (Prism, CurseForge, Modrinth App, ATLauncher, …), each holding a piece of the best design. The Omni-Framework project:

1. **Vivisects** every open-source launcher and loader into a local intelligence database (`intelligence/`) — architecture, features, file formats, protocols, auth flows — with full provenance for every fact.
2. **Synthesizes** the best features (future-proofing score + director cherry-pick) into the Omni-Framework Requirements Spec.
3. **Builds** OmniLauncher (Tauri: Rust core + TypeScript UI) and OmniLoader (JVM, Java 21) as complete, stable, zero-warning systems.
4. **Runs the ecosystem**: existing Fabric/NeoForge/Forge mods load unmodified through runtime compatibility layers; top-tier mods get native Omni ports where licenses (or direct author permission) allow.
5. Stays **Minecraft-EULA-compliant** throughout: official Microsoft OAuth login only, no piracy, no redistribution of others' work. See [`docs/COMPLIANCE.md`](docs/COMPLIANCE.md).

## Start Here (SOUL §38)

| Read | To learn |
|------|----------|
| [`SOULv2.0.0.md`](SOULv2.0.0.md) | The constitution. Non-negotiable. |
| [`CLAUDE.md`](CLAUDE.md) | AI/contributor onboarding: conventions, current phase, commands. |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | The 8-phase program plan and current status. |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Architecture Decision Records (why things are the way they are). |
| [`docs/COMPLIANCE.md`](docs/COMPLIANCE.md) | EULA, authentication, licensing, and reverse-engineering policy. |
| [`intelligence/README.md`](intelligence/README.md) | The vivisection database and how to rebuild it. |

## Repository Map

```
Omni_Framework/
├── SOULv2.0.0.md          # The constitution — SOUL IS LAW
├── CLAUDE.md              # AI onboarding + conventions
├── docs/                  # Roadmap, ADRs, compliance
├── intelligence/          # Vivisection database, tooling, analyses, dossiers
│   ├── schema.sql         # SQLite schema (source of truth for DB structure)
│   ├── analysis/          # Hand-audited per-subject analysis files (TOML, versioned)
│   ├── dossiers/          # Generated per-subject reports (committed, reviewable)
│   ├── tools/             # ingest / analyze / dossier pipeline (Python 3.14)
│   └── corpus/            # Shallow clones of subjects (git-ignored, rebuilt on demand)
├── OmniLauncher/          # Tauri desktop launcher (implementation begins Phase 4)
└── OmniLoader/            # JVM mod loader (implementation begins Phase 4)
```

## Status

**Phase 0 — Foundation** ✅ · **Phase 1 — Vivisection Corpus** ✅ (14/14 subjects, 2026-07-18) · **Phase 2 — Feature Synthesis** ▶ next (director cherry-pick session required).

## Legal

Proprietary — Copyright © 2026 Wizard Productions AI Studio. All rights reserved. See [`LICENSE`](LICENSE).
Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
