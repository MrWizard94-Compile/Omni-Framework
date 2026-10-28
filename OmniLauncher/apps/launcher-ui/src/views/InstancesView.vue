<script setup lang="ts">
/**
 * Instance grid + detail (ARCHITECTURE §7).
 *
 * Mods are rendered as entities with their ecosystem and port-status badges —
 * the visible payoff of storing them as rows rather than as a folder listing
 * (LNC-2/D5). Client launches go through the selected account (CPL-2). Server
 * launches need only explicit EULA acceptance (D8) — no Microsoft account.
 */
import { computed, ref, watch } from "vue";
import * as api from "../api";
import type {
  BootIssue,
  Ecosystem,
  InstanceDetail,
  PortStatus,
  ResourceItem,
  ServerConfig,
  VersionOption,
} from "../api/types";
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
const exporting = ref(false);
const exportFormat = ref<"mrpack" | "curseforge" | "omni">("mrpack");
const exportDest = ref("");
const exportIncludeMods = ref(true);
const resources = ref<ResourceItem[]>([]);
const resourceKind = ref("resourcepack");
const serverCfg = ref<ServerConfig | null>(null);
const bootIssueList = ref<BootIssue[]>([]);

const selectedAccount = computed(() =>
  store.accounts.find((a) => a.uuid === store.activeAccount) ?? null,
);

/** Whether Launch is allowed for the current selection. */
const canLaunch = computed(() => {
  if (busy.value || selectedId.value === null || !detail.value) return false;
  if (detail.value.summary.kind === "server") {
    return detail.value.serverEulaAccepted;
  }
  return selectedAccount.value !== null;
});

const launchTitle = computed(() => {
  if (!detail.value) return undefined;
  if (detail.value.summary.kind === "server" && !detail.value.serverEulaAccepted) {
    return "Accept the Minecraft server EULA first";
  }
  if (detail.value.summary.kind === "client" && !selectedAccount.value) {
    return "Sign in on the Accounts tab first";
  }
  return undefined;
});

async function select(id: number): Promise<void> {
  selectedId.value = id;
  try {
    detail.value = await api.instanceDetail(id);
    await loadExtras(id);
  } catch (error) {
    detail.value = null;
    reportError(error);
  }
}

async function loadExtras(id: number): Promise<void> {
  try {
    resources.value = await api.listResources(id, resourceKind.value);
    bootIssueList.value = await api.bootIssues(id);
    if (detail.value?.summary.kind === "server") {
      serverCfg.value = await api.getServerConfig(id);
    } else {
      serverCfg.value = null;
    }
  } catch (error) {
    reportError(error);
  }
}

async function toggleResource(name: string, enabled: boolean): Promise<void> {
  if (selectedId.value === null) return;
  try {
    await api.setResourceEnabled(selectedId.value, resourceKind.value, name, enabled);
    resources.value = await api.listResources(selectedId.value, resourceKind.value);
  } catch (error) {
    reportError(error);
  }
}

async function saveServerCfg(): Promise<void> {
  if (selectedId.value === null || !serverCfg.value) return;
  try {
    await api.setServerConfig(selectedId.value, serverCfg.value);
    toast("info", "server.properties saved.");
  } catch (error) {
    reportError(error);
  }
}

