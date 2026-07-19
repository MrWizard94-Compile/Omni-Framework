//! The install job: a [`crate::jobs::JobExecutor`] whose phases run in small
//! batched steps so **any prefix resumes to completion** — the same property
//! the runner proves, now applied to real installs (modrinth-app lesson).
//!
//! Phase order: game files (libraries + jar) → asset index → asset objects →
//! java manifest → java files → register. Every artifact is hash-verified into
//! the content-addressed stores, so re-running a step is always safe and
//! already-present files cost one local hash, no network (self-diagnosing
//! repair for free: run the same job again and it fixes exactly what's
//! missing or corrupt).

use serde_json::{Value, json};

use crate::db::Db;
use crate::install::blob::{BlobSource, ContentCache};
use crate::install::plan::{
    self, AssetIndexDoc, FileKind, JavaManifestDoc, PlannedFile, java_platform_key,
};
use crate::install::{Paths, ResolvedStack};
use crate::jobs::{JobExecutor, StepError, StepOutcome};
use crate::platform::Platform;
use crate::store::JavaSource;

/// Files ensured per step — small enough that interrupts lose little, large
/// enough that step persistence is not the bottleneck.
pub const BATCH_SIZE: usize = 25;

/// Inputs of one install job.
pub struct InstallSpec {
    /// The instance row id (game dir, registration).
    pub instance_id: i64,
    /// Server instance (D8): server jar + java only; no client libraries or
    /// assets.
    pub server: bool,
    /// The resolved stack + java catalog.
    pub stack: ResolvedStack,
}

/// The executor. Construct per run (holds no cross-run state — everything
/// that matters is in the stores and the persisted resume point).
pub struct InstallExecutor<'a> {
    db: &'a Db,
    paths: &'a Paths,
    source: &'a dyn BlobSource,
    spec: InstallSpec,
    platform: Platform,
    /// Lazily built per-run plans (deterministic from spec + cached docs).
    game_files: Option<Vec<PlannedFile>>,
    asset_files: Option<Vec<PlannedFile>>,
    java_files: Option<Vec<PlannedFile>>,
    java_selection: Option<(String, String, String)>,
}

impl<'a> InstallExecutor<'a> {
    /// Creates the executor for one job run.
    pub fn new(
        db: &'a Db,
        paths: &'a Paths,
        source: &'a dyn BlobSource,
        spec: InstallSpec,
    ) -> Self {
        Self {
            db,
            paths,
            source,
            spec,
            platform: Platform::current(),
            game_files: None,
            asset_files: None,
            java_files: None,
            java_selection: None,
        }
    }

    fn fail(message: String) -> StepError {
        StepError { message, retryable: true }
    }

    fn objects_cache(&self) -> ContentCache {
        ContentCache::new(self.paths.objects_root())
    }

    fn game_plan(&mut self) -> Result<&[PlannedFile], StepError> {
        if self.game_files.is_none() {
            let refs: Vec<&omni_meta_format::ComponentPayload> =
                self.spec.stack.payloads.iter().collect();
            let files = plan::plan_game_files(&refs, &self.platform, self.spec.server)
                .map_err(|err| Self::fail(err.to_string()))?;
            self.game_files = Some(files);
        }
        Ok(self.game_files.as_deref().unwrap_or_default())
    }

    fn asset_index_ref(&self) -> Result<Option<(String, String, String)>, StepError> {
        if self.spec.server {
            return Ok(None);
        }
        let game = self.spec.stack.game().map_err(|err| Self::fail(err.to_string()))?;
        Ok(game
            .asset_index
            .as_ref()
            .map(|index| (index.id.clone(), index.url.clone(), index.sha1.clone())))
    }

    fn asset_plan(&mut self) -> Result<&[PlannedFile], StepError> {
        if self.asset_files.is_none() {
            let files = match self.asset_index_ref()? {
                None => Vec::new(),
                Some((id, _, _)) => {
                    let path = self.paths.asset_indexes_dir().join(format!("{id}.json"));
                    let bytes = std::fs::read(&path).map_err(|err| {
                        Self::fail(format!("asset index not staged at {}: {err}", path.display()))
                    })?;
                    let doc: AssetIndexDoc = serde_json::from_slice(&bytes)
                        .map_err(|err| Self::fail(format!("asset index parse: {err}")))?;
                    plan::plan_assets(&doc)
                }
            };
            self.asset_files = Some(files);
        }
        Ok(self.asset_files.as_deref().unwrap_or_default())
    }

