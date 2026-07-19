<script setup lang="ts">
/**
 * Instance grid + detail (ARCHITECTURE §7).
 *
 * Mods are rendered as entities with their ecosystem and port-status badges —
 * the visible payoff of storing them as rows rather than as a folder listing
 * (LNC-2/D5). Launching goes through the account the user selected; the core's
 * CPL-2 gate is what actually authorizes it, and its typed refusal is shown
 * verbatim.
 */
import { computed, ref, watch } from "vue";
import * as api from "../api";
import type { Ecosystem, InstanceDetail, PortStatus, VersionOption } from "../api/types";
import { store, refreshInstances, reportError, toast } from "../store";

const selectedId = ref<number | null>(null);
const detail = ref<InstanceDetail | null>(null);
const creating = ref(false);
const newName = ref("");
const newVersion = ref("");
const newKind = ref<"client" | "server">("client");
const versions = ref<VersionOption[]>([]);
const versionsLoading = ref(false);
const busy = ref(false);

const selectedAccount = computed(() =>
  store.accounts.find((a) => a.uuid === store.activeAccount) ?? null,
);

async function select(id: number): Promise<void> {
  selectedId.value = id;
  try {
    detail.value = await api.instanceDetail(id);
  } catch (error) {
    detail.value = null;
    reportError(error);
  }
}

// The core announces changes; the detail pane refetches rather than patching.
watch(
  () => store.instances,
  () => {
    if (selectedId.value !== null) {
      const stillExists = store.instances.some((i) => i.id === selectedId.value);
      if (stillExists) void select(selectedId.value);
      else {
        selectedId.value = null;
        detail.value = null;
      }
    }
  },
  { deep: true },
);

async function openCreate(): Promise<void> {
  creating.value = true;
  if (versions.value.length === 0) {
    versionsLoading.value = true;
    try {
      versions.value = await api.availableVersions(true);
      newVersion.value =
        versions.value.find((v) => v.recommended)?.version ?? versions.value[0]?.version ?? "";
    } catch (error) {
      reportError(error);
    } finally {
      versionsLoading.value = false;
    }
  }
}

async function create(): Promise<void> {
  busy.value = true;
  try {
    const created = await api.createInstance(newName.value, newVersion.value, newKind.value);
    creating.value = false;
    newName.value = "";
    await refreshInstances();
    await select(created.id);
    toast("info", `Created “${created.name}”. Run Install to download its files.`);
  } catch (error) {
    reportError(error);
  } finally {
    busy.value = false;
  }
}

async function install(): Promise<void> {
  if (selectedId.value === null) return;
  busy.value = true;
  try {
    await api.startInstall(selectedId.value);
    toast("info", "Install started — watch it in Jobs.");
  } catch (error) {
    reportError(error);
  } finally {
    busy.value = false;
  }
}

async function launch(): Promise<void> {
  if (selectedId.value === null || !store.activeAccount) return;
  busy.value = true;
  try {
    await api.launchInstance(selectedId.value, store.activeAccount);
    toast("info", "Launching — output appears in Console.");
  } catch (error) {
    reportError(error);
  } finally {
    busy.value = false;
  }
}

async function acceptEula(): Promise<void> {
  if (selectedId.value === null) return;
  try {
    await api.acceptServerEula(selectedId.value);
    await select(selectedId.value);
  } catch (error) {
    reportError(error);
  }
}

async function toggleMod(modId: number, enabled: boolean): Promise<void> {
  try {
    await api.setModEnabled(modId, enabled);
  } catch (error) {
    reportError(error);
  }
}

async function remove(): Promise<void> {
  if (selectedId.value === null || !detail.value) return;
  try {
    await api.deleteInstance(selectedId.value);
    toast("info", "Instance removed from the launcher. Its files were left on disk.");
  } catch (error) {
    reportError(error);
  }
}

const ecosystemStyle: Record<Ecosystem, string> = {
  omni: "bg-indigo-500/20 text-indigo-300",
  fabric: "bg-amber-500/20 text-amber-300",
  neoforge: "bg-orange-500/20 text-orange-300",
  forge: "bg-sky-500/20 text-sky-300",
  unknown: "bg-slate-700 text-slate-300",
};

