<script setup lang="ts">
/**
 * Server console / session output (ARCHITECTURE §7, D8).
 *
 * Output is read from the captured log the core records per session, so it
 * survives closing this tab — and, unlike an in-memory buffer, it is still
 * there after a crash. Polling only happens while a session is running and
 * this surface is mounted.
 */
import { onMounted, onUnmounted, ref, watch } from "vue";
import * as api from "../api";
import type { ConsoleTail } from "../api/types";
import { store, reportError } from "../store";

const tail = ref<ConsoleTail | null>(null);
const sessionId = ref<number | null>(store.activeSession);
const output = ref<HTMLElement | null>(null);
let timer: number | undefined;

async function poll(): Promise<void> {
  if (sessionId.value === null) return;
  try {
    tail.value = await api.consoleTail(sessionId.value, 500);
    // Keep the newest line in view unless the user scrolled up to read.
    await new Promise((resolve) => requestAnimationFrame(resolve));
    const element = output.value;
    if (element) {
      const nearBottom =
        element.scrollHeight - element.scrollTop - element.clientHeight < 80;
      if (nearBottom) element.scrollTop = element.scrollHeight;
    }
  } catch (error) {
    reportError(error);
    stop();
  }
}

function start(): void {
  stop();
  void poll();
  timer = window.setInterval(() => {
    if (tail.value?.running === false) {
      stop();
      return;
    }
    void poll();
  }, 1500);
}

function stop(): void {
  if (timer !== undefined) {
    window.clearInterval(timer);
    timer = undefined;
  }
}

watch(
  () => store.activeSession,
  (next) => {
    sessionId.value = next;
    if (next !== null) start();
  },
);

async function stopProcess(): Promise<void> {
  if (sessionId.value === null) return;
  try {
    await api.stopProcess(sessionId.value);
  } catch (error) {
    reportError(error);
  }
}

onMounted(() => {
  if (sessionId.value !== null) start();
});
onUnmounted(stop);
</script>

<template>
  <div class="flex h-full flex-col p-6">
    <header class="mb-4 flex items-start justify-between gap-4">
      <div>
        <h2 class="text-lg font-semibold">Console</h2>
        <p class="mt-0.5 text-xs text-slate-500">
          <template v-if="sessionId === null">
            Nothing running. Launch an instance and its output appears here.
          </template>
          <template v-else>
            Session #{{ sessionId }} ·
            <span :class="tail?.running ? 'text-emerald-400' : 'text-slate-500'">{{
              tail?.running ? "running" : "ended"
            }}</span>
          </template>
        </p>
      </div>
      <button
        v-if="sessionId !== null && tail?.running"
        class="btn-danger shrink-0"
        @click="stopProcess"
      >
        Stop
      </button>
    </header>

    <div
      ref="output"
      class="min-h-0 flex-1 overflow-y-auto rounded-lg border border-slate-800 bg-slate-950 p-3 font-mono text-xs leading-relaxed"
      role="log"
      aria-live="polite"
    >
      <p v-if="!tail || tail.lines.length === 0" class="text-slate-600">No output yet.</p>
      <p v-for="(line, index) in tail?.lines ?? []" :key="index" class="whitespace-pre-wrap">
        {{ line }}
      </p>
    </div>

    <p v-if="tail" class="mt-2 truncate font-mono text-[10px] text-slate-600">
      {{ tail.path }}
    </p>
  </div>
</template>