    fn java_selection(&mut self) -> Result<(String, String, String), StepError> {
        if self.java_selection.is_none() {
            let game = self.spec.stack.game().map_err(|err| Self::fail(err.to_string()))?;
            let component = game
                .java_version
                .as_ref()
                .map_or("java-runtime-delta", |jv| jv.component.as_str());
            let refs: Vec<&omni_meta_format::ComponentPayload> =
                self.spec.stack.java_payloads.iter().collect();
            let selection =
                plan::select_java_offer(&refs, component, java_platform_key(&self.platform))
                    .map_err(|err| Self::fail(err.to_string()))?;
            self.java_selection = Some(selection);
        }
        Ok(self.java_selection.clone().expect("just set"))
    }

    fn java_plan(&mut self) -> Result<&[PlannedFile], StepError> {
        if self.java_files.is_none() {
            let (_, _, manifest_sha1) = self.java_selection()?;
            let cache = self.objects_cache();
            let path = cache.object_path(&manifest_sha1);
            let bytes = std::fs::read(&path).map_err(|err| {
                Self::fail(format!("java manifest not staged at {}: {err}", path.display()))
            })?;
            let doc: JavaManifestDoc = serde_json::from_slice(&bytes)
                .map_err(|err| Self::fail(format!("java manifest parse: {err}")))?;
            self.java_files = Some(plan::plan_java_files(&doc));
        }
        Ok(self.java_files.as_deref().unwrap_or_default())
    }

    /// Ensures one batch of `files` starting at `done`; returns the new count.
    fn ensure_batch(
        &self,
        cache: &ContentCache,
        files: &[PlannedFile],
        done: usize,
    ) -> Result<usize, StepError> {
        let end = files.len().min(done + BATCH_SIZE);
        for file in &files[done..end] {
            let object = cache
                .ensure(self.source, &file.url, &file.sha1)
                .map_err(|err| Self::fail(err.to_string()))?;
            if let FileKind::JavaFile { rel_path, executable: _ } = &file.kind {
                let (version, _, _) = self
                    .java_selection
                    .clone()
                    .ok_or_else(|| Self::fail("java selection missing".into()))?;
                let game = self.spec.stack.game().map_err(|e| Self::fail(e.to_string()))?;
                let component = game
                    .java_version
                    .as_ref()
                    .map_or("java-runtime-delta", |jv| jv.component.as_str());
                let dest = self.paths.java_install_dir(component, &version).join(rel_path);
                if let Some(parent) = dest.parent() {
                    std::fs::create_dir_all(parent)
                        .map_err(|err| Self::fail(format!("mkdir {}: {err}", parent.display())))?;
                }
                std::fs::copy(&object, &dest)
                    .map_err(|err| Self::fail(format!("place {}: {err}", dest.display())))?;
            }
        }
        Ok(end)
    }

    fn progress(phase: &str, completed: usize, total: usize) -> Value {
        json!({ "phase": phase, "completed": completed, "total": total })
    }

    fn advance(
        phase: &'static str,
        next_phase: &'static str,
        completed: usize,
        total: usize,
    ) -> StepOutcome {
        if completed >= total {
            StepOutcome::Continue {
                resume: json!({ "phase": next_phase, "done": 0 }),
                progress: Self::progress(phase, completed, total),
            }
        } else {
            StepOutcome::Continue {
                resume: json!({ "phase": phase, "done": completed }),
                progress: Self::progress(phase, completed, total),
            }
        }
    }
}

impl InstallExecutor<'_> {
    /// Instance dirs exist from the first step (cheap, idempotent).
    fn ensure_instance_dirs(&self) -> Result<(), StepError> {
        for dir in [
            self.paths.game_dir(self.spec.instance_id),
            self.paths.natives_dir(self.spec.instance_id),
            self.paths.logs_dir(self.spec.instance_id),
        ] {
            std::fs::create_dir_all(&dir)
                .map_err(|err| Self::fail(format!("mkdir {}: {err}", dir.display())))?;
        }
        Ok(())
    }
}

