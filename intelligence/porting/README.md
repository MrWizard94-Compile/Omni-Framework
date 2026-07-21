# Phase 7 — Port queue foundation (ADR-0023)

Human-gated native-port intake from the Phase 6 census. **Compat layers remain
the coverage path** for everything not (yet) ported (ADR-0003). A hard author
**no** permanently ends native porting for that project.

## What this brick is

| Piece | Role |
|---|---|
| `schema.sql` | `omni_port.db` — queue, outreach trail, intake batches |
| `tools/port_lib.py` | License classifier + intake + draft/respond |
| `tools/port_queue.py` | CLI |
| `templates/port_permission_v1.md` | Outreach draft (never auto-sent) |

## What this brick is **not** (honest SOUL boundary)

- Not an autonomous code rewriter / mod porter
- Not a mailer (drafts only; humans send)
- Not a publish pipeline

Those are later Phase 7 bricks once this queue is fed by a frozen C4 census.

## Commands

```powershell
cd intelligence

# After Phase 6 export (C4): pull S+A tiers into the queue
.venv\Scripts\python tools\port_queue.py intake --batch-label c4-1.21.1 --tiers S,A

# Draft permission emails for rows that need outreach (review before sending)
.venv\Scripts\python tools\port_queue.py draft --limit 20

# Record an author decision (yes permanently enables; no permanently refuses)
.venv\Scripts\python tools\port_queue.py respond --outreach-id 1 --response yes

.venv\Scripts\python tools\port_queue.py status
.venv\Scripts\python tools\port_queue.py export --out porting\queue_export.json
```

`omni_port.db` and `queue_export.json` are git-ignored operational artifacts.

## License classes

| Class | Native port | Outreach |
|---|---|---|
| `permissive` (MIT, Apache-2.0, BSD, …) | eligible | not required |
| `weak_copyleft` (LGPL, MPL, …) | eligible w/ obligations | recommended |
| `strong_copyleft` (GPL, AGPL) | not auto-eligible | required |
| `all_rights` / proprietary | not auto-eligible | required |
| `unknown` | hold | human classify |

## QA

```powershell
.venv\Scripts\python -m pytest tests\test_port_queue.py
.venv\Scripts\python -m ruff check tools\port_lib.py tools\port_queue.py tests\test_port_queue.py
.venv\Scripts\python -m mypy tools\port_lib.py tools\port_queue.py
```
