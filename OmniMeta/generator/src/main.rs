//! `omni-meta-gen` CLI — thin dispatch over the library pipeline (`lib.rs`).
//!
//! Exit codes: `0` success · `1` operation failed / verification found issues
//! · `2` usage error.

use std::collections::BTreeSet;
use std::path::PathBuf;
use std::process::ExitCode;

use omni_meta_gen::normalize::{self, Options, Policy};
use omni_meta_gen::snapshot::{
    self, DEFAULT_JAVA_RUNTIME_URL, DEFAULT_VERSION_MANIFEST_URL, FetchOptions,
};
use omni_meta_gen::{GenError, emit, verify};

const USAGE: &str = "\
omni-meta-gen — OmniMeta static-tree generator (SVC-1)

USAGE:
  omni-meta-gen snapshot --out DIR [--versions IDS] [--manifest-url URL] [--java-url URL]
      Capture the Mojang upstreams into a pinned snapshot directory.
      IDS: comma-separated version ids; omitted = every version in the manifest.

  omni-meta-gen generate --snapshot DIR --out DIR
                         [--definitions DIR] [--policy FILE] [--versions IDS] [--force]
      Deterministically emit the /v1 tree from a snapshot, then self-verify it.
      --definitions: Omni component payloads, laid out as <uid>/<version>.json.
      --policy: recommended-version policy JSON: {\"recommended\": {\"<uid>\": [..]}}.
      --force: replace an existing <out>/v1 tree.

  omni-meta-gen verify --tree DIR
      Audit an emitted tree (DIR is the directory containing v1/).

  omni-meta-gen help
      Print this text.";

/// Parsed flag set: string flags plus boolean switches.
struct Flags {
    values: std::collections::BTreeMap<&'static str, String>,
    switches: BTreeSet<&'static str>,
}

fn parse_flags(
    args: &[String],
    value_flags: &[&'static str],
    switch_flags: &[&'static str],
) -> Result<Flags, GenError> {
    let mut flags = Flags { values: std::collections::BTreeMap::new(), switches: BTreeSet::new() };
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        if let Some(&name) = switch_flags.iter().find(|&&name| name == arg) {
            flags.switches.insert(name);
        } else if let Some(&name) = value_flags.iter().find(|&&name| name == arg) {
            let value = iter
                .next()
                .ok_or_else(|| GenError::Usage(format!("{name} requires a value")))?;
            if flags.values.insert(name, value.clone()).is_some() {
                return Err(GenError::Usage(format!("{name} given twice")));
            }
        } else {
            return Err(GenError::Usage(format!("unknown argument {arg:?}")));
        }
    }
    Ok(flags)
}

fn required(flags: &Flags, name: &'static str) -> Result<String, GenError> {
    flags
        .values
        .get(name)
        .cloned()
        .ok_or_else(|| GenError::Usage(format!("{name} is required")))
}

fn parse_version_set(flags: &Flags) -> Option<BTreeSet<String>> {
    flags.values.get("--versions").map(|list| {
        list.split(',')
            .map(str::trim)
            .filter(|id| !id.is_empty())
            .map(str::to_owned)
            .collect()
    })
}

fn run_snapshot(args: &[String]) -> Result<(), GenError> {
    let flags = parse_flags(
        args,
        &["--out", "--versions", "--manifest-url", "--java-url"],
        &[],
    )?;
    let options = FetchOptions {
        out: PathBuf::from(required(&flags, "--out")?),
        versions: parse_version_set(&flags),
        manifest_url: flags
            .values
            .get("--manifest-url")
            .cloned()
            .unwrap_or_else(|| DEFAULT_VERSION_MANIFEST_URL.to_owned()),
        java_url: flags
            .values
            .get("--java-url")
            .cloned()
            .unwrap_or_else(|| DEFAULT_JAVA_RUNTIME_URL.to_owned()),
    };
    let summary = snapshot::fetch(&options)?;
    eprintln!(
        "[snapshot] done: {} version document(s), {} bytes -> {}",
        summary.versions_fetched,
        summary.bytes_written,
        options.out.display()
    );
    Ok(())
}

fn run_generate(args: &[String]) -> Result<(), GenError> {
    let flags = parse_flags(
        args,
        &["--snapshot", "--out", "--definitions", "--policy", "--versions"],
        &["--force"],
    )?;
    let snapshot_dir = PathBuf::from(required(&flags, "--snapshot")?);
    let out_root = PathBuf::from(required(&flags, "--out")?);

    let snapshot = snapshot::load(&snapshot_dir)?;
    let definitions = match flags.values.get("--definitions") {
        Some(dir) => normalize::load_definitions(std::path::Path::new(dir))?,
        None => Vec::new(),
    };
    let policy = match flags.values.get("--policy") {
        Some(file) => {
            let bytes = std::fs::read(file)
                .map_err(|e| GenError::io("read policy", PathBuf::from(file), e))?;
            Policy::parse(&bytes, file)?
        }
        None => Policy::default(),
    };

    let tree = normalize::normalize(
        &snapshot,
        &Options { versions: parse_version_set(&flags), policy, definitions },
    )?;
    for warning in &tree.warnings {
        eprintln!("[warn] {warning}");
    }
    let summary = emit::emit(
        &tree,
        &emit::EmitOptions { out_root: out_root.clone(), force: flags.switches.contains("--force") },
    )?;
    eprintln!(
        "[generate] emitted {} component(s), {} version payload(s), {} file(s) -> {}",
        summary.components,
        summary.versions,
        summary.files,
        out_root.join("v1").display()
    );

    // Self-verify: generate never hands over a tree it cannot vouch for.
    let issues = verify::verify(&out_root)?;
    if issues.is_empty() {
        eprintln!("[generate] self-verify passed");
        Ok(())
    } else {
        for issue in &issues {
            eprintln!("[verify] {issue}");
        }
        Err(GenError::Integrity {
            detail: format!("self-verify found {} issue(s)", issues.len()),
        })
    }
}

fn run_verify(args: &[String]) -> Result<bool, GenError> {
    let flags = parse_flags(args, &["--tree"], &[])?;
    let tree_root = PathBuf::from(required(&flags, "--tree")?);
    let issues = verify::verify(&tree_root)?;
    for issue in &issues {
        eprintln!("[verify] {issue}");
    }
    if issues.is_empty() {
        eprintln!("[verify] ok: {}", tree_root.join("v1").display());
    } else {
        eprintln!("[verify] FAILED: {} issue(s)", issues.len());
    }
    Ok(issues.is_empty())
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let Some((command, rest)) = args.split_first() else {
        eprintln!("{USAGE}");
        return ExitCode::from(2);
    };
    let outcome = match command.as_str() {
        "snapshot" => run_snapshot(rest).map(|()| true),
        "generate" => run_generate(rest).map(|()| true),
        "verify" => run_verify(rest),
        "help" | "--help" | "-h" => {
            println!("{USAGE}");
            return ExitCode::SUCCESS;
        }
        other => {
            eprintln!("error: unknown command {other:?}\n\n{USAGE}");
            return ExitCode::from(2);
        }
    };
    match outcome {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::FAILURE,
        Err(err @ GenError::Usage(_)) => {
            eprintln!("error: {err}\n\n{USAGE}");
            ExitCode::from(2)
        }
        Err(err) => {
            eprintln!("error: {err}");
            let mut source = std::error::Error::source(&err);
            while let Some(cause) = source {
                eprintln!("  caused by: {cause}");
                source = cause.source();
            }
            ExitCode::FAILURE
        }
    }
}