const portStyle: Record<PortStatus, string> = {
  native: "bg-emerald-500/20 text-emerald-300",
  ported: "bg-emerald-500/20 text-emerald-300",
  compat: "bg-slate-700 text-slate-300",
  excluded: "bg-rose-500/20 text-rose-300",
  unknown: "bg-slate-800 text-slate-500",
};

function formatDate(seconds: number | null): string {
  if (!seconds) return "never";
  return new Date(seconds * 1000).toLocaleDateString();
}
</script>

<template>
  <div class="flex h-full">
    <!-- Grid -->
    <section class="w-80 shrink-0 overflow-y-auto border-r border-slate-800 p-4">
      <div class="mb-3 flex items-center justify-between">
        <h2 class="text-sm font-semibold">Instances</h2>
        <button class="btn-primary" :disabled="busy" @click="openCreate">New</button>
      </div>

      <p v-if="store.instances.length === 0" class="text-sm text-slate-500">
        No instances yet. Create one, or bring your packs across from the Import tab.
      </p>

      <ul class="space-y-2">
        <li v-for="instance in store.instances" :key="instance.id">
          <button
            class="w-full rounded-lg border p-3 text-left transition-colors"
            :class="
              selectedId === instance.id
                ? 'border-indigo-500 bg-indigo-600/10'
                : 'border-slate-800 bg-slate-900 hover:border-slate-700'
            "
            @click="select(instance.id)"
          >
            <div class="flex items-start justify-between gap-2">
              <span class="truncate text-sm font-medium">{{ instance.name }}</span>
              <span
                v-if="instance.kind === 'server'"
                class="badge shrink-0 bg-sky-500/20 text-sky-300"
                >server</span
              >
            </div>
            <div class="mt-1 flex flex-wrap items-center gap-1.5 text-[11px] text-slate-500">
              <span>{{ instance.gameVersion }}</span>
              <span v-if="instance.loader" class="badge bg-slate-800 text-slate-300">
                {{ instance.loader.kind }} {{ instance.loader.version }}
              </span>
              <span>· {{ instance.modCount }} mods</span>
            </div>
          </button>
        </li>
      </ul>
    </section>

    <!-- Detail -->
    <section class="flex-1 overflow-y-auto p-6">
      <!-- Create form -->
      <div v-if="creating" class="card mb-6">
        <h3 class="mb-3 text-sm font-semibold">New instance</h3>
        <div class="grid gap-3 sm:grid-cols-3">
          <div class="sm:col-span-2">
            <label class="label" for="new-name">Name</label>
            <input id="new-name" v-model="newName" class="input mt-1" placeholder="My pack" />
          </div>
          <div>
            <label class="label" for="new-kind">Kind</label>
            <select id="new-kind" v-model="newKind" class="input mt-1">
              <option value="client">Client</option>
              <option value="server">Server</option>
            </select>
          </div>
          <div class="sm:col-span-3">
            <label class="label" for="new-version">Minecraft version</label>
            <select id="new-version" v-model="newVersion" class="input mt-1">
              <option v-if="versionsLoading" value="">Loading versions…</option>
              <option v-for="v in versions" :key="v.version" :value="v.version">
                {{ v.version }}{{ v.recommended ? " (recommended)" : "" }}
              </option>
            </select>
          </div>
        </div>
        <div class="mt-4 flex gap-2">
          <button class="btn-primary" :disabled="busy || !newVersion" @click="create">
            Create
          </button>
          <button class="btn-ghost" @click="creating = false">Cancel</button>
        </div>
      </div>

      <p v-if="!detail && !creating" class="text-sm text-slate-500">
        Select an instance to see its components and mods.
      </p>

      <template v-if="detail">
        <header class="mb-5 flex items-start justify-between gap-4">
          <div>
            <h2 class="text-lg font-semibold">{{ detail.summary.name }}</h2>
            <p class="mt-0.5 text-xs text-slate-500">
              Minecraft {{ detail.summary.gameVersion }} ·
              {{ detail.summary.kind }} · last played
              {{ formatDate(detail.summary.lastPlayedAt) }}
            </p>
          </div>
          <div class="flex shrink-0 gap-2">
            <button class="btn-ghost" :disabled="busy" @click="install">Install / Repair</button>
            <button
              class="btn-primary"
              :disabled="busy || !selectedAccount"
              :title="selectedAccount ? undefined : 'Sign in on the Accounts tab first'"
              @click="launch"
            >
              Launch
            </button>
            <button class="btn-danger" @click="remove">Delete</button>
          </div>
        </header>

        <!-- Server EULA gate (D8): explicit, never implicit. -->
        <div
          v-if="detail.summary.kind === 'server' && !detail.serverEulaAccepted"
          class="card mb-5 border-amber-800 bg-amber-950/40"
        >
          <h3 class="text-sm font-semibold text-amber-200">Minecraft EULA</h3>
          <p class="mt-1 text-xs text-amber-100/80">
            A Minecraft server can only be started once you have read and accepted the
            Minecraft End User License Agreement. Accepting here writes
            <code>eula.txt</code> for this instance and records that you agreed.
          </p>
          <div class="mt-3 flex items-center gap-3">
            <button class="btn-primary" @click="acceptEula">I accept the EULA</button>
            <a
              class="text-xs text-amber-300 underline"
              href="https://www.minecraft.net/eula"
              target="_blank"
              rel="noreferrer"
              >Read the EULA</a
            >
          </div>
        </div>

        <div class="grid gap-5 lg:grid-cols-2">
          <!-- Component stack -->
          <div class="card">
            <h3 class="mb-3 text-sm font-semibold">Components</h3>
            <table class="w-full text-sm">
              <thead>
                <tr class="text-left text-[11px] uppercase tracking-wide text-slate-500">
                  <th class="pb-1 font-medium">Component</th>
                  <th class="pb-1 font-medium">Version</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="entry in detail.stack" :key="entry.uid" class="border-t border-slate-800">
                  <td class="py-1.5 font-mono text-xs">{{ entry.uid }}</td>
                  <td class="py-1.5">
                    {{ entry.version }}
                    <span v-if="entry.pinned" class="badge ml-1 bg-slate-800 text-slate-400"
                      >pinned</span
                    >
                  </td>
                </tr>
              </tbody>
            </table>
            <p
              v-if="detail.summary.loader"
              class="mt-3 rounded border border-slate-800 bg-slate-950 p-2 text-[11px] text-slate-400"
            >
              Imported from a {{ detail.summary.loader.kind }} pack
              ({{ detail.summary.loader.version }}). The loader becomes a stack component once
              OmniLoader's compat layers ship.
            </p>
          </div>

          <!-- Mods as entities -->
          <div class="card">
            <h3 class="mb-3 text-sm font-semibold">Mods ({{ detail.mods.length }})</h3>
            <p v-if="detail.mods.length === 0" class="text-sm text-slate-500">
              No mods recorded for this instance.
            </p>
            <ul class="max-h-96 space-y-1 overflow-y-auto">
              <li
                v-for="mod in detail.mods"
                :key="mod.id"
                class="flex items-center gap-2 rounded px-2 py-1.5 hover:bg-slate-800/60"
              >
                <input
                  :id="`mod-${mod.id}`"
                  type="checkbox"
                  class="shrink-0 accent-indigo-500"
                  :checked="mod.enabled"
                  @change="toggleMod(mod.id, ($event.target as HTMLInputElement).checked)"
                />
                <label :for="`mod-${mod.id}`" class="min-w-0 flex-1 cursor-pointer">
                  <span class="block truncate text-sm">{{
                    mod.displayName ?? mod.fileName
                  }}</span>
                  <span class="block truncate text-[11px] text-slate-500">{{
                    mod.fileName
                  }}</span>
                </label>
                <span class="badge shrink-0" :class="ecosystemStyle[mod.ecosystem]">{{
                  mod.ecosystem
                }}</span>
                <span
                  v-if="mod.portStatus !== 'unknown'"
                  class="badge shrink-0"
                  :class="portStyle[mod.portStatus]"
                  >{{ mod.portStatus }}</span
                >
              </li>
            </ul>
          </div>
        </div>
      </template>
    </section>
  </div>
</template>
