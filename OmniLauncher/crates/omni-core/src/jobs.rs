//! The resumable job runner (LNC-5): store-backed execution with a persisted
//! resume point after every step.
//!
//! Contract (the modrinth-app install-recovery lesson, made a property):
//! an executor's work is a sequence of **small, idempotent-at-boundary steps**.
//! After each step, the runner persists `(resume, progress)` atomically while
//! the job is `running`. Whatever kills the process — crash, power loss,
//! user exit — the job resumes from its last persisted step and completes as
//! if never interrupted (`tests/jobs.rs` proves this for every interrupt
//! point).
//!
//! Threading: [`run`] is synchronous and callable from any thread (the Tauri
//! shell will dedicate worker threads in brick 7). Cooperative pause goes
//! through [`CancelToken`]; hard crashes are handled at next startup by
//! [`crate::store::Store::recover_interrupted_jobs`].

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use serde_json::Value;

use crate::db::Db;
use crate::error::CoreError;
use crate::events::{CoreEvent, EventSink};
use crate::store::JobState;

/// What one executor step produced.
#[derive(Debug, Clone)]
pub enum StepOutcome {
    /// Work remains: persist this resume point and progress, then continue.
    Continue {
        /// The exact state needed to resume after this step.
        resume: Value,
        /// Progress document for the UI.
        progress: Value,
    },
    /// The job is complete.
    Done {
        /// Final progress document.
        progress: Value,
    },
}

/// A step failure. `retryable` distinguishes transient conditions (network)
/// from permanent ones (corrupt input) for the UI's retry affordance.
#[derive(Debug, Clone)]
pub struct StepError {
    /// Human-actionable message.
    pub message: String,
    /// Whether retrying the job may succeed.
    pub retryable: bool,
}

impl StepError {
    fn to_json(&self) -> Value {
        serde_json::json!({ "message": self.message, "retryable": self.retryable })
    }
}

/// The work of one job kind. Implementations live with their feature bricks
/// (install pipeline, importers); this crate owns only the execution contract.
pub trait JobExecutor {
    /// Performs the **next** step given the last persisted resume point
    /// (`None` on first run). Must be deterministic with respect to the
    /// resume point: re-running a step whose persistence never happened must
    /// be safe (steps end at externally-idempotent boundaries).
    ///
    /// # Errors
    ///
    /// [`StepError`] on failure; the runner persists it and fails the job.
    fn step(&mut self, resume: Option<&Value>) -> Result<StepOutcome, StepError>;
}

/// Cooperative pause signal shared between the runner and its controller.
#[derive(Debug, Clone, Default)]
pub struct CancelToken(Arc<AtomicBool>);

impl CancelToken {
    /// Creates an unsignaled token.
    pub fn new() -> Self {
        Self::default()
    }

    /// Requests a pause at the next step boundary.
    pub fn cancel(&self) {
        self.0.store(true, Ordering::SeqCst);
    }

    /// Whether a pause has been requested.
    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }
}

/// How a [`run`] invocation ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RunOutcome {
    /// The job reached `done`.
    Completed,
    /// The token was signaled; the job is `paused` at its resume point.
    Paused,
    /// A step failed; the job is `failed` with a persisted error, resumable.
    Failed,
}

fn emit_state(sink: &dyn EventSink, job_id: i64, kind: &str, state: JobState) {
    sink.emit(CoreEvent::JobState { job_id, kind: kind.to_owned(), state });
}

/// Runs a job to completion, pause, or failure.
///
/// Accepts jobs in `pending` (first run), `paused` (resume), or `failed`
/// (retry) state; transitions to `running`, then drives the executor,
/// persisting after every step.
///
/// # Errors
///
/// [`CoreError`] only for infrastructure failures (store/database). Step
/// failures are **not** errors here — they persist into the job row and
/// return [`RunOutcome::Failed`].
pub fn run(
    db: &Db,
    job_id: i64,
    executor: &mut dyn JobExecutor,
    sink: &dyn EventSink,
    cancel: &CancelToken,
) -> Result<RunOutcome, CoreError> {
    let job = db.with_store(|store| store.transition_job(job_id, JobState::Running))?;
    emit_state(sink, job_id, &job.kind, JobState::Running);
    let mut resume = job.resume;

    loop {
        if cancel.is_cancelled() {
            db.with_store(|store| store.transition_job(job_id, JobState::Paused))?;
            emit_state(sink, job_id, &job.kind, JobState::Paused);
            return Ok(RunOutcome::Paused);
        }
        match executor.step(resume.as_ref()) {
            Ok(StepOutcome::Continue { resume: next, progress }) => {
                db.with_store(|store| store.persist_job_step(job_id, &next, &progress))?;
                sink.emit(CoreEvent::JobProgress { job_id, progress });
                resume = Some(next);
            }
            Ok(StepOutcome::Done { progress }) => {
                db.with_store(|store| store.complete_job(job_id, &progress))?;
                sink.emit(CoreEvent::JobProgress { job_id, progress });
                emit_state(sink, job_id, &job.kind, JobState::Done);
                return Ok(RunOutcome::Completed);
            }
            Err(step_error) => {
                db.with_store(|store| store.fail_job(job_id, &step_error.to_json()))?;
                emit_state(sink, job_id, &job.kind, JobState::Failed);
                return Ok(RunOutcome::Failed);
            }
        }
    }
}