impl JobExecutor for InstallExecutor<'_> {
    fn step(&mut self, resume: Option<&Value>) -> Result<StepOutcome, StepError> {
        let phase = resume
            .and_then(|value| value["phase"].as_str())
            .unwrap_or("game-files")
            .to_owned();
        let done = resume
            .and_then(|value| value["done"].as_u64())
            .and_then(|done| usize::try_from(done).ok())
            .unwrap_or(0);
        self.ensure_instance_dirs()?;

        match phase.as_str() {
            "game-files" => {
                let cache = self.objects_cache();
                let files = self.game_plan()?.to_vec();
                let completed = self.ensure_batch(&cache, &files, done)?;
                Ok(Self::advance("game-files", "asset-index", completed, files.len()))
            }
            "asset-index" => {
                match self.asset_index_ref()? {
                    None => Ok(StepOutcome::Continue {
                        resume: json!({ "phase": "java-manifest", "done": 0 }),
                        progress: Self::progress("asset-index", 0, 0),
                    }),
                    Some((id, url, sha1)) => {
                        let cache = self.objects_cache();
                        let object = cache
                            .ensure(self.source, &url, &sha1)
                            .map_err(|err| Self::fail(err.to_string()))?;
                        let dest = self.paths.asset_indexes_dir().join(format!("{id}.json"));
                        if let Some(parent) = dest.parent() {
                            std::fs::create_dir_all(parent).map_err(|err| {
                                Self::fail(format!("mkdir {}: {err}", parent.display()))
                            })?;
                        }
                        std::fs::copy(&object, &dest)
                            .map_err(|err| Self::fail(format!("stage asset index: {err}")))?;
                        Ok(StepOutcome::Continue {
                            resume: json!({ "phase": "assets", "done": 0 }),
                            progress: Self::progress("asset-index", 1, 1),
                        })
                    }
                }
            }
            "assets" => {
                // Asset objects live directly in the official layout, which is
                // itself content-addressed by the asset hash.
                let cache = ContentCache::new(self.paths.asset_objects_root());
                let files = self.asset_plan()?.to_vec();
                let completed = self.ensure_batch(&cache, &files, done)?;
                Ok(Self::advance("assets", "java-manifest", completed, files.len()))
            }
            "java-manifest" => {
                let (_, url, sha1) = self.java_selection()?;
                let cache = self.objects_cache();
                cache
                    .ensure(self.source, &url, &sha1)
                    .map_err(|err| Self::fail(err.to_string()))?;
                Ok(StepOutcome::Continue {
                    resume: json!({ "phase": "java", "done": 0 }),
                    progress: Self::progress("java-manifest", 1, 1),
                })
            }
            "java" => {
                let cache = self.objects_cache();
                let files = self.java_plan()?.to_vec();
                let completed = self.ensure_batch(&cache, &files, done)?;
                Ok(Self::advance("java", "register", completed, files.len()))
            }
            "register" => {
                let (version, _, _) = self.java_selection()?;
                let game = self.spec.stack.game().map_err(|e| Self::fail(e.to_string()))?;
                let component = game
                    .java_version
                    .as_ref()
                    .map_or("java-runtime-delta", |jv| jv.component.as_str());
                let exe = self.paths.java_exe(component, &version);
                let arch = self.platform.arch.to_owned();
                self.db
                    .with_store(|store| {
                        store.upsert_java_runtime(
                            &exe.to_string_lossy(),
                            Some("mojang"),
                            &version,
                            &arch,
                            JavaSource::MojangManifest,
                            Some(store.clock_now()),
                        )
                    })
                    .map_err(|err| Self::fail(err.to_string()))?;
                Ok(StepOutcome::Done {
                    progress: Self::progress("register", 1, 1),
                })
            }
            other => Err(StepError {
                message: format!("unknown install phase {other:?} in resume point"),
                retryable: false,
            }),
        }
    }
}
