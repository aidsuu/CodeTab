use jangkau_core::*;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

struct Listener {
    prompts: Arc<AtomicUsize>,
    accept: bool,
}

impl ConnectListener for Listener {
    fn progress(&self, step: String) {
        eprintln!("progress: {step}");
    }
    fn confirm_host_key(&self, host: String, port: u16, fp: String, prev: Option<String>) -> bool {
        eprintln!("host key {host}:{port} {fp} prev={prev:?}");
        self.prompts.fetch_add(1, Ordering::SeqCst);
        self.accept
    }
}

struct TermL(Arc<Mutex<u32>>);
impl TerminalListener for TermL {
    fn on_update(&self) {
        *self.0.lock().unwrap() += 1;
    }
    fn on_exit(&self, _m: Option<String>) {}
}

fn env() -> Option<(u16, String, String, String)> {
    let port = std::env::var("JANGKAU_E2E_PORT").ok()?.parse().ok()?;
    let user = std::env::var("JANGKAU_E2E_USER").ok()?;
    let key = std::fs::read_to_string(std::env::var("JANGKAU_E2E_KEY").ok()?).ok()?;
    let dir = std::env::var("JANGKAU_E2E_DIR").ok()?;
    Some((port, user, key, dir))
}

fn hop(host: &str, port: u16, user: &str, key: &str) -> Hop {
    Hop {
        host: host.into(),
        port,
        user: user.into(),
        key_pem: key.into(),
        passphrase: None,
    }
}

#[test]
fn jump_sftp_exec_and_terminal() {
    let Some((port, user, key, dir)) = env() else {
        eprintln!("JANGKAU_E2E_* not set, skipping");
        return;
    };
    let kh = format!("{dir}/app_known_hosts");
    let _ = std::fs::remove_file(&kh);
    let prompts = Arc::new(AtomicUsize::new(0));

    let cfg = ConnectConfig {
        target: hop("127.0.0.1", port, &user, &key),
        jump: Some(hop("localhost", port, &user, &key)),
        known_hosts_file: kh.clone(),
        timeout_secs: 20,
    };
    let s = Session::connect(
        cfg.clone(),
        Box::new(Listener {
            prompts: prompts.clone(),
            accept: true,
        }),
    )
    .unwrap();
    assert_eq!(
        prompts.load(Ordering::SeqCst),
        2,
        "both new hosts must be confirmed"
    );
    assert!(s.is_connected());
    assert!(s.ping(5000).unwrap() < 5000);
    assert!(s.label().contains("via localhost"));
    assert!(!s.home().is_empty());

    let work = format!("{dir}/work");
    let _ = s.delete(work.clone(), true);
    s.mkdir(work.clone()).unwrap();
    let f = format!("{work}/hello world.py");
    s.write(f.clone(), b"print('hello from server')\n".to_vec())
        .unwrap();
    assert_eq!(
        s.read(f.clone(), 1024).unwrap(),
        b"print('hello from server')\n"
    );
    assert!(matches!(
        s.read(f.clone(), 4),
        Err(CoreError::TooLarge { .. })
    ));
    s.mkdir(format!("{work}/sub")).unwrap();
    let names: Vec<String> = s
        .list(work.clone())
        .unwrap()
        .into_iter()
        .map(|e| e.name)
        .collect();
    assert_eq!(names, vec!["sub".to_string(), "hello world.py".to_string()]);
    let g = format!("{work}/renamed.py");
    s.rename(f.clone(), g.clone()).unwrap();
    assert!(s.stat(f).unwrap().is_none());
    assert_eq!(s.stat(g.clone()).unwrap().unwrap().size, 27);

    let r = s
        .exec(
            format!(
                "cd {} && cat {} && echo err >&2 && exit 3",
                sh_quote(work.clone()),
                sh_quote("renamed.py".into())
            ),
            20,
        )
        .unwrap();
    assert_eq!(r.exit_code, 3);
    assert_eq!(r.stdout, "print('hello from server')\n");
    assert_eq!(r.stderr.trim(), "err");

    let updates = Arc::new(Mutex::new(0));
    let t = s
        .open_terminal(80, 24, None, Box::new(TermL(updates.clone())))
        .unwrap();
    t.send(b"echo JANGKAU_$((40+2)); tput cols\r".to_vec());
    let start = Instant::now();
    while !(t.screen_text().contains("JANGKAU_42") && t.screen_text().contains("\n80"))
        && start.elapsed() < Duration::from_secs(10)
    {
        std::thread::sleep(Duration::from_millis(100));
    }
    let text = t.screen_text();
    assert!(text.contains("JANGKAU_42"), "screen: {text}");
    assert!(text.contains("\n80"), "PTY must be 80 columns: {text}");
    t.resize(100, 30);
    t.send(b"tput cols\r".to_vec());
    let start = Instant::now();
    while !t.screen_text().contains("\n100") && start.elapsed() < Duration::from_secs(10) {
        std::thread::sleep(Duration::from_millis(100));
    }
    assert!(
        t.screen_text().contains("\n100"),
        "resize: {}",
        t.screen_text()
    );
    assert!(*updates.lock().unwrap() > 0);
    t.send(b"exit\r".to_vec());
    let start = Instant::now();
    while t.is_open() && start.elapsed() < Duration::from_secs(10) {
        std::thread::sleep(Duration::from_millis(100));
    }
    assert!(!t.is_open());

    s.delete(work, true).unwrap();
    s.disconnect();

    let p2 = Arc::new(AtomicUsize::new(0));
    let s2 = Session::connect(
        cfg.clone(),
        Box::new(Listener {
            prompts: p2.clone(),
            accept: true,
        }),
    )
    .unwrap();
    assert_eq!(p2.load(Ordering::SeqCst), 0);
    s2.disconnect();

    known_hosts_trust(
        kh.clone(),
        "localhost".into(),
        port,
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeFakeFakeFakeFakeFakeFakeFakeFakeFakeFake".into(),
        "SHA256:old".into(),
    );
    let p3 = Arc::new(AtomicUsize::new(0));
    let err = Session::connect(
        cfg,
        Box::new(Listener {
            prompts: p3.clone(),
            accept: false,
        }),
    )
    .err()
    .unwrap();
    assert!(matches!(err, CoreError::HostKeyRejected(_)), "{err:?}");
    assert_eq!(p3.load(Ordering::SeqCst), 1);
}

