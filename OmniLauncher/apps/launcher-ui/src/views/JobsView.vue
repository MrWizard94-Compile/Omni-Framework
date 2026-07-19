<script setup lang="ts">
/**
 * Job center (ARCHITECTURE §7): live progress from the event stream.
 *
 * The list never polls — `store.refreshJobs()` runs when the core emits a job
 * event. Failed and paused jobs are resumable because installs persist a
 * resume point after every step (LNC-5), so "Retry" continues rather than
 * restarting.
 */
import * as api from "../api";
import type { Job } from "../api/types";
import { store, reportError, toast } from "../store";

const stateStyle: Record<Job["state"], string> = {
  running: "bg-indigo-500/20 text-indigo-300",
  pending: "bg-slate-700 text-slate-300",
  paused: "bg-amber-500/20 text-amber-300",
  failed: "bg-rose-500/20 text-rose-300",
  done: "bg-emerald-500/20 text-emerald-300",
};

function percent(job: Job): number | null {
  const { completed, total } = job.progress;
  if (typeof completed !== "number" || typeof total !== "number" || total <= 0) return null;
  return Math.min(100, Math.round((completed / total) * 100));
}

function instanceName(job: Job): string {
  if (job.instanceId === null) return "—";
  return store.instances.find((i) => i.id === job.instanceId)?.name ?? `#${job.instanceId}`;
}

async function retry(job: Job): Promise<void> {
  try {
    await api.resumeJob(job.id);
    toast("info", "Resuming from where it stopped.");
  } catch (error) {
    reportError(error);
  }
}

async function cancel(job: Job): Promise<void> {
  try {
    const owned = await api.cancelJob(job.id);
    toast(
      "info",
      owned ? "Pausing at the next safe point…" : "That job isn't running in this session.",
    );
  } catch (error) {
    reportError(error);
  }
}
</script>

<template>
  <div class="p-6">
    <header class="mb-5">
      <h2 class="text-lg font-semibold">Jobs</h2>
      <p class="mt-0.5 text-xs text-slate-500">
        Installs and repairs run in the background. Interrupted work resumes from its last
        completed step — nothing is downloaded twice.
      </p>
    </header>

    <p v-if="store.jobs.length === 0" class="text-sm text-slate-500">Nothing has run yet.</p>

    <ul class="space-y-2">
      <li v-for="job in store.jobs" :key="job.id" class="card">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2">
              <span class="text-sm font-medium">{{ job.kind }}</span>
              <span class="badge" :class="stateStyle[job.state]">{{ job.state }}</span>
              <span class="text-xs text-slate-500">{{ instanceName(job) }}</span>
            </div>

            <p v-if="job.progress.phase" class="mt-1 text-[11px] text-slate-400">
              {{ job.progress.phase }}
              <template v-if="typeof job.progress.total === 'number' && job.progress.total > 0">
                — {{ job.progress.completed }} / {{ job.progress.total }}
              </template>
            </p>

            <div
              v-if="percent(job) !== null"
              class="mt-2 h-1.5 w-full overflow-hidden rounded bg-slate-800"
              role="progressbar"
              :aria-valuenow="percent(job) ?? 0"
              aria-valuemin="0"
              aria-valuemax="100"
            >
              <div
                class="h-full bg-indigo-500 transition-all"
                :style="{ width: `${percent(job)}%` }"
              />
            </div>

            <p v-if="job.error?.message" class="mt-2 break-words text-xs text-rose-300">
              {{ job.error.message }}
            </p>
          </div>

          <div class="flex shrink-0 gap-2">
            <button v-if="job.running" class="btn-ghost" @click="cancel(job)">Pause</button>
            <button
              v-else-if="job.state === 'failed' || job.state === 'paused'"
              class="btn-primary"
              @click="retry(job)"
            >
              Resume
            </button>
          </div>
        </div>
      </li>
    </ul>
  </div>
</template>
