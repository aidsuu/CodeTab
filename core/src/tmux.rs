use crate::paths::quote;

const MARKER: &str = "__codetab_tmux__";
pub const TMUX_SESSION_PREFIX: &str = "codetab-";

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TmuxInfo {
    pub available: bool,
    pub sessions: Vec<String>,
}

fn login_shell(script: &str) -> String {
    format!("exec \"${{SHELL:-/bin/sh}}\" -lc {}", quote(script))
}

/// Attaches to (or creates) a tmux session, falling back to a plain login shell without tmux.
#[uniffi::export]
pub fn tmux_attach_command(session: String) -> String {
    let s = quote(&session);
    login_shell(&format!(
        "if command -v tmux >/dev/null 2>&1; then \
         exec tmux new-session -A -s {s} \\; set-option -q status off \\; set-option -q mouse on; \
         fi; exec \"${{SHELL:-/bin/sh}}\" -l"
    ))
}

#[uniffi::export]
pub fn tmux_probe_command() -> String {
    login_shell(&format!(
        "command -v tmux >/dev/null 2>&1 || exit 0; echo {MARKER}; \
         tmux list-sessions -F '#{{session_name}}' 2>/dev/null; exit 0"
    ))
}

#[uniffi::export]
pub fn tmux_kill_command(session: String) -> String {
    login_shell(&format!(
        "tmux kill-session -t ={} 2>/dev/null; exit 0",
        quote(&session)
    ))
}

#[uniffi::export]
pub fn parse_tmux_probe(output: String) -> TmuxInfo {
    let mut lines = output.lines().map(str::trim);
    if !lines.any(|l| l == MARKER) {
        return TmuxInfo {
            available: false,
            sessions: Vec::new(),
        };
    }
    let mut sessions: Vec<String> = lines
        .filter(|l| l.starts_with(TMUX_SESSION_PREFIX))
        .map(str::to_string)
        .collect();
    sessions.sort_by_key(|s| {
        let n = s[TMUX_SESSION_PREFIX.len()..]
            .parse::<u64>()
            .unwrap_or(u64::MAX);
        (n, s.clone())
    });
    sessions.dedup();
    TmuxInfo {
        available: true,
        sessions,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn probe_output_is_parsed() {
        let out = "Welcome\n__codetab_tmux__\nwork\ncodetab-10\ncodetab-2\n";
        assert_eq!(
            parse_tmux_probe(out.into()),
            TmuxInfo {
                available: true,
                sessions: vec!["codetab-2".into(), "codetab-10".into()],
            }
        );
        assert!(!parse_tmux_probe("no tmux here\n".into()).available);
        assert!(parse_tmux_probe("__codetab_tmux__\n".into())
            .sessions
            .is_empty());
    }

    #[test]
    fn commands_run_in_a_login_shell() {
        let attach = tmux_attach_command("codetab-3".into());
        assert!(attach.starts_with("exec \"${SHELL:-/bin/sh}\" -lc '"));
        assert!(attach.contains("new-session -A -s codetab-3 "));
        assert!(attach.contains("\\; set-option -q mouse on"));
        assert!(tmux_kill_command("codetab-3".into()).contains("kill-session -t =codetab-3 "));
        assert!(tmux_probe_command().contains("list-sessions"));
    }
}
