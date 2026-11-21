use crate::paths::quote;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct GitChange {
    pub path: String,
    pub orig_path: Option<String>,
    pub index: String,
    pub worktree: String,
    pub staged: bool,
    pub unstaged: bool,
    pub untracked: bool,
    pub conflicted: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct GitStatus {
    pub branch: String,
    pub upstream: Option<String>,
    pub ahead: u32,
    pub behind: u32,
    pub detached: bool,
    pub changes: Vec<GitChange>,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum GitOp {
    TopLevel,
    Status,
    Stage { paths: Vec<String> },
    Unstage { paths: Vec<String> },
    Discard { paths: Vec<String> },
    Commit { message: String },
    Pull,
    Push,
    Fetch,
    Log { count: u32 },
    Diff { path: String, staged: bool },
    DiffUntracked { path: String },
    Init,
}

#[uniffi::export]
/// Builds a git shell command. Uses pre-2.0 syntax so it works on old HPC login nodes.
pub fn git_command(repo: String, op: GitOp) -> String {
    let git = format!("GIT_TERMINAL_PROMPT=0 LC_ALL=C git -C {}", quote(&repo));
    let paths = |ps: &[String]| ps.iter().map(|p| quote(p)).collect::<Vec<_>>().join(" ");
    match op {
        GitOp::TopLevel => format!("{git} rev-parse --show-toplevel"),
        GitOp::Status => format!("{git} status --porcelain -b -z"),
        GitOp::Stage { paths: ps } => format!("{git} add -A -- {}", paths(&ps)),
        GitOp::Unstage { paths: ps } => format!("{git} reset -q HEAD -- {}", paths(&ps)),
        GitOp::Discard { paths: ps } => format!("{git} checkout -- {}", paths(&ps)),
        GitOp::Commit { message } => format!("{git} commit -m {}", quote(&message)),
        GitOp::Pull => format!("{git} pull --ff-only"),
        GitOp::Push => format!("{git} push"),
        GitOp::Fetch => format!("{git} fetch --all --prune"),
        GitOp::Log { count } => format!("{git} log --oneline --decorate -n {}", count.max(1)),
        GitOp::Diff { path, staged } => {
            format!(
                "{git} diff{} -- {}",
                if staged { " --cached" } else { "" },
                quote(&path)
            )
        }
        GitOp::DiffUntracked { path } => format!(
            "{git} diff --no-index -- /dev/null {} || true",
            quote(&path)
        ),
        GitOp::Init => format!("{git} init"),
    }
}

#[uniffi::export]
pub fn parse_git_status(output: String) -> GitStatus {
    let mut st = GitStatus {
        branch: String::new(),
        upstream: None,
        ahead: 0,
        behind: 0,
        detached: false,
        changes: Vec::new(),
    };
    let mut fields = output.split('\0').filter(|s| !s.is_empty()).peekable();
    while let Some(f) = fields.next() {
        if let Some(header) = f.strip_prefix("## ") {
            parse_branch(header, &mut st);
            continue;
        }
        if f.len() < 4 {
            continue;
        }
        let (x, y) = (&f[0..1], &f[1..2]);
        let path = f[3..].to_string();
        let orig_path = if x == "R" || x == "C" {
            fields.next().map(str::to_string)
        } else {
            None
        };
        let untracked = x == "?";
        let conflicted = matches!((x, y), ("U", _) | (_, "U") | ("A", "A") | ("D", "D"));
        st.changes.push(GitChange {
            path,
            orig_path,
            index: x.to_string(),
            worktree: y.to_string(),
            staged: !untracked && !conflicted && x != " " && x != "!",
            unstaged: !untracked && !conflicted && y != " " && y != "!",
            untracked,
            conflicted,
        });
    }
    st
}

fn parse_branch(header: &str, st: &mut GitStatus) {
    let (names, tracking) = match header.find(" [") {
        Some(i) => (&header[..i], Some(header[i + 2..].trim_end_matches(']'))),
        None => (header, None),
    };
    if let Some(b) = names
        .strip_prefix("No commits yet on ")
        .or_else(|| names.strip_prefix("Initial commit on "))
    {
        st.branch = b.to_string();
    } else if names.starts_with("HEAD (no branch)") {
        st.branch = "HEAD".into();
        st.detached = true;
    } else if let Some((b, up)) = names.split_once("...") {
        st.branch = b.to_string();
        st.upstream = Some(up.to_string());
    } else {
        st.branch = names.to_string();
    }
    if let Some(t) = tracking {
        for part in t.split(", ") {
            if let Some(n) = part.strip_prefix("ahead ") {
                st.ahead = n.parse().unwrap_or(0);
            } else if let Some(n) = part.strip_prefix("behind ") {
                st.behind = n.parse().unwrap_or(0);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_branch_tracking_and_changes() {
        let out = "## main...origin/main [ahead 2, behind 1]\0M  src/model.py\0 M train.py\0MM notes.md\0?? data/new.csv\0R  run_v2.sh\0run.sh\0UU conflict.txt\0";
        let st = parse_git_status(out.into());
        assert_eq!(st.branch, "main");
        assert_eq!(st.upstream.as_deref(), Some("origin/main"));
        assert_eq!((st.ahead, st.behind), (2, 1));
        assert!(!st.detached);
        assert_eq!(st.changes.len(), 6);

        let m = &st.changes[0];
        assert_eq!(m.path, "src/model.py");
        assert!(m.staged && !m.unstaged);

        let w = &st.changes[1];
        assert!(!w.staged && w.unstaged);

        let both = &st.changes[2];
        assert!(both.staged && both.unstaged);

        let u = &st.changes[3];
        assert!(u.untracked && !u.staged && !u.unstaged);

        let r = &st.changes[4];
        assert_eq!(r.path, "run_v2.sh");
        assert_eq!(r.orig_path.as_deref(), Some("run.sh"));
        assert!(r.staged);

        assert!(st.changes[5].conflicted);
    }

    #[test]
    fn parses_special_branch_headers() {
        assert_eq!(
            parse_git_status("## No commits yet on master\0".into()).branch,
            "master"
        );
        assert_eq!(
            parse_git_status("## Initial commit on dev\0".into()).branch,
            "dev"
        );
        let d = parse_git_status("## HEAD (no branch)\0".into());
        assert!(d.detached);
        let g = parse_git_status("## feat...origin/feat [gone]\0".into());
        assert_eq!(g.branch, "feat");
        assert_eq!((g.ahead, g.behind), (0, 0));
        let plain = parse_git_status("## main\0".into());
        assert_eq!(plain.upstream, None);
    }

    #[test]
    fn paths_with_spaces_survive_z_format() {
        let st = parse_git_status("## main\0?? my data/hasil run 1.txt\0".into());
        assert_eq!(st.changes[0].path, "my data/hasil run 1.txt");
    }

    #[test]
    fn builds_quoted_commands() {
        let repo = "/home/user/proj x".to_string();
        assert_eq!(
            git_command(repo.clone(), GitOp::Status),
            "GIT_TERMINAL_PROMPT=0 LC_ALL=C git -C '/home/user/proj x' status --porcelain -b -z"
        );
        assert_eq!(
            git_command(repo.clone(), GitOp::Commit { message: "fix: it's done".into() }),
            "GIT_TERMINAL_PROMPT=0 LC_ALL=C git -C '/home/user/proj x' commit -m 'fix: it'\\''s done'"
        );
        assert!(git_command(
            repo.clone(),
            GitOp::Stage {
                paths: vec!["a.py".into(), "b c.py".into()]
            }
        )
        .ends_with("add -A -- a.py 'b c.py'"));
        assert!(git_command(
            repo.clone(),
            GitOp::Diff {
                path: "a.py".into(),
                staged: true
            }
        )
        .ends_with("diff --cached -- a.py"));
        assert!(
            git_command(repo, GitOp::Log { count: 0 }).ends_with("log --oneline --decorate -n 1")
        );
    }
}
