/// <reference types="vite/client" />

// Vue SFCs have no intrinsic TypeScript type; this teaches `vue-tsc` that a
// `.vue` import is a component.
declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>;
  export default component;
}
