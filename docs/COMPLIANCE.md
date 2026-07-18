# Compliance — EULA, Authentication, Licensing, Reverse Engineering

**Non-negotiable.** This document defines the legal operating envelope of the Omni-Framework. It implements SOUL §9 (security mindset) and §36 (secrets) for this project's specific risk surface. When any task conflicts with this document, the task stops and the director is consulted.

## 1. Minecraft EULA & Usage Guidelines

The Omni-Framework must always satisfy the [Minecraft EULA](https://www.minecraft.net/en-us/eula) and Mojang's Usage Guidelines:

- **No piracy.** OmniLauncher launches the game only for accounts that own Minecraft: Java Edition. Ownership is verified via the official entitlement/profile endpoints after login. No "offline mode" that bypasses ownership, no credential sharing, no distribution of Mojang's game files beyond what the official launcher-metadata pipeline serves to any launcher.
- **Mods, not the game.** We distribute only our own original code (launcher, loader, compat layers, native ports we are licensed to make). Vanilla jars, assets, and libraries are downloaded by the user's launcher instance from Mojang's official CDNs (`piston-meta.mojang.com`, `resources.download.minecraft.net`, `libraries.minecraft.net`) exactly as every compliant launcher does.
- **Naming/branding.** "Minecraft" is Mojang Synergies AB's trademark. All published materials carry: *"Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft."*

## 2. Authentication — the CurseForge-identical path

Login is handled **exactly the way CurseForge/Prism/Modrinth do it**: the officially documented Microsoft identity platform flow. Reference chain (documented publicly at wiki.vg/Microsoft_Authentication and learn.microsoft.com):

```
1. Microsoft OAuth 2.0 authorization-code (PKCE) or device-code flow
       → Microsoft access token          (user consents in Microsoft's own UI)
2. Xbox Live user authentication         (user.auth.xboxlive.com)
       → XBL token
3. XSTS authorization                    (xsts.auth.xboxlive.com)
       → XSTS token  (handle XErr cases: no Xbox account, child account, region)
4. Minecraft services login              (api.minecraftservices.com/authentication/login_with_xbox)
       → Minecraft access token
5. Entitlement + profile check           (api.minecraftservices.com/entitlements/mcstore, /minecraft/profile)
       → UUID, username, ownership proof
```

**Hard rules:**
- We **never** see, transmit, store, or prompt for a Microsoft/Mojang password. Consent happens in Microsoft's surfaces only.
- Tokens are stored in the OS credential vault (Windows Credential Manager via Tauri's keyring path), never in plaintext files, never in logs (SOUL §16/§36).
- Refresh tokens are used for silent re-auth; failure falls back to a fresh interactive login.

**External action item (director):** register an Azure application (personal Microsoft accounts audience) and submit Mojang's third-party-launcher approval form so the client ID may call the Minecraft services API. Tracked in `ROADMAP.md`. Until approval, development uses the documented device-code flow against a development client ID with the director's own account.

## 3. Reverse-Engineering Policy (ADR-0002)

- **Open-source subjects** (Fabric, Quilt, NeoForge, Forge, Mixin, Sinytra, Prism, MultiMC, ATLauncher, HMCL, Modrinth App, GDLauncher): full source vivisection at pinned commits, under their own licenses. We record facts and original analysis with provenance; we do not copy their code into our products. Where a subject's license would even restrict *that* (rare), the license is recorded in the DB and respected.
- **Proprietary subjects** (CurseForge app, official Minecraft launcher): black-box only — public documentation, published API surfaces, on-disk file layouts of our own legitimate installations. **No decompilation, no bytecode/binary analysis, no ToS-violating probing.**

## 4. Third-Party Mod Licensing (ADR-0003)

- **Compat layers**: OmniLoader loads user-supplied Fabric/NeoForge/Forge jars **unmodified, at runtime, on the user's machine**. No redistribution, no derivative works → legal for every license, identical in kind to what OptiFine-compat and Sinytra Connector established.
- **Native ports**: produced only when (a) the mod's license explicitly permits derivative works/redistribution (MIT, Apache-2.0, LGPL, MPL, CC-BY, …), or (b) the author grants **written permission** after direct outreach. Permission records are stored in the intelligence DB. A hard "no" is final and recorded.
- **Attribution**: every native port credits the original author and links the original project, regardless of license minimums.

## 5. Distribution & Platform Rules

- CurseForge/Modrinth API usage (Phase 6 census) follows each platform's API terms: authenticated API keys, rate limits respected, no scraping around the API, no re-hosting of mod files that authors have not permitted (CurseForge's per-project "allow third-party launchers" flag is honored).
- Mod downloads in OmniLauncher go through the platforms' official APIs/CDNs so authors keep their download counts and rewards.

## 6. Enforcement

Every delivery's SOUL §0 self-audit includes a compliance pass against this document. Any feature request that cannot be implemented inside this envelope is escalated to the director with the specific conflict named — never silently implemented.
