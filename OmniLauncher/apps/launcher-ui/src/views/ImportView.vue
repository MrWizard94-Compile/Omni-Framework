<script setup lang="ts">
/**
 * Import wizard (ARCHITECTURE §7, LNC-7/D7): detect → choose → import → report.
 *
 * The report panel is the point of the whole surface. Migration tools that
 * silently drop things are why people distrust them, so everything the
 * importer could not map is listed with its reason (corpus lesson: bidirectional,
 * honest migration is the anti-lock-in posture in MUST NOT #6).
 */
import { ref } from "vue";
import * as api from "../api";
import type { DetectedFormat, ImportCandidate, ImportReport } from "../api/types";
import { refreshInstances, reportError } from "../store";

const path = ref("");
const detected = ref<DetectedFormat[]>([]);
const candidates = ref<ImportCandidate[]>([]);
const report = ref<ImportReport | null>(null);
const busy = ref(false);
const scanned = ref(false);

const formatLabels: Record<string, string> = {
  curseforge: "CurseForge App",
  "prism-multimc": "Prism / MultiMC",
  mrpack: "Modrinth pack (.mrpack)",
  "official-launcher": "Official Minecraft Launcher",
};

function label(format: string): string {
  return formatLabels[format] ?? format;
}

async function scan(): Promise<void> {
  busy.value = true;
  candidates.value = [];
  report.value = null;
  try {
    detected.value = await api.detectImport(path.value);
    scanned.value = true;
    if (detected.value.length > 0) await choose(detected.value[0].format);
  } catch (error) {
    reportError(error);
  } finally {
    busy.value = false;
  }
}

async function choose(format: string): Promise<void> {
  busy.value = true;
  try {
    candidates.value = await api.enumerateImport(path.value, format);
  } catch (error) {
    candidates.value = [];
    reportError(error);
  } finally {
    busy.value = false;
  }
}

async function importOne(candidate: ImportCandidate): Promise<void> {
  busy.value = true;
  try {
    report.value = await api.runImport(candidate);
    await refreshInstances();
  } catch (error) {
    reportError(error);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="p-6">
    <header class="mb-5">
      <h2 class="text-lg font-semibold">Import</h2>
      <p class="mt-0.5 text-xs text-slate-500">
        Bring instances across from the CurseForge App, Prism/MultiMC, a Modrinth
        <code>.mrpack</code>, or the official launcher. Your original files are read, never
        moved or changed.
      </p>
    </header>

    <div class="card mb-5">
      <label class="label" for="import-path">Folder or pack file</label>
      <div class="mt-1 flex gap-2">
        <input
          id="import-path"
          v-model="path"
          class="input font-mono text-xs"
          placeholder="C:\Users\you\curseforge\minecraft\Instances\My Pack"
          @keyup.enter="scan"
        />
        <button class="btn-primary shrink-0" :disabled="busy || !path" @click="scan">Scan</button>
      </div>
      <p class="mt-2 text-[11px] text-slate-500">
        Point at a single instance folder, at an instances root to list everything inside it,
        or at a <code>.mrpack</code> file.
      </p>
    </div>

    <div v-if="scanned && detected.length === 0" class="card mb-5 text-sm text-slate-400">
      Nothing recognizable there. Check the path points at an instance folder, an instances
      root, or a <code>.mrpack</code> file.
    </div>

    <div v-if="detected.length > 0" class="card mb-5">
      <h3 class="mb-2 text-sm font-semibold">Detected</h3>
      <div class="flex flex-wrap gap-2">
        <button
          v-for="format in detected"
          :key="format.format"
          class="btn-ghost"
          @click="choose(format.format)"
        >
          {{ label(format.format) }}
          <span
            class="badge"
            :class="
              format.confidence === 'certain'
                ? 'bg-emerald-500/20 text-emerald-300'
                : 'bg-amber-500/20 text-amber-300'
            "
            >{{ format.confidence }}</span
          >
        </button>
      </div>
    </div>

    <div v-if="candidates.length > 0" class="card mb-5">
      <h3 class="mb-2 text-sm font-semibold">Found {{ candidates.length }}</h3>
      <ul class="space-y-2">
        <li
          v-for="candidate in candidates"
          :key="`${candidate.format}:${candidate.root}:${candidate.key ?? ''}`"
          class="flex items-center justify-between gap-4 rounded border border-slate-800 p-2"
        >
          <div class="min-w-0">
            <p class="truncate text-sm">{{ candidate.name }}</p>
            <p class="truncate font-mono text-[11px] text-slate-500">{{ candidate.root }}</p>
          </div>
          <button class="btn-primary shrink-0" :disabled="busy" @click="importOne(candidate)">
            Import
          </button>
        </li>
      </ul>
    </div>

    <!-- The honest report -->
    <div v-if="report" class="card border-emerald-800 bg-emerald-950/20">
      <h3 class="text-sm font-semibold text-emerald-200">Imported</h3>
      <p class="mt-1 text-sm text-slate-300">
        {{ report.modsImported }} mods mapped into instance #{{ report.instanceId }}.
      </p>

      <div v-if="report.skipped.length > 0" class="mt-3">
        <h4 class="text-xs font-semibold uppercase tracking-wide text-amber-300">
          Not imported ({{ report.skipped.length }})
        </h4>
        <ul class="mt-1 max-h-56 space-y-1 overflow-y-auto text-xs">
          <li v-for="(item, index) in report.skipped" :key="index" class="text-slate-400">
            <span class="font-mono text-slate-300">{{ item.item }}</span> — {{ item.reason }}
          </li>
        </ul>
      </div>

      <ul v-if="report.notes.length > 0" class="mt-3 space-y-1 text-[11px] text-slate-400">
        <li v-for="(note, index) in report.notes" :key="index">· {{ note }}</li>
      </ul>
    </div>
  </div>
</template>
