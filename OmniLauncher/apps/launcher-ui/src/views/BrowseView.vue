<script setup lang="ts">
/**
 * Content browse over omni-platform (LNC-9): search Modrinth + CurseForge.
 */
import { ref } from "vue";
import * as api from "../api";
import type { ContentHit, InstanceSummary } from "../api/types";
import { store, reportError, toast } from "../store";

const query = ref("");
const gameVersion = ref("1.21.1");
const hits = ref<ContentHit[]>([]);
const busy = ref(false);
const targetId = ref<number | null>(null);

async function search(): Promise<void> {
  busy.value = true;
  try {
    hits.value = await api.searchContent(query.value, gameVersion.value || null, 25);
  } catch (error) {
    reportError(error);
  } finally {
    busy.value = false;
  }
}

async function install(hit: ContentHit): Promise<void> {
  if (targetId.value === null) {
    toast("info", "Select a target instance first.");
    return;
  }
  busy.value = true;
  try {
    const path = await api.installBrowseHit(
      targetId.value,
      hit.platform,
      hit.projectId,
      gameVersion.value || null,
    );
    toast("info", `Installed “${hit.title}” → ${path}`);
  } catch (error) {
    reportError(error);
  } finally {
    busy.value = false;
  }
}

function instanceLabel(i: InstanceSummary): string {
  return `${i.name} (${i.gameVersion})`;
}
</script>

<template>
  <div class="p-6">
    <h2 class="mb-1 text-sm font-semibold">Browse mods</h2>
    <p class="mb-4 text-xs text-slate-500">
      Searches Modrinth (public) and CurseForge (when a Core API key is set in Settings).
      Downloads always go through official endpoints.
    </p>

    <div class="card mb-4 grid gap-3 sm:grid-cols-4">
      <div class="sm:col-span-2">
        <label class="label" for="q">Query</label>
        <input id="q" v-model="query" class="input mt-1" placeholder="sodium, jei…" @keyup.enter="search" />
      </div>
      <div>
        <label class="label" for="gv">Game version</label>
        <input id="gv" v-model="gameVersion" class="input mt-1" />
      </div>
      <div>
        <label class="label" for="inst">Install into</label>
        <select id="inst" v-model="targetId" class="input mt-1">
          <option :value="null">—</option>
          <option v-for="i in store.instances" :key="i.id" :value="i.id">
            {{ instanceLabel(i) }}
          </option>
        </select>
      </div>
      <div class="sm:col-span-4">
        <button class="btn-primary" :disabled="busy || !query.trim()" @click="search">Search</button>
      </div>
    </div>

    <ul class="space-y-2">
      <li v-for="hit in hits" :key="`${hit.platform}-${hit.projectId}`" class="card flex items-start justify-between gap-3">
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-2">
            <span class="text-sm font-medium">{{ hit.title }}</span>
            <span class="badge bg-slate-800 text-slate-300">{{ hit.platform }}</span>
          </div>
          <p class="mt-1 line-clamp-2 text-xs text-slate-500">{{ hit.description }}</p>
          <p class="mt-1 text-[11px] text-slate-600">
            {{ hit.author }} · {{ hit.downloads.toLocaleString() }} downloads · {{ hit.slug }}
          </p>
        </div>
        <button class="btn-primary shrink-0" :disabled="busy" @click="install(hit)">
          Install
        </button>
      </li>
    </ul>
    <p v-if="!hits.length && !busy" class="text-sm text-slate-500">No results yet. Try a search.</p>
  </div>
</template>
