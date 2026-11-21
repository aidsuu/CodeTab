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
        eprintln!("JANGKAU_E2E_* tidak diset, tes dilewati");
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
        "dua host baru harus dikonfirmasi"
    );
    assert!(s.is_connected());
    assert!(s.label().contains("via localhost"));
    assert!(!s.home().is_empty());

    let work = format!("{dir}/work");
    let _ = s.delete(work.clone(), true);
    s.mkdir(work.clone()).unwrap();
    let f = format!("{work}/hello world.py");
    s.write(f.clone(), b"print('halo dari server')\n".to_vec())
        .unwrap();
    assert_eq!(
        s.read(f.clone(), 1024).unwrap(),
        b"print('halo dari server')\n"
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
    assert_eq!(s.stat(g.clone()).unwrap().unwrap().size, 26);

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
    assert_eq!(r.stdout, "print('halo dari server')\n");
    assert_eq!(r.stderr.trim(), "err");

    let updates = Arc::new(Mutex::new(0));
    let t = s
        .open_terminal(80, 24, Box::new(TermL(updates.clone())))
        .unwrap();
    t.send(b"echo JANGKAU_$((40+2)); tput cols\r".to_vec());
    let start = Instant::now();
    while !(t.screen_text().contains("JANGKAU_42") && t.screen_text().contains("\n80"))
        && start.elapsed() < Duration::from_secs(10)
    {
        std::thread::sleep(Duration::from_millis(100));
    }
    let text = t.screen_text();
    assert!(text.contains("JANGKAU_42"), "layar: {text}");
    assert!(text.contains("\n80"), "PTY harus 80 kolom: {text}");
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
        "SHA256:lama".into(),
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
    assert!(err.to_string().contains("ditolak"), "{err}");
}
