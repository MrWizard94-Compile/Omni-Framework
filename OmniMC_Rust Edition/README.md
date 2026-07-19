# OmniMC — an original voxel game, pure native Rust

**Status: chartered (ADR-0017, 2026-07-19) — design not started.** The Phase 4 MVP of
the Omni-Framework (OmniLauncher/OmniLoader for real Minecraft) is the critical path;
OmniMC is the parallel track that begins its own architecture phase after the MVP ships.

## Charter (binding — see `../docs/DECISIONS.md` ADR-0017)

OmniMC is **not** a Minecraft rebuild, clone, or substitute. It is a fully original
voxel game by Wizard Productions AI Studio:

- **Original everything**: code, assets, sounds, name, world and content identity.
- **Clean-room**: no vanilla source, decompiled output, or Mojang mappings as
  implementation reference (ADR-0002 discipline).
- **Engine direction**: pure Rust; wgpu rendering (multidraw indirect chunk meshing);
  decoupled simulation/render/IO loops with strict concurrency guarantees.
- **Modding**: first-class, sandboxed, language-agnostic **WASM mod API** — the
  ecosystem grows by author consent through the ADR-0003 outreach pipeline.
- **Out of scope**: Java-mod interop of any kind, absent a future dedicated ADR with
  explicit legal review.

"Minecraft" is a trademark of Mojang Synergies AB. OmniMC is not an official Minecraft
product and makes no compatibility claims.
