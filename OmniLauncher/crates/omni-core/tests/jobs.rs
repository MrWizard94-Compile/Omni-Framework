//! Job runner tests, headlined by the ARCHITECTURE §8 property: **any prefix
//! of a job can resume to completion** — proven for every interrupt point.
#![expect(
    clippy::redundant_closure_for_method_calls,
    reason = "with_store takes a closure over &mut Store<'_>; clippy's suggested bare \
              method paths fail HRTB inference (`implementation of FnOnce is not \
              general enough`), so these closures are load-bearing"
)]

use std::sync::{Arc, Mutex};

use serde_json::{Value, json};

use omni_core::clock::FixedClock;
use omni_core::events::CollectingSink;
use omni_core::jobs::{CancelToken, JobExecutor, RunOutcome, StepError, StepOutcome, run};
use omni_core::store::JobState;
use omni_core::{CoreError, CoreEvent, Db};

const TOTAL_STEPS: u64 = 5;

/// Scripted executor: `TOTAL_STEPS` numbered steps, an append-only shared log
/// of completed steps, optional one-time failure, optional self-cancellation
/// after N completed steps in this run (simulating an interrupt exactly at a
/// persistence boundary).
struct Scripted {
    log: Arc<Mutex<Vec<u64>>>,
    fail_once_at: Option<u64>,
    has_failed: bool,
    cancel_after: Option<(u64, CancelToken)>,
    completed_this_run: u64,
}

impl Scripted {
    fn new(log: Arc<Mutex<Vec<u64>>>) -> Self {
        Self { log, fail_once_at: None, has_failed: false, cancel_after: None, completed_this_run: 0 }
    }
}

impl JobExecutor for Scripted {
    fn step(&mut self, resume: Option<&Value>) -> Result<StepOutcome, StepError> {
        let next = resume.map_or(0, |value| {
            value["next"].as_u64().expect("resume document carries `next`")
        });
        if let Some(fail_at) = self.fail_once_at
            && !self.has_failed
            && next == fail_at
        {
            self.has_failed = true;
            return Err(StepError { message: format!("transient failure at step {next}"), retryable: true });
        }
        self.log.lock().expect("log lock").push(next);
        let completed = next + 1;
        self.completed_this_run += 1;
        if let Some((after, token)) = &self.cancel_after
            && self.completed_this_run == *after
        {
            token.cancel();
        }
        if completed == TOTAL_STEPS {
            Ok(StepOutcome::Done { progress: json!({ "steps": completed }) })
        } else {
            Ok(StepOutcome::Continue {
                resume: json!({ "next": completed }),
                progress: json!({ "steps": completed }),
            })
        }
    }
}

fn fresh_db() -> Db {
    Db::open_in_memory(Arc::new(FixedClock::new(1_800_000_000))).expect("open db")
}

fn expected_full_log() -> Vec<u64> {
    (0..TOTAL_STEPS).collect()
}

#[test]
fn uninterrupted_run_completes_with_events() {
    let db = fresh_db();
    let job = db.with_store(|store| store.create_job("install", None)).expect("create");
    let log = Arc::new(Mutex::new(Vec::new()));
    let sink = CollectingSink::new();

    let outcome = run(&db, job.id, &mut Scripted::new(log.clone()), &sink, &CancelToken::new())
        .expect("run");
    assert_eq!(outcome, RunOutcome::Completed);
    assert_eq!(*log.lock().expect("log"), expected_full_log());

    let row = db.with_store(|store| store.job(job.id)).expect("query").expect("exists");
    assert_eq!(row.state, JobState::Done);
    assert!(row.resume.is_none(), "done jobs carry no resume point");
    assert_eq!(row.progress, json!({ "steps": TOTAL_STEPS }));

    let events = sink.drain();
    assert!(matches!(
        events.first(),
        Some(CoreEvent::JobState { state: JobState::Running, .. })
    ));
    assert!(matches!(
        events.last(),
        Some(CoreEvent::JobState { state: JobState::Done, .. })
    ));
    let progress_count =
        events.iter().filter(|e| matches!(e, CoreEvent::JobProgress { .. })).count();
    assert_eq!(progress_count as u64, TOTAL_STEPS);
}

/// THE property (OmniLauncher/ARCHITECTURE.md §8): interrupt after every
/// possible number of completed steps (0..TOTAL_STEPS-1), resume, and require
/// the exact uninterrupted step sequence — nothing skipped, nothing repeated.
#[test]
fn any_prefix_resumes_to_completion() {
    for interrupt_after in 0..TOTAL_STEPS {
        let db = fresh_db();
        let job = db.with_store(|store| store.create_job("install", None)).expect("create");
        let log = Arc::new(Mutex::new(Vec::new()));

        // Phase 1: run with a token that fires after `interrupt_after` steps
        // (0 = cancelled before the first step even runs).
        let token = CancelToken::new();
        let mut executor = Scripted::new(log.clone());
        if interrupt_after == 0 {
            token.cancel();
        } else {
            executor.cancel_after = Some((interrupt_after, token.clone()));
        }
        let outcome = run(&db, job.id, &mut executor, &omni_core::NullSink, &token)
            .expect("interrupted run");
        assert_eq!(outcome, RunOutcome::Paused, "interrupt_after={interrupt_after}");
        assert_eq!(
            log.lock().expect("log").len() as u64,
            interrupt_after,
            "exactly the prefix ran"
        );
        let paused = db.with_store(|store| store.job(job.id)).expect("query").expect("exists");
        assert_eq!(paused.state, JobState::Paused);

        // Phase 2: a brand-new executor (fresh process semantics) resumes.
        let outcome = run(
            &db,
            job.id,
            &mut Scripted::new(log.clone()),
            &omni_core::NullSink,
            &CancelToken::new(),
        )
        .expect("resumed run");
        assert_eq!(outcome, RunOutcome::Completed, "interrupt_after={interrupt_after}");
        assert_eq!(
            *log.lock().expect("log"),
            expected_full_log(),
            "interrupt_after={interrupt_after}: no step skipped or repeated"
        );
    }
}

