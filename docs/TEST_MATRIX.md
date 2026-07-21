# Test matrix — Phase 5 stabilization

The single place that lists every QA gate the monorepo must pass before a
delivery (SOUL Pre-Delivery Gate §3–4). Commands are PowerShell-friendly; run
from the repo root unless noted.

## Always (every delivery that touches the area)

| Area | Gate | Command | Pass criterion |
|------|------|---------|----------------|
| Rust workspace | unit + integration | `cargo test --workspace` | all green |
| Rust workspace | lints | `cargo clippy --workspace --all-targets` | **silent** (all + pedantic) |
| OmniLoader | build + tests | `cd OmniLoader; .\gradlew build` | green, `-Werror` |
| Intelligence | unit | `cd intelligence; .venv\Scripts\python -m pytest` | all green |
| Intelligence | lint | `cd intelligence; .venv\Scripts\python -m ruff check .` | silent |
| Intelligence | types | `cd intelligence; .venv\Scripts\python -m mypy tools` | silent |
| Launcher UI | typecheck + bundle | `cd OmniLauncher\apps\launcher-ui; npm run build` | vue-tsc clean + vite ok |

## By surface (property under test)

### OmniMeta
- Golden tree byte-stability (`omni-meta-gen` tests)
- Hash-chain verify of published objects
- Deploy dry-run ordering / cache headers (unit)

### OmniLauncher / omni-core
- Schema migrations + store CHECKs
- Job resume from every interrupt point
- Auth chain against fixtures (never live secrets in CI)
- Importers (synthetic + optional real-pack `#[ignore]`)
- **Export writers** (Phase 5): omni / curseforge / mrpack golden shape (`tests/export.rs`)
- IPC wire shapes camelCase + event tags (`tests/app.rs`)
- Launch gate: no account / offline window / ready

### OmniLoader
- Model parse (golden + adversarial manifests)
- Resolver (property: solutions satisfy rules; unsat cores minimal)
- Transform cache key / atomic write
- Mixin service registration packaged in the artifact
- Bootstrap headless composition

### Intelligence / census
- Vivisection TOML provenance enforcement
- Deterministic DB rebuild
- Census tier determinism + mocked platform clients (no live network in CI)

## Live / director-only (not CI)

| Probe | How | Notes |
|-------|-----|-------|
| MSA full chain | Accounts surface or `auth_probe` example | Needs Mojang-approved client ID |
| Vanilla 1.21.1 launch | `docs/FIRST_RUN.md` | Human eyes on the window |
| Meta CDN | `omni-meta-deploy` + public URL verify | Uses R2 credentials in env |
| Full CF census | `census_index.py fetch --platforms curseforge` | Needs **third-party** CF API key |
| Real pack import | ATM10 path in import tests `--ignored` | Machine-local fixture |

## Release engineering cross-check

Before tagging a release (see `docs/RELEASE.md`):

1. Full matrix above green on a clean checkout.
2. `scripts/checksum-release.ps1` over published artifacts.
3. `CHANGELOG.md` entry for the version.
4. Upgrade notes (if any schema/format bump) listed in the release section.
