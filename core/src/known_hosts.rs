use std::fs;
use std::path::Path;

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum HostKeyCheck {
    Trusted,
    Unknown,
    Changed { previous_fingerprint: String },
}

struct Entry {
    host: String,
    key: String,
    fingerprint: String,
}

fn host_id(host: &str, port: u16) -> String {
    format!("{}:{}", host.to_lowercase(), port)
}

fn key_core(key: &str) -> String {
    key.split_whitespace().take(2).collect::<Vec<_>>().join(" ")
}

fn read(path: &str) -> Vec<Entry> {
    fs::read_to_string(path)
        .unwrap_or_default()
        .lines()
        .filter(|l| !l.trim().is_empty() && !l.starts_with('#'))
        .filter_map(|l| {
            let p: Vec<&str> = l.split_whitespace().collect();
            (p.len() >= 4).then(|| Entry {
                host: p[0].into(),
                key: format!("{} {}", p[1], p[2]),
                fingerprint: p[3].into(),
            })
        })
        .collect()
}

fn write(path: &str, entries: &[Entry]) -> std::io::Result<()> {
    if let Some(dir) = Path::new(path).parent() {
        fs::create_dir_all(dir)?;
    }
    let body: String = entries
        .iter()
        .map(|e| format!("{} {} {}\n", e.host, e.key, e.fingerprint))
        .collect();
    let tmp = format!("{path}.tmp");
    fs::write(&tmp, body)?;
    fs::rename(tmp, path)
}

#[uniffi::export]
pub fn known_hosts_check(
    file: String,
    host: String,
    port: u16,
    public_key: String,
) -> HostKeyCheck {
    let id = host_id(&host, port);
    match read(&file).into_iter().find(|e| e.host == id) {
        None => HostKeyCheck::Unknown,
        Some(e) if e.key == key_core(&public_key) => HostKeyCheck::Trusted,
        Some(e) => HostKeyCheck::Changed {
            previous_fingerprint: e.fingerprint,
        },
    }
}

#[uniffi::export]
pub fn known_hosts_trust(
    file: String,
    host: String,
    port: u16,
    public_key: String,
    fingerprint: String,
) -> bool {
    let id = host_id(&host, port);
    let mut entries: Vec<Entry> = read(&file).into_iter().filter(|e| e.host != id).collect();
    entries.push(Entry {
        host: id,
        key: key_core(&public_key),
        fingerprint,
    });
    write(&file, &entries).is_ok()
}

#[uniffi::export]
pub fn known_hosts_forget(file: String, host: String, port: u16) -> bool {
    let id = host_id(&host, port);
    let entries: Vec<Entry> = read(&file).into_iter().filter(|e| e.host != id).collect();
    write(&file, &entries).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trust_on_first_use_then_detect_change() {
        let dir = tempfile::tempdir().unwrap();
        let f = dir.path().join("known_hosts").to_string_lossy().to_string();
        let k1 = "ssh-ed25519 AAAAkeyone comment";
        let k2 = "ssh-ed25519 AAAAkeytwo";

        assert_eq!(
            known_hosts_check(f.clone(), "Server.Example".into(), 22, k1.into()),
            HostKeyCheck::Unknown
        );
        assert!(known_hosts_trust(
            f.clone(),
            "server.example".into(),
            22,
            k1.into(),
            "SHA256:one".into()
        ));
        assert_eq!(
            known_hosts_check(
                f.clone(),
                "SERVER.example".into(),
                22,
                "ssh-ed25519 AAAAkeyone".into()
            ),
            HostKeyCheck::Trusted
        );
        assert_eq!(
            known_hosts_check(f.clone(), "server.example".into(), 2222, k1.into()),
            HostKeyCheck::Unknown
        );
        assert_eq!(
            known_hosts_check(f.clone(), "server.example".into(), 22, k2.into()),
            HostKeyCheck::Changed {
                previous_fingerprint: "SHA256:one".into()
            }
        );
        assert!(known_hosts_trust(
            f.clone(),
            "server.example".into(),
            22,
            k2.into(),
            "SHA256:two".into()
        ));
        assert_eq!(
            known_hosts_check(f.clone(), "server.example".into(), 22, k2.into()),
            HostKeyCheck::Trusted
        );
        assert_eq!(fs::read_to_string(&f).unwrap().lines().count(), 1);

        assert!(known_hosts_forget(f.clone(), "server.example".into(), 22));
        assert_eq!(
            known_hosts_check(f, "server.example".into(), 22, k2.into()),
            HostKeyCheck::Unknown
        );
    }
}
