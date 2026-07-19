import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// Tauri serves this build from disk (frontendDist), so the base must be
// relative and the dev server must use a fixed port the shell can point at.
export default defineConfig({
  plugins: [vue()],
  base: "./",
  clearScreen: false,
  server: { port: 5173, strictPort: true },
  build: { target: "esnext", emptyOutDir: true },
});
