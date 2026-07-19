//! Tauri's build step: bakes `tauri.conf.json`, capabilities, and icons into
//! the binary for `tauri::generate_context!`.

fn main() {
    tauri_build::build();
}
