<script setup lang="ts">
/**
 * The shell of the UI: navigation between the six surfaces of
 * ARCHITECTURE §7, plus the toast rail that surfaces every rejected command.
 *
 * Routing is a plain reactive tab rather than a router dependency — six fixed
 * surfaces with no URL semantics do not justify one (SOUL §22).
 */
import { ref } from "vue";
import { store, dismissToast } from "./store";
import InstancesView from "./views/InstancesView.vue";
import AccountsView from "./views/AccountsView.vue";
import JobsView from "./views/JobsView.vue";
import ImportView from "./views/ImportView.vue";
import ConsoleView from "./views/ConsoleView.vue";
import SettingsView from "./views/SettingsView.vue";

type Surface = "instances" | "accounts" | "jobs" | "import" | "console" | "settings";

const surface = ref<Surface>("instances");

const nav: { id: Surface; label: string }[] = [
  { id: "instances", label: "Instances" },
  { id: "accounts", label: "Accounts" },
  { id: "jobs", label: "Jobs" },
  { id: "import", label: "Import" },
  { id: "console", label: "Console" },
  { id: "settings", label: "Settings" },
];
</script>

<template>
  <div class="flex h-full">
    <nav class="flex w-52 shrink-0 flex-col border-r border-slate-800 bg-slate-900">
      <div class="px-4 py-5">
        <h1 class="text-base font-semibold tracking-tight">OmniLauncher</h1>
        <p class="mt-0.5 text-[11px] text-slate-500">Omni-Framework</p>
      </div>

      <ul class="flex-1 space-y-0.5 px-2">
        <li v-for="item in nav" :key="item.id">
          <button
            class="flex w-full items-center justify-between rounded-md px-3 py-2 text-sm transition-colors"
            :class="
              surface === item.id
                ? 'bg-indigo-600/15 font-medium text-indigo-300'
                : 'text-slate-400 hover:bg-slate-800 hover:text-slate-200'
            "
            :aria-current="surface === item.id ? 'page' : undefined"
            @click="surface = item.id"
          >
            <span>{{ item.label }}</span>
            <span
              v-if="item.id === 'jobs' && store.jobs.some((j) => j.running)"
              class="badge bg-indigo-500/20 text-indigo-300"
              >{{ store.jobs.filter((j) => j.running).length }}</span
            >
          </button>
        </li>
      </ul>

      <!-- Mojang's Usage Guidelines require this on all materials
           (COMPLIANCE §1); it is not decoration and must not be removed. -->
      <p class="px-4 pb-4 text-[10px] leading-tight text-slate-600">
        Not an official Minecraft product. Not approved by or associated with Mojang or
        Microsoft.
      </p>
    </nav>

    <main class="flex-1 overflow-y-auto">
      <div v-if="!store.ready" class="p-8 text-sm text-slate-500">Starting…</div>
      <template v-else>
        <InstancesView v-if="surface === 'instances'" />
        <AccountsView v-else-if="surface === 'accounts'" />
        <JobsView v-else-if="surface === 'jobs'" />
        <ImportView v-else-if="surface === 'import'" />
        <ConsoleView v-else-if="surface === 'console'" />
        <SettingsView v-else />
      </template>
    </main>

    <!-- Toasts: every rejected command lands here rather than the console. -->
    <div class="pointer-events-none fixed bottom-4 right-4 flex w-96 flex-col gap-2">
      <div
        v-for="item in store.toasts"
        :key="item.id"
        class="pointer-events-auto flex items-start gap-3 rounded-md border px-3 py-2 text-sm shadow-lg"
        :class="
          item.kind === 'error'
            ? 'border-rose-800 bg-rose-950 text-rose-100'
            : 'border-slate-700 bg-slate-800 text-slate-100'
        "
        role="status"
      >
        <span class="flex-1 break-words">{{ item.message }}</span>
        <button
          class="shrink-0 text-xs text-slate-400 hover:text-slate-200"
          aria-label="Dismiss"
          @click="dismissToast(item.id)"
        >
          ✕
        </button>
      </div>
    </div>
  </div>
</template>
