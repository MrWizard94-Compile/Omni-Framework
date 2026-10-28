# AGENTS — Project Entry Point (Omni_Framework)

**Status:** Level 4 pointer — legacy local constitutions replaced  
**Pack path (relative):** `../../../AGENTS Constitution/`  
**Pack version:** see `../../../AGENTS Constitution/VERSION`

This file does **not** duplicate the constitution. Binding quality law lives in the pack.

---

## Binding pack (Level 1–3)

| Role | Path |
|------|------|
| **Constitution (SOUL)** | [../../../AGENTS Constitution/AGENTS.md](../../../AGENTS Constitution/AGENTS.md) |
| **Process (SOP)** | [../../../AGENTS Constitution/SOP.md](../../../AGENTS Constitution/SOP.md) |
| **Identity / portability** | [../../../AGENTS Constitution/PACK.md](../../../AGENTS Constitution/PACK.md) |
| **Lock** | [../../../AGENTS Constitution/LOCK.md](../../../AGENTS Constitution/LOCK.md) |
| **Adopt** | [../../../AGENTS Constitution/ADOPT.md](../../../AGENTS Constitution/ADOPT.md) |
| **Integrity** | [../../../AGENTS Constitution/INTEGRITY.md](../../../AGENTS Constitution/INTEGRITY.md) |

### Always load

1. This file (project entry)
2. Pack `AGENTS.md`
3. Pack `SOP.md`
4. Pack `constitution/03-DEFINITION-OF-DONE.md`
5. Pack `standards/ENGINEERING.md`
6. Pack `standards/TESTING.md`
7. Pack `standards/DOCUMENTATION.md`

Then load pack modules per the applicability matrix in pack `AGENTS.md`.

### Verify pack

```powershell
pwsh -File "../../../AGENTS Constitution/tools/verify-pack.ps1"
```

---

## Project-local law (Level 4)

Project onboarding detail previously in `CLAUDE.md` is archived at `CLAUDE.md.legacy-pre-pack-20260727-205752-fix`.  
Restore mission/stack/phase notes here as needed. Do not re-embed the empire SOUL monolith.