fn wait_for(t: &Terminal, needle: &str) -> bool {
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(15) {
        if t.screen_text().contains(needle) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    false
}

#[test]
fn tmux_session_survives_reconnect() {
    let Some((port, user, key, dir)) = env() else {
        return;
    };
    let cfg = ConnectConfig {
        target: hop("127.0.0.1", port, &user, &key),
        jump: None,
        known_hosts_file: format!("{dir}/kh_tmux"),
        timeout_secs: 20,
    };
    let connect = || {
        Session::connect(
            cfg.clone(),
            Box::new(Listener {
                prompts: Arc::new(AtomicUsize::new(0)),
                accept: true,
            }),
        )
        .unwrap()
    };
    let probe = |s: &Session| parse_tmux_probe(s.exec(tmux_probe_command(), 30).unwrap().stdout);
    let updates = Arc::new(Mutex::new(0));

    let s = connect();
    if !probe(&s).available {
        eprintln!("tmux is not installed, skipping");
        s.disconnect();
        return;
    }
    let name = format!("codetab-e2e{}", std::process::id());
    let _ = s.exec(tmux_kill_command(name.clone()), 30);
    let t = s
        .open_terminal(
            80,
            24,
            Some(tmux_attach_command(name.clone())),
            Box::new(TermL(updates.clone())),
        )
        .unwrap();
    std::thread::sleep(Duration::from_millis(500));
    t.send(b"echo KEEP_$((6*7))\r".to_vec());
    assert!(wait_for(&t, "KEEP_42"), "screen: {}", t.screen_text());
    s.disconnect();

    let s2 = connect();
    assert!(probe(&s2).sessions.contains(&name));
    let t2 = s2
        .open_terminal(
            80,
            24,
            Some(tmux_attach_command(name.clone())),
            Box::new(TermL(updates)),
        )
        .unwrap();
    assert!(wait_for(&t2, "KEEP_42"), "screen: {}", t2.screen_text());
    assert!(t2.snapshot(0).mouse_reporting);
    s2.exec(tmux_kill_command(name.clone()), 30).unwrap();
    assert!(!probe(&s2).sessions.contains(&name));
    s2.disconnect();
}

#[test]
fn wrong_key_is_rejected() {
    let Some((port, user, _key, dir)) = env() else {
        return;
    };
    let other = generate_ed25519("lain".into()).unwrap().private_pem;
    let cfg = ConnectConfig {
        target: hop("127.0.0.1", port, &user, &other),
        jump: None,
        known_hosts_file: format!("{dir}/kh_wrong"),
        timeout_secs: 20,
    };
    let err = Session::connect(
        cfg,
        Box::new(Listener {
            prompts: Arc::new(AtomicUsize::new(0)),
            accept: true,
        }),
    )
    .err()
    .unwrap();
    assert!(matches!(err, CoreError::Auth(_)), "{err:?}");
}

#[test]
fn closed_port_gives_clear_error() {
    if env().is_none() {
        return;
    }
    let key = generate_ed25519("x".into()).unwrap().private_pem;
    let cfg = ConnectConfig {
        target: hop("127.0.0.1", 1, "nobody", &key),
        jump: None,
        known_hosts_file: "/dev/null".into(),
        timeout_secs: 5,
    };
    let err = Session::connect(
        cfg,
        Box::new(Listener {
            prompts: Arc::new(AtomicUsize::new(0)),
            accept: true,
        }),
    )
    .err()
    .unwrap();
    assert!(err.to_string().contains("refused"), "{err}");
}

#[derive(Default)]
struct LspEvents {
    diagnostics: Mutex<Vec<(String, Vec<LspDiagnostic>)>>,
    messages: Mutex<Vec<String>>,
    progress: Mutex<Vec<Option<String>>>,
    exit: Mutex<Option<String>>,
}

struct LspL(Arc<LspEvents>);
impl LspListener for LspL {
    fn on_diagnostics(&self, path: String, diagnostics: Vec<LspDiagnostic>) {
        self.0.diagnostics.lock().unwrap().push((path, diagnostics));
    }
    fn on_progress(&self, message: Option<String>) {
        self.0.progress.lock().unwrap().push(message);
    }
    fn on_message(&self, _severity: u8, message: String) {
        self.0.messages.lock().unwrap().push(message);
    }
    fn on_exit(&self, message: String) {
        *self.0.exit.lock().unwrap() = Some(message);
    }
}

fn until(mut f: impl FnMut() -> bool) -> bool {
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(20) {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    false
}

fn plain_session(port: u16, user: &str, key: &str, dir: &str, name: &str) -> Arc<Session> {
    Session::connect(
        ConnectConfig {
            target: hop("127.0.0.1", port, user, key),
            jump: None,
            known_hosts_file: format!("{dir}/kh_{name}"),
            timeout_secs: 20,
        },
        Box::new(Listener {
            prompts: Arc::new(AtomicUsize::new(0)),
            accept: true,
        }),
    )
    .unwrap()
}

#[test]
fn language_server_over_ssh() {
    let Some((port, user, key, dir)) = env() else {
        return;
    };
    let s = plain_session(port, &user, &key, &dir, "lsp");
    let root = format!("{dir}/lsp project");
    std::fs::create_dir_all(&root).unwrap();
    let file = format!("{root}/main.py");
    let text = "greeting = 1\ngreet";
    std::fs::write(&file, text).unwrap();
    let server_py = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fake_lsp.py");

    let events = Arc::new(LspEvents::default());
    let ls = s
        .start_language_server(
            lsp_launch_command(
                format!("python3 {}", sh_quote(server_py.into())),
                root.clone(),
                None,
            ),
            root.clone(),
            r#"{"fake": {"option": 42}}"#.into(),
            "null".into(),
            30,
            false,
            Box::new(LspL(events.clone())),
        )
        .unwrap();
    let info = ls.info();
    assert_eq!(info.name, "fake-lsp");
    assert!(info.incremental_sync && info.hover && info.definition && info.signature_help);
    assert_eq!(info.trigger_characters, vec!["."]);
    assert!(until(|| events
        .messages
        .lock()
        .unwrap()
        .iter()
        .any(|m| m == "config [42]")));
    assert!(until(
        || events.progress.lock().unwrap().last() == Some(&None)
    ));
    assert!(ls.stderr_tail().contains("lsp project"));

    ls.did_open(file.clone(), "python".into(), 1, text.into())
        .unwrap();
    assert!(until(|| events
        .diagnostics
        .lock()
        .unwrap()
        .iter()
        .any(|(p, _)| *p == file)));

    let c = ls.completion(file.clone(), 1, 5, None, 5000).unwrap();
    let item = c
        .items
        .iter()
        .find(|i| i.label == "greeting")
        .expect("greeting suggested");
    assert_eq!(item.edit_range.unwrap().start.character, 0);
    assert!(c
        .items
        .iter()
        .any(|i| i.snippet && i.insert_text == "snippet(${1:arg})$0"));

    ls.did_change(
        file.clone(),
        2,
        vec![LspChange {
            range: Some(LspRange {
                start: LspPosition {
                    line: 1,
                    character: 0,
                },
                end: LspPosition {
                    line: 1,
                    character: 0,
                },
            }),
            text: "# TODO ".into(),
        }],
    )
    .unwrap();
    assert!(until(|| {
        events
            .diagnostics
            .lock()
            .unwrap()
            .last()
            .is_some_and(|(_, d)| {
                d.len() == 1
                    && d[0].range.start
                        == LspPosition {
                            line: 1,
                            character: 2,
                        }
                    && d[0].severity == 2
            })
    }));
    let hover = ls.hover(file.clone(), 1, 3, 5000).unwrap().unwrap();
    assert_eq!(hover, "Line: `# TODO greet`");
    let def = ls.definition(file.clone(), 1, 8, 5000).unwrap();
    assert_eq!(def[0].path, file);
    let sig = ls
        .signature_help(file.clone(), 1, 8, 5000)
        .unwrap()
        .unwrap();
    assert_eq!(
        &sig.label[sig.active_start.unwrap() as usize..sig.active_end.unwrap() as usize],
        "times"
    );

    ls.did_save(file.clone(), String::new()).unwrap();
    ls.did_close(file.clone()).unwrap();
    ls.shutdown();
    assert!(until(|| events.exit.lock().unwrap().is_some()));
    assert!(!ls.is_alive());
    assert!(matches!(ls.hover(file, 0, 0, 1000), Err(CoreError::Closed)));

    let events = Arc::new(LspEvents::default());
    let err = s
        .start_language_server(
            lsp_launch_command("no-such-language-server --stdio".into(), root.clone(), None),
            root,
            "null".into(),
            "null".into(),
            10,
            false,
            Box::new(LspL(events)),
        )
        .err()
        .unwrap()
        .to_string();
    assert!(
        err.contains("stopped during startup") && err.contains("not found"),
        "{err}"
    );
    s.disconnect();
}

/// Talks to every real language server installed on the test machine. Run with
/// `CODETAB_LSP_REAL=1 scripts/e2e-sshd.sh real_language_servers`.
#[test]
fn real_language_servers() {
    let Some((port, user, key, dir)) = env() else {
        return;
    };
    if std::env::var("CODETAB_LSP_REAL").is_err() {
        return;
    }
    let s = plain_session(port, &user, &key, &dir, "real");
    let servers = language_servers();
    let bins: Vec<String> = servers.iter().map(|s| s.binary.clone()).collect();
    let mut probe = parse_lsp_probe(
        s.exec(lsp_probe_command(bins, Some("python3".into())), 60)
            .unwrap()
            .stdout,
    );
    let extra = std::env::var("CODETAB_LSP_PATH").unwrap_or_default();
    let mut ours = Vec::new();
    for d in extra.split(':').filter(|d| !d.is_empty()) {
        for spec in &servers {
            if std::path::Path::new(d).join(&spec.binary).exists() {
                ours.push(spec.binary.clone());
                if !probe.binaries.contains(&spec.binary) {
                    probe.binaries.push(spec.binary.clone());
                }
            }
        }
    }
    let mut failed = Vec::new();
    let mut worked: Vec<String> = Vec::new();
    eprintln!(
        "installed: {:?}, python: {:?}",
        probe.binaries, probe.python
    );
    let samples: &[(&str, &str, &str, &str)] = &[
        ("python", "main.py", "import os\nos.pa", "path"),
        (
            "c",
            "main.c",
            "#include <stdio.h>\nint main(void) { pri",
            "printf",
        ),
        (
            "rust",
            "src/main.rs",
            "fn main() {\n    let v: Vec<u8> = Vec::new();\n    v.le",
            "len",
        ),
        (
            "go",
            "main.go",
            "package main\n\nimport \"strings\"\n\nfunc main() { strings.ToUp",
            "ToUpper",
        ),
        (
            "typescript",
            "main.ts",
            "const s = \"x\";\ns.toUpper",
            "toUpperCase",
        ),
        ("javascript", "main.js", "const a = [1];\na.pu", "push"),
        ("css", "style.css", "body { colo", "color"),
        ("html", "index.html", "<html><bo", "body"),
        ("json", "package.json", "{\n  \"scr", "scripts"),
        ("shellscript", "run.sh", "#!/bin/bash\nech", "echo"),
        ("yaml", "a.yaml", "key: value\n", ""),
        ("dockerfile", "Dockerfile", "FROM debian\nRU", "RUN"),
        ("php", "index.php", "<?php\n$x = str_rep", "str_replace"),
        (
            "latex",
            "doc.tex",
            "\\documentclass{article}\n\\beg",
            "begin",
        ),
    ];
    let mut tried = 0;
    for spec in &servers {
        if !probe.binaries.contains(&spec.binary) {
            continue;
        }
        if std::env::var("CODETAB_LSP_ONLY").is_ok_and(|o| o != spec.id) {
            continue;
        }
        let Some((lang, name, text, expect)) = samples
            .iter()
            .find(|(l, ..)| spec.languages.iter().any(|x| x == l))
        else {
            continue;
        };
        tried += 1;
        let root = format!("{dir}/real-{}", spec.id);
        let path = format!("{root}/{name}");
        std::fs::create_dir_all(std::path::Path::new(&path).parent().unwrap()).unwrap();
        std::fs::write(&path, text).unwrap();
        if spec.id == "rust-analyzer" {
            std::fs::write(
                format!("{root}/Cargo.toml"),
                "[package]\nname = \"t\"\nversion = \"0.1.0\"\nedition = \"2021\"\n",
            )
            .unwrap();
        }
        if spec.id == "gopls" {
            std::fs::write(format!("{root}/go.mod"), "module t\n\ngo 1.21\n").unwrap();
        }
        let cfg = lsp_server_config(spec.id.clone(), probe.python.clone());
        let events = Arc::new(LspEvents::default());
        let started = Instant::now();
        let ls = match s.start_language_server(
            lsp_launch_command(
                format!("PATH=\"{extra}:$PATH\"; {}", spec.command),
                root.clone(),
                probe.python.clone(),
            ),
            root.clone(),
            cfg.settings_json,
            cfg.init_options_json,
            spec.init_timeout_secs,
            spec.wait_for_index,
            Box::new(LspL(events.clone())),
        ) {
            Ok(ls) => ls,
            Err(e) => {
                eprintln!("{:>14}: could not start: {e}", spec.id);
                if ours.contains(&spec.binary) {
                    failed.push(spec.id.clone());
                }
                continue;
            }
        };
        ls.did_open(path.clone(), lang.to_string(), 1, text.to_string())
            .unwrap();
        let last = text.lines().last().unwrap_or("");
        let (line, col) = (
            text.lines().count().saturating_sub(1) as u32,
            last.encode_utf16().count() as u32,
        );
        let mut labels = Vec::new();
        for _ in 0..20 {
            let c = ls
                .completion(path.clone(), line, col, None, 20000)
                .unwrap_or_else(|e| {
                    eprintln!("{:>14}: completion failed: {e}", spec.id);
                    LspCompletion {
                        incomplete: true,
                        items: vec![],
                    }
                });
            labels = c
                .items
                .iter()
                .map(|i| i.label.trim().to_string())
                .collect::<Vec<_>>();
            if expect.is_empty() || labels.iter().any(|l| l.contains(expect)) {
                break;
            }
            std::thread::sleep(Duration::from_millis(1000));
        }
        eprintln!(
            "{:>14}: {} items in {:.1}s, sample {:?}",
            spec.id,
            labels.len(),
            started.elapsed().as_secs_f32(),
            labels.iter().take(4).collect::<Vec<_>>()
        );
        if !expect.is_empty() && !labels.iter().any(|l| l.contains(expect)) {
            eprintln!(
                "{:>14}: no {expect}; alive {} exit {:?} messages {:?}\n{}",
                spec.id,
                ls.is_alive(),
                events.exit.lock().unwrap(),
                events.messages.lock().unwrap(),
                ls.stderr_tail()
            );
            failed.push(spec.id.clone());
        } else {
            worked.push(spec.id.clone());
        }
        ls.shutdown();
    }
    failed.retain(|id| {
        let langs = &servers.iter().find(|s| s.id == *id).unwrap().languages;
        !worked.iter().any(|w| {
            servers
                .iter()
                .any(|s| s.id == *w && s.languages.iter().any(|l| langs.contains(l)))
        })
    });
    eprintln!("tried {tried} real servers");
    assert!(failed.is_empty(), "failed: {failed:?}");
    s.disconnect();
}