async function refreshResourceKind(): Promise<void> {
  if (selectedId.value === null) return;
  resources.value = await api.listResources(selectedId.value, resourceKind.value);
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
  if (selectedId.value === null || !detail.value || !canLaunch.value) return;
  busy.value = true;
  try {
    // Servers pass an empty account uuid; the core ignores it for InstanceKind::Server.
    const accountUuid =
      detail.value.summary.kind === "server" ? "" : (store.activeAccount ?? "");
    await api.launchInstance(selectedId.value, accountUuid);
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

async function runExport(): Promise<void> {
  if (selectedId.value === null || !exportDest.value.trim()) return;
  busy.value = true;
  try {
    const report = await api.exportInstance(
      selectedId.value,
      exportFormat.value,
      exportDest.value.trim(),
      exportIncludeMods.value,
    );
    exporting.value = false;
    toast(
      "info",
      `Exported ${report.modsExported} mod(s) as ${report.format} → ${report.path}`,
    );
  } catch (error) {
    reportError(error);
  } finally {
    busy.value = false;
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
          <div class="flex shrink-0 flex-wrap gap-2">
            <button class="btn-ghost" :disabled="busy" @click="install">Install / Repair</button>
            <button
              class="btn-primary"
              :disabled="!canLaunch"
              :title="launchTitle"
              @click="launch"
            >
              Launch
            </button>
            <button class="btn-ghost" :disabled="busy" @click="exporting = !exporting">
              Export
            </button>
            <button class="btn-danger" @click="remove">Delete</button>
          </div>
        </header>

        <!-- Phase 5 export (ADR-0014) — core writers already ship; this is the UI surface. -->
        <div v-if="exporting" class="card mb-5">
          <h3 class="mb-3 text-sm font-semibold">Export instance</h3>
          <div class="grid gap-3 sm:grid-cols-3">
            <div>
              <label class="label" for="export-format">Format</label>
              <select id="export-format" v-model="exportFormat" class="input mt-1">
                <option value="mrpack">Modrinth (.mrpack)</option>
                <option value="curseforge">CurseForge (folder)</option>
                <option value="omni">Omni (omni.pack.json)</option>
              </select>
            </div>
            <div class="sm:col-span-2">
              <label class="label" for="export-dest">Destination path</label>
              <input
                id="export-dest"
                v-model="exportDest"
                class="input mt-1"
                :placeholder="
                  exportFormat === 'curseforge'
                    ? 'C:\\path\\to\\export-folder'
                    : 'C:\\path\\to\\pack.mrpack'
                "
              />
            </div>
            <label class="flex items-center gap-2 text-xs text-slate-300 sm:col-span-3">
              <input v-model="exportIncludeMods" type="checkbox" class="accent-indigo-500" />
              Include mod jar files (overrides / self-contained pack)
            </label>
          </div>
          <div class="mt-4 flex gap-2">
            <button
              class="btn-primary"
              :disabled="busy || !exportDest.trim()"
              @click="runExport"
            >
              Write export
            </button>
            <button class="btn-ghost" @click="exporting = false">Cancel</button>
          </div>
        </div>

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

          <!-- LNC-10 resources -->
          <div class="card lg:col-span-2">
            <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h3 class="text-sm font-semibold">Resources (LNC-10)</h3>
              <select v-model="resourceKind" class="input w-auto text-xs" @change="refreshResourceKind">
                <option value="resourcepack">Resource packs</option>
                <option value="shaderpack">Shader packs</option>
                <option value="datapack">Data packs</option>
                <option value="world">Worlds</option>
              </select>
            </div>
            <ul v-if="resources.length" class="max-h-40 space-y-1 overflow-y-auto">
              <li
                v-for="r in resources"
                :key="r.name"
                class="flex items-center gap-2 text-sm"
              >
                <input
                  type="checkbox"
                  class="accent-indigo-500"
                  :checked="r.enabled"
                  @change="toggleResource(r.name, ($event.target as HTMLInputElement).checked)"
                />
                <span class="truncate">{{ r.name }}</span>
              </li>
            </ul>
            <p v-else class="text-xs text-slate-500">No {{ resourceKind }} entries on disk.</p>
          </div>

          <!-- Server config -->
          <div v-if="serverCfg" class="card lg:col-span-2">
            <h3 class="mb-3 text-sm font-semibold">Server config</h3>
            <div class="grid gap-3 sm:grid-cols-3">
              <div>
                <label class="label">Port</label>
                <input v-model.number="serverCfg.port" type="number" class="input mt-1" />
              </div>
              <div class="sm:col-span-2">
                <label class="label">MOTD</label>
                <input v-model="serverCfg.motd" class="input mt-1" />
              </div>
              <div>
                <label class="label">Max players</label>
                <input v-model.number="serverCfg.maxPlayers" type="number" class="input mt-1" />
              </div>
              <div>
                <label class="label">Difficulty</label>
                <input v-model="serverCfg.difficulty" class="input mt-1" />
              </div>
              <div>
                <label class="label">Gamemode</label>
                <input v-model="serverCfg.gamemode" class="input mt-1" />
              </div>
              <label class="flex items-center gap-2 text-xs sm:col-span-3">
                <input v-model="serverCfg.onlineMode" type="checkbox" class="accent-indigo-500" />
                Online mode (Microsoft auth required for players)
              </label>
            </div>
            <button class="btn-primary mt-3" @click="saveServerCfg">Save server.properties</button>
          </div>

          <!-- Boot failure tree -->
          <div v-if="bootIssueList.length" class="card lg:col-span-2 border-rose-900/50">
            <h3 class="mb-3 text-sm font-semibold text-rose-200">Loader issues</h3>
            <ul class="space-y-2 text-xs">
              <li v-for="(issue, idx) in bootIssueList" :key="idx" class="rounded border border-slate-800 p-2">
                <span class="badge" :class="issue.level === 'error' ? 'bg-rose-500/20 text-rose-300' : 'bg-amber-500/20 text-amber-300'">{{
                  issue.level
                }}</span>
                <code class="ml-2 text-slate-400">{{ issue.code }}</code>
                <p class="mt-1 text-slate-300">{{ issue.message }}</p>
                <p v-if="issue.remedy" class="mt-1 text-slate-500">{{ issue.remedy }}</p>
              </li>
            </ul>
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
