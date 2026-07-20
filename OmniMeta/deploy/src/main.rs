//! `omni-meta-deploy` CLI.
//!
//! Exit codes: `0` success · `1` publish failed · `2` usage error.

use std::path::PathBuf;
use std::process::ExitCode;

use omni_meta_deploy::plan::plan;
use omni_meta_deploy::{DeployError, R2Client, Target};

const USAGE: &str = "\
omni-meta-deploy — publish a generated OmniMeta tree to Cloudflare R2 (SVC-1)

USAGE:
  omni-meta-deploy --tree DIR [--account-id ID] [--bucket NAME] [--prefix P]
                   [--public-url URL] [--dry-run] [--no-verify]

  --tree DIR        directory containing the generated `v1/` tree (required)
  --account-id ID   Cloudflare account id (default: $R2_ACCOUNT_ID)
  --bucket NAME     R2 bucket (default: omni-meta)
  --prefix P        key prefix inside the bucket (default: none)
  --public-url URL  base URL to verify through
                    (default: https://omnimeta.wpaistudio.net)
  --dry-run         print the plan and exit; touches no network, needs no
                    credentials
  --no-verify       skip reading objects back after upload

CREDENTIALS
  Read from R2_ACCESS_KEY_ID and R2_SECRET_ACCESS_KEY, and from nowhere else.
  They are never accepted as arguments, so they cannot reach shell history or
  the process list. --dry-run needs none.

ORDER
  Payloads publish before the indexes that name them, and the tree root
  publishes last. A client mid-publish therefore sees either the previous
  complete tree or the new one — never a root pointing at objects that do not
  exist yet.";

struct Args {
    tree: PathBuf,
    account_id: Option<String>,
    bucket: String,
    prefix: String,
    public_url: String,
    dry_run: bool,
    verify: bool,
}

fn parse_args() -> Result<Args, DeployError> {
    let mut tree = None;
    let mut account_id = std::env::var("R2_ACCOUNT_ID").ok().filter(|v| !v.is_empty());
    let mut bucket = "omni-meta".to_owned();
    let mut prefix = String::new();
    let mut public_url = "https://omnimeta.wpaistudio.net".to_owned();
    let mut dry_run = false;
    let mut verify = true;

    let argv: Vec<String> = std::env::args().skip(1).collect();
    let mut iter = argv.iter();
    while let Some(arg) = iter.next() {
        let mut value = |name: &str| -> Result<String, DeployError> {
            iter.next()
                .cloned()
                .ok_or_else(|| DeployError::Usage(format!("{name} requires a value")))
        };
        match arg.as_str() {
            "--tree" => tree = Some(PathBuf::from(value("--tree")?)),
            "--account-id" => account_id = Some(value("--account-id")?),
            "--bucket" => bucket = value("--bucket")?,
            "--prefix" => prefix = value("--prefix")?,
            "--public-url" => public_url = value("--public-url")?,
            "--dry-run" => dry_run = true,
            "--no-verify" => verify = false,
            "--help" | "-h" | "help" => {
                println!("{USAGE}");
                std::process::exit(0);
            }
            other => return Err(DeployError::Usage(format!("unknown argument {other:?}"))),
        }
    }

    Ok(Args {
        tree: tree.ok_or_else(|| DeployError::Usage("--tree is required".to_owned()))?,
        account_id,
        bucket,
        prefix,
        public_url,
        dry_run,
        verify,
    })
}

fn run() -> Result<(), DeployError> {
    let args = parse_args()?;
    let deploy_plan = plan(&args.tree, &args.prefix)?;

    eprintln!(
        "[plan] {} object(s), {} byte(s); {} mutable root(s) publish last",
        deploy_plan.uploads.len(),
        deploy_plan.total_bytes(),
        deploy_plan.root_count()
    );

    if args.dry_run {
        for upload in &deploy_plan.uploads {
            println!(
                "{:<12} {:>9}  {}  {}",
                if upload.is_root { "root" } else { "immutable" },
                upload.size,
                &upload.sha256[..12],
                upload.key
            );
        }
        eprintln!("[plan] dry run — nothing was uploaded");
        return Ok(());
    }

    let account_id = args.account_id.ok_or_else(|| {
        DeployError::Usage(
            "no account id: pass --account-id or set R2_ACCOUNT_ID".to_owned(),
        )
    })?;
    let client = R2Client::new(Target { account_id, bucket: args.bucket })?;

    let total = deploy_plan.uploads.len();
    for (index, upload) in deploy_plan.uploads.iter().enumerate() {
        client.put(upload)?;
        if (index + 1) % 25 == 0 || index + 1 == total {
            eprintln!("[upload] {}/{}", index + 1, total);
        }
    }

    if args.verify {
        // Verify through the public URL: this proves what a launcher actually
        // receives, not merely that a write succeeded.
        for upload in &deploy_plan.uploads {
            client.verify_public(&args.public_url, upload)?;
        }
        eprintln!("[verify] all {total} object(s) read back correctly from {}", args.public_url);
    }

    eprintln!("[done] published {total} object(s)");
    Ok(())
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(err @ DeployError::Usage(_)) => {
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
