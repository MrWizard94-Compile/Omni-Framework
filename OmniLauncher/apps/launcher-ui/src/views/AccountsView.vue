<script setup lang="ts">
/**
 * Account manager (ARCHITECTURE §7, LNC-4).
 *
 * COMPLIANCE §2 shapes this surface: the launcher never asks for a password
 * and never shows a Microsoft login form. It displays a device code, the user
 * consents in Microsoft's own page, and the core reports the outcome as an
 * event. The entitlement window (ADR-0011) is shown honestly, including how
 * long offline play remains available.
 */
import { ref } from "vue";
import * as api from "../api";
import type { DeviceCode } from "../api/types";
import { store, setActiveAccount, refreshAccounts, reportError } from "../store";

const prompt = ref<DeviceCode | null>(null);
const busy = ref(false);
const copied = ref(false);

async function beginLogin(): Promise<void> {
  busy.value = true;
  copied.value = false;
  try {
    prompt.value = await api.startDeviceLogin();
  } catch (error) {
    reportError(error);
  } finally {
    busy.value = false;
  }
}

async function copyCode(): Promise<void> {
  if (!prompt.value) return;
  try {
    await navigator.clipboard.writeText(prompt.value.userCode);
    copied.value = true;
  } catch {
    // Clipboard access can be denied; the code is on screen regardless.
    copied.value = false;
  }
}

async function remove(uuid: string): Promise<void> {
  try {
    await api.signOut(uuid);
    if (prompt.value) prompt.value = null;
    await refreshAccounts();
  } catch (error) {
    reportError(error);
  }
}

function windowText(expiresAt: number | null): string {
  if (!expiresAt) return "not verified yet";
  const remainingMs = expiresAt * 1000 - Date.now();
  if (remainingMs <= 0) return "offline window expired — sign in again to play";
  const days = Math.floor(remainingMs / 86_400_000);
  const hours = Math.floor((remainingMs % 86_400_000) / 3_600_000);
  return days > 0
    ? `offline play available for ${days}d ${hours}h`
    : `offline play available for ${hours}h`;
}
</script>

<template>
  <div class="p-6">
    <header class="mb-5 flex items-center justify-between">
      <div>
        <h2 class="text-lg font-semibold">Accounts</h2>
        <p class="mt-0.5 text-xs text-slate-500">
          Sign-in happens on Microsoft's own site. OmniLauncher never sees your password.
        </p>
      </div>
      <button class="btn-primary" :disabled="busy" @click="beginLogin">Add account</button>
    </header>

    <!-- Device-code panel -->
    <div v-if="prompt" class="card mb-5 border-indigo-800 bg-indigo-950/30">
      <h3 class="text-sm font-semibold text-indigo-200">Finish signing in</h3>
      <ol class="mt-2 space-y-2 text-sm text-slate-300">
        <li>
          1. Open
          <a
            class="text-indigo-300 underline"
            :href="prompt.verificationUri"
            target="_blank"
            rel="noreferrer"
            >{{ prompt.verificationUri }}</a
          >
        </li>
        <li class="flex items-center gap-2">
          2. Enter this code:
          <code class="rounded bg-slate-950 px-2 py-1 font-mono text-base tracking-widest">{{
            prompt.userCode
          }}</code>
          <button class="btn-ghost text-xs" @click="copyCode">
            {{ copied ? "Copied" : "Copy" }}
          </button>
        </li>
        <li>3. Come back here — this page updates itself when you're done.</li>
      </ol>
      <p class="mt-3 text-[11px] text-slate-500">
        The code expires in about {{ Math.round(prompt.expiresInSecs / 60) }} minutes.
      </p>
    </div>

    <p v-if="store.accounts.length === 0" class="text-sm text-slate-500">
      No accounts yet. You need a Microsoft account that owns Minecraft: Java Edition.
    </p>

    <ul class="space-y-2">
      <li
        v-for="account in store.accounts"
        :key="account.uuid"
        class="card flex items-center justify-between gap-4"
      >
        <div class="min-w-0">
          <div class="flex items-center gap-2">
            <span class="truncate text-sm font-medium">{{ account.username }}</span>
            <span
              v-if="store.activeAccount === account.uuid"
              class="badge bg-indigo-500/20 text-indigo-300"
              >launch account</span
            >
            <span
              class="badge"
              :class="
                account.entitlementValid
                  ? 'bg-emerald-500/20 text-emerald-300'
                  : 'bg-rose-500/20 text-rose-300'
              "
              >{{ account.entitlementValid ? "verified" : "needs sign-in" }}</span
            >
          </div>
          <p class="mt-0.5 truncate font-mono text-[11px] text-slate-500">{{ account.uuid }}</p>
          <p class="mt-0.5 text-[11px] text-slate-500">
            {{ windowText(account.windowExpiresAt) }}
          </p>
        </div>
        <div class="flex shrink-0 gap-2">
          <button
            v-if="store.activeAccount !== account.uuid"
            class="btn-ghost"
            @click="setActiveAccount(account.uuid)"
          >
            Use for launch
          </button>
          <button class="btn-danger" @click="remove(account.uuid)">Sign out</button>
        </div>
      </li>
    </ul>
  </div>
</template>
