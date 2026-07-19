/** UI entry point: mount the app and start the event subscription. */

import { createApp } from "vue";
import App from "./App.vue";
import "./style.css";
import { initStore, reportError } from "./store";

createApp(App).mount("#app");

// The store owns the event subscription; a failure here means the UI would
// silently stop updating, so it must be visible rather than swallowed.
initStore().catch(reportError);
