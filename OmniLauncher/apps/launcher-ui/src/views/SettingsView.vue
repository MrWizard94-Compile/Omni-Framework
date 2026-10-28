<script setup lang="ts">
/**
 * Settings (ARCHITECTURE §7).
 *
 * Global preferences plus the provenance panel — the launcher states plainly
 * where its metadata comes from and which app registration it authenticates
 * with, because a launcher asking for a Microsoft sign-in owes the user that
 * transparency (COMPLIANCE §2).
 */
import { onMounted, ref } from "vue";
import * as api from "../api";
import type { JavaRuntime } from "../api/types";
import { reportError, toast } from "../store";

const defaultMemory = ref(4096);
const releasesOnly = ref(true);
const cfApiKey = ref("");
const saving = ref(false);
const javas = ref<JavaRuntime[]>([]);

onMounted(async () => {
  try {
    const memory = await api.getSetting("defaultMemoryMib");
    if (typeof memory === "number") defaultMemory.value = memory;
    const releases = await api.getSetting("showReleasesOnly");
    if (typeof releases === "boolean") releasesOnly.value = releases;
    const key = await api.getSetting("cfApiKey");
    if (typeof key === "string") cfApiKey.value = key;
  } catch (error) {
    reportError(error);
  }
});

async function save(): Promise<void> {
  saving.value = true;
  try {
    await api.setSetting("defaultMemoryMib", defaultMemory.value);
    await api.setSetting("showReleasesOnly", releasesOnly.value);
    await api.setSetting("cfApiKey", cfApiKey.value.trim() || null);
    toast("info", "Settings saved.");
  } catch (error) {
    reportError(error);
  } finally {
    saving.value = false;
  }
}

async function scanJava(): Promise<void> {
  try {
    javas.value = await api.discoverJavaRuntimes(true);
    toast("info", `Found ${javas.value.length} system JVM(s).`);
  } catch (error) {
    reportError(error);
  }
}
</script>

<template>
  <div class="max-w-2xl p-6">
    <header class="mb-5">
      <h2 class="text-lg font-semibold">Settings</h2>
    </header>

    <div class="card mb-5 space-y-4">
      <div>
        <label class="label" for="memory">Default heap for new instances (MiB)</label>
        <input
          id="memory"
          v-model.number="defaultMemory"
          type="number"
          min="1024"
          max="65536"
          step="512"
          class="input mt-1"
        />
        <p class="mt-1 text-[11px] text-slate-500">
          Applies to instances created from now on. Existing instances keep their own value.
        </p>
      </div>

      <div class="flex items-center gap-2">
        <input
          id="releases"
          v-model="releasesOnly"
          type="checkbox"
          class="accent-indigo-500"
        />
        <label for="releases" class="text-sm">Only offer release versions when creating</label>
      </div>

      <div>
        <label class="label" for="cfkey">CurseForge Core API key (optional)</label>
        <input
          id="cfkey"
          v-model="cfApiKey"
          type="password"
          class="input mt-1"
          placeholder="Stored only in local settings DB"
          autocomplete="off"
        />
        <p class="mt-1 text-[11px] text-slate-500">
          Enables CF search and murmur2 fingerprint lookup. Never committed; env
          <code>CF_API_KEY</code> also works for tools.
        </p>
      </div>

      <button class="btn-primary" :disabled="saving" @click="save">Save</button>
    </div>

    <div class="card mb-5 space-y-3">
      <h3 class="text-sm font-semibold">System Java (LNC-6)</h3>
      <button class="btn-ghost" @click="scanJava">Scan for JVMs</button>
      <ul v-if="javas.length" class="space-y-1 text-xs text-slate-400">
        <li v-for="j in javas" :key="j.path" class="font-mono">
          {{ j.version }} · {{ j.vendor ?? "?" }} · {{ j.arch }} · {{ j.path }}
        </li>
      </ul>
    </div>

    <div class="card space-y-3 text-xs text-slate-400">
      <h3 class="text-sm font-semibold text-slate-200">About</h3>
      <p>
        OmniLauncher is part of the Omni-Framework by Wizard Productions AI Studio.
      </p>
      <p>
        Game files are downloaded from Mojang's official CDNs. Version metadata comes from the
        Omni meta service. Mod files come from the platforms' official APIs, so authors keep
        their download counts.
      </p>
      <p>
        Sign-in uses the official Microsoft identity platform flow. The launcher never sees or
        stores your password; tokens are held in your operating system's credential vault.
      </p>
      <p class="text-slate-500">
        Not an official Minecraft product. Not approved by or associated with Mojang or
        Microsoft. "Minecraft" is a trademark of Mojang Synergies AB.
      </p>
    </div>
  </div>
</template>