#[test]
fn failure_persists_error_and_retry_resumes_at_failed_step() {
    let db = fresh_db();
    let job = db.with_store(|store| store.create_job("install", None)).expect("create");
    let log = Arc::new(Mutex::new(Vec::new()));

    let mut failing = Scripted::new(log.clone());
    failing.fail_once_at = Some(2);
    let outcome =
        run(&db, job.id, &mut failing, &omni_core::NullSink, &CancelToken::new()).expect("run");
    assert_eq!(outcome, RunOutcome::Failed);
    let row = db.with_store(|store| store.job(job.id)).expect("query").expect("exists");
    assert_eq!(row.state, JobState::Failed);
    let error = row.error.expect("error persisted");
    assert_eq!(error["retryable"], true);
    assert_eq!(error["message"], "transient failure at step 2");
    assert_eq!(row.resume, Some(json!({ "next": 2 })), "resume points at the failed step");

    // Retry with a healthy executor: failed -> running is legal; the log ends
    // complete with steps 0 and 1 never re-executed.
    let outcome = run(
        &db,
        job.id,
        &mut Scripted::new(log.clone()),
        &omni_core::NullSink,
        &CancelToken::new(),
    )
    .expect("retry");
    assert_eq!(outcome, RunOutcome::Completed);
    assert_eq!(*log.lock().expect("log"), expected_full_log());
}

/// Regression: a job that failed, was resumed, and then succeeded kept showing
/// the earlier failure. The job center rendered a completed install next to the
/// reason a previous attempt had failed — "it worked, but also it didn't".
/// Starting a new attempt must clear the stale error.
#[test]
fn retrying_clears_the_previous_failure() {
    let db = fresh_db();
    let job = db.with_store(|store| store.create_job("install", None)).expect("create");
    let log = Arc::new(Mutex::new(Vec::new()));

    // Fail once.
    let mut failing = Scripted::new(log.clone());
    failing.fail_once_at = Some(2);
    run(&db, job.id, &mut failing, &omni_core::NullSink, &CancelToken::new()).expect("run");
    let failed = db.with_store(|store| store.job(job.id)).expect("query").expect("job");
    assert_eq!(failed.state, JobState::Failed);
    assert!(failed.error.is_some(), "the failure is recorded while it is current");

    // Retry to completion.
    run(
        &db,
        job.id,
        &mut Scripted::new(log.clone()),
        &omni_core::NullSink,
        &CancelToken::new(),
    )
    .expect("retry");

    let done = db.with_store(|store| store.job(job.id)).expect("query").expect("job");
    assert_eq!(done.state, JobState::Done);
    assert!(
        done.error.is_none(),
        "a succeeded job must not still carry why an earlier attempt failed: {:?}",
        done.error
    );
}

#[test]
fn crash_recovery_pauses_running_jobs_at_their_resume_point() {
    let db = fresh_db();
    let job = db.with_store(|store| store.create_job("install", None)).expect("create");
    // Simulate a crash: the job is 'running' with a persisted step, and the
    // process that ran it is gone.
    db.with_store(|store| {
        store.transition_job(job.id, JobState::Running)?;
        store.persist_job_step(job.id, &json!({ "next": 3 }), &json!({ "steps": 3 }))
    })
    .expect("simulate crash state");

    let recovered =
        db.with_store(|store| store.recover_interrupted_jobs()).expect("recover");
    assert_eq!(recovered, vec![job.id]);
    let row = db.with_store(|store| store.job(job.id)).expect("query").expect("exists");
    assert_eq!(row.state, JobState::Paused);
    assert_eq!(row.resume, Some(json!({ "next": 3 })), "resume point survives the crash");

    // And the recovered job runs to completion from exactly there.
    let log = Arc::new(Mutex::new(Vec::new()));
    let outcome = run(
        &db,
        job.id,
        &mut Scripted::new(log.clone()),
        &omni_core::NullSink,
        &CancelToken::new(),
    )
    .expect("resume after crash");
    assert_eq!(outcome, RunOutcome::Completed);
    assert_eq!(*log.lock().expect("log"), vec![3, 4]);
}

#[test]
fn illegal_transitions_are_typed_errors() {
    let db = fresh_db();
    let job = db.with_store(|store| store.create_job("install", None)).expect("create");

    // pending -> done skips the machine.
    let err = db.with_store(|store| store.transition_job(job.id, JobState::Done));
    assert!(
        matches!(
            err,
            Err(CoreError::IllegalTransition { from: JobState::Pending, to: JobState::Done, .. })
        ),
        "got: {err:?}"
    );

    // Steps cannot be persisted for a job that is not running.
    let err =
        db.with_store(|store| store.persist_job_step(job.id, &json!({}), &json!({})));
    assert!(matches!(err, Err(CoreError::IllegalTransition { .. })), "got: {err:?}");

    // done is terminal.
    db.with_store(|store| {
        store.transition_job(job.id, JobState::Running)?;
        store.complete_job(job.id, &json!({ "steps": 0 }))
    })
    .expect("complete");
    let err = db.with_store(|store| store.transition_job(job.id, JobState::Running));
    assert!(matches!(err, Err(CoreError::IllegalTransition { .. })), "got: {err:?}");

    // Unknown job is NotFound.
    let err = db.with_store(|store| store.transition_job(9999, JobState::Running));
    assert!(matches!(err, Err(CoreError::NotFound { what: "job", .. })), "got: {err:?}");
}
