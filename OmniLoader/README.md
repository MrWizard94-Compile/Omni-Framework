# OmniLoader

**Status: architecture specified — implementation begins Phase 4.** The binding design is **[`ARCHITECTURE.md`](ARCHITECTURE.md)** (Phase 3 output, 2026-07-18): module map, boot sequence, `omni.mod.json` v0 spec, transform cache, compat-layer SPI, Mixin integration. This directory intentionally contains no code yet per SOUL §1. This README records the high-level constraints; the architecture doc governs.

## What it will be

The Omni-Framework's mod loader for Minecraft 1.21.1 (Java 21): bootstrap, mod discovery, class transformation, and — its defining feature — **runtime compatibility layers** that load existing Fabric, NeoForge, and Forge mods unmodified, plus a native Omni mod format for new development and licensed ports.

## Locked constraints (ADRs)

- **Runtime**: JVM, Java 21 (Minecraft 1.21.1's requirement). Exact toolchain (Gradle setup, mappings strategy) decided in Phase 3 from vivisection evidence.
- **Compat-first** (ADR-0003): third-party jars are loaded unmodified at runtime on the user's machine — no redistribution, no derivatives. Sinytra Connector is the load-bearing prior art and a Phase 1 vivisection subject.
- **Native ports** are permission-gated per `../docs/COMPLIANCE.md` §4.
- **Low-level discipline**: mixins/bytecode work under SOUL §23 — least-invasive approach, documented justification, version-checked, no untracked hacks.

## Inputs it is waiting on

1. Phase 1 complete: loader architecture inventory (Fabric ✅, Quilt, FancyModLoader, Forge, Mixin, Sinytra).
2. Phase 2: director cherry-pick → `../docs/REQUIREMENTS.md`.
3. Phase 3: ADRs for bootstrap chain, mod metadata format, transformation pipeline, compat-layer SPI.
