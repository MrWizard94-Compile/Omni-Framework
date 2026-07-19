//! Desktop entry point. All wiring lives in the library so Tauri's mobile
//! targets (and any future harness) can call the same `run()`.

// Release builds must not open a console window behind the app.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    launcher_shell::run();
}
