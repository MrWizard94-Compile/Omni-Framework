//! Job rows: persistence and the guarded state machine (LNC-5).
//!
//! Every transition goes through [`Store::transition_job`], which enforces
//! [`super::JobState::can_transition_to`] — there is no code path that can put
//! a job into an illegal state, which is what makes `resume_json` trustworthy.

use rusqlite::{OptionalExtension as _, Row, params};

use crate::error::CoreError;

use super::{Job, JobState, Store};

fn map_job(row: &Row<'_>) -> rusqlite::Result<Job> {
    Ok(Job {
        id: row.get("id")?,
        kind: row.get("kind")?,
        instance_id: row.get("instance_id")?,
        state: row.get("state")?,
        progress: row.get("progress_json")?,
        resume: row.get("resume_json")?,
        error: row.get("error_json")?,
        created_at: row.get("created_at")?,
        updated_at: row.get("updated_at")?,
    })
}

const SELECT_JOB: &str = "SELECT id, kind, instance_id, state, progress_json, \
     resume_json, error_json, created_at, updated_at FROM jobs";

impl Store<'_> {
    /// Creates a pending job.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn create_job(&mut self, kind: &str, instance_id: Option<i64>) -> Result<Job, CoreError> {
        let now = self.clock.now_unix();
        self.conn
            .execute(
                "INSERT INTO jobs (kind, instance_id, created_at, updated_at) \
                 VALUES (?1, ?2, ?3, ?3)",
                params![kind, instance_id, now],
            )
            .map_err(CoreError::db("insert job"))?;
        let id = self.conn.last_insert_rowid();
        self.job(id)?.ok_or(CoreError::NotFound { what: "job", id })
    }

    /// Fetches one job.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn job(&mut self, id: i64) -> Result<Option<Job>, CoreError> {
        self.conn
            .query_row(&format!("{SELECT_JOB} WHERE id = ?1"), params![id], map_job)
            .optional()
            .map_err(CoreError::db("select job"))
    }

    /// Jobs in a given state, oldest first.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn jobs_in_state(&mut self, state: JobState) -> Result<Vec<Job>, CoreError> {
        let mut statement = self
            .conn
            .prepare(&format!("{SELECT_JOB} WHERE state = ?1 ORDER BY id"))
            .map_err(CoreError::db("prepare job list"))?;
        let rows = statement
            .query_map(params![state], map_job)
            .map_err(CoreError::db("list jobs"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(CoreError::db("read job rows"))?;
        Ok(rows)
    }

    /// Moves a job through the state machine, returning the updated row.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotFound`] for a missing job;
    /// [`CoreError::IllegalTransition`] when the state machine forbids the
    /// move; [`CoreError::Db`] on driver failure.
    pub fn transition_job(&mut self, id: i64, to: JobState) -> Result<Job, CoreError> {
        let tx = self.conn.transaction().map_err(CoreError::db("begin transition"))?;
        let current: JobState = tx
            .query_row("SELECT state FROM jobs WHERE id = ?1", params![id], |row| row.get(0))
            .optional()
            .map_err(CoreError::db("read job state"))?
            .ok_or(CoreError::NotFound { what: "job", id })?;
        if !current.can_transition_to(to) {
            return Err(CoreError::IllegalTransition { job_id: id, from: current, to });
        }
        // Starting a new attempt invalidates the previous failure. Without this,
        // a job that failed, was resumed, and then succeeded still carried its
        // old `error_json` — so the job center rendered a completed install
        // alongside the reason an earlier attempt had failed, which reads as
        // "it worked, but also it didn't" (found live 2026-07-20).
        let clearing_error = to == JobState::Running;
        tx.execute(
            "UPDATE jobs SET state = ?2, updated_at = ?3, \
             error_json = CASE WHEN ?4 THEN NULL ELSE error_json END WHERE id = ?1",
            params![id, to, self.clock.now_unix(), clearing_error],
        )
        .map_err(CoreError::db("update job state"))?;
        let job = tx
            .query_row(&format!("{SELECT_JOB} WHERE id = ?1"), params![id], map_job)
            .map_err(CoreError::db("reselect job"))?;
        tx.commit().map_err(CoreError::db("commit transition"))?;
        Ok(job)
    }

    /// Persists one completed step of a running job: the next resume point and
    /// the progress document, atomically.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotFound`] for a missing job; [`CoreError::IllegalTransition`]
    /// (reported as running-required) when the job is not running;
    /// [`CoreError::Db`] on driver failure.
    pub fn persist_job_step(
        &mut self,
        id: i64,
        resume: &serde_json::Value,
        progress: &serde_json::Value,
    ) -> Result<(), CoreError> {
        let now = self.clock.now_unix();
        let changed = self
            .conn
            .execute(
                "UPDATE jobs SET resume_json = ?2, progress_json = ?3, updated_at = ?4 \
                 WHERE id = ?1 AND state = 'running'",
                params![id, resume, progress, now],
            )
            .map_err(CoreError::db("persist job step"))?;
        if changed == 0 {
            let state = self
                .job(id)?
                .ok_or(CoreError::NotFound { what: "job", id })?
                .state;
            return Err(CoreError::IllegalTransition { job_id: id, from: state, to: JobState::Running });
        }
        Ok(())
    }

    /// Completes a running job: state `done`, final progress, resume cleared.
    ///
    /// # Errors
    ///
    /// As [`Store::transition_job`].
    pub fn complete_job(
        &mut self,
        id: i64,
        progress: &serde_json::Value,
    ) -> Result<Job, CoreError> {
        let job = self.transition_job(id, JobState::Done)?;
        self.conn
            .execute(
                "UPDATE jobs SET resume_json = NULL, progress_json = ?2 WHERE id = ?1",
                params![id, progress],
            )
            .map_err(CoreError::db("finalize job"))?;
        Ok(Job { resume: None, progress: progress.clone(), ..job })
    }

    /// Fails a running job with a persisted error document; the resume point
    /// is kept so a retry continues from the failed step.
    ///
    /// # Errors
    ///
    /// As [`Store::transition_job`].
    pub fn fail_job(&mut self, id: i64, error: &serde_json::Value) -> Result<Job, CoreError> {
        let job = self.transition_job(id, JobState::Failed)?;
        self.conn
            .execute("UPDATE jobs SET error_json = ?2 WHERE id = ?1", params![id, error])
            .map_err(CoreError::db("record job error"))?;
        Ok(Job { error: Some(error.clone()), ..job })
    }

    /// Crash recovery (startup duty): every `running` job — impossible unless
    /// the previous process died — becomes `paused` at its persisted resume
    /// point. Returns the affected ids.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn recover_interrupted_jobs(&mut self) -> Result<Vec<i64>, CoreError> {
        let now = self.clock.now_unix();
        let mut statement = self
            .conn
            .prepare(
                "UPDATE jobs SET state = 'paused', updated_at = ?1 \
                 WHERE state = 'running' RETURNING id",
            )
            .map_err(CoreError::db("prepare crash recovery"))?;
        let ids = statement
            .query_map(params![now], |row| row.get(0))
            .map_err(CoreError::db("recover jobs"))?
            .collect::<Result<Vec<i64>, _>>()
            .map_err(CoreError::db("read recovered ids"))?;
        Ok(ids)
    }
}
