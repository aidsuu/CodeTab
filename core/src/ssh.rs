use crate::error::CoreError;
use crate::keys::decode;
use crate::known_hosts::{known_hosts_check, known_hosts_trust, HostKeyCheck};
use crate::lsp::{Incoming, LanguageServer, LspIo, LspListener, LspStart, Outbound};
use crate::paths::{join, quote};
use crate::runtime::rt;
use crate::term::{Outgoing, Terminal, TerminalListener};
use russh::client::{self, Handle};
use russh::keys::{HashAlg, PrivateKeyWithHashAlg, PublicKey, PublicKeyOrCertificate};
use russh::{ChannelMsg, Disconnect};
use russh_sftp::client::SftpSession;
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, Instant};
use tokio::io::AsyncWriteExt;
use tokio::sync::Mutex;

#[uniffi::export(callback_interface)]
pub trait ConnectListener: Send + Sync {
    fn progress(&self, step: String);
    fn confirm_host_key(
        &self,
        host: String,
        port: u16,
        fingerprint: String,
        previous_fingerprint: Option<String>,
    ) -> bool;
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Hop {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub key_pem: String,
    pub passphrase: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ConnectConfig {
    pub target: Hop,
    pub jump: Option<Hop>,
    pub known_hosts_file: String,
    pub timeout_secs: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RemoteEntry {
    pub path: String,
    pub name: String,
    pub is_dir: bool,
    pub is_link: bool,
    pub size: u64,
    pub mtime: u64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ExecResult {
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
}

struct ClientHandler {
    host: String,
    port: u16,
    known_hosts: String,
    listener: Arc<dyn ConnectListener>,
    rejected: Arc<StdMutex<Option<String>>>,
}

impl client::Handler for ClientHandler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        key: &PublicKeyOrCertificate,
    ) -> Result<bool, Self::Error> {
        let key: PublicKey = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.clone(),
            PublicKeyOrCertificate::Certificate(cert) => {
                PublicKey::new(cert.public_key().clone(), "")
            }
        };
        let line = key.to_openssh().unwrap_or_default();
        let fp = key.fingerprint(HashAlg::Sha256).to_string();
        let shown = format!("{} {}", key.algorithm(), fp);
        let prev = match known_hosts_check(
            self.known_hosts.clone(),
            self.host.clone(),
            self.port,
            line.clone(),
        ) {
            HostKeyCheck::Trusted => return Ok(true),
            HostKeyCheck::Unknown => None,
            HostKeyCheck::Changed {
                previous_fingerprint,
            } => Some(previous_fingerprint),
        };
        let listener = self.listener.clone();
        let (host, port) = (self.host.clone(), self.port);
        let accepted =
            tokio::task::block_in_place(move || listener.confirm_host_key(host, port, shown, prev));
        if accepted {
            known_hosts_trust(
                self.known_hosts.clone(),
                self.host.clone(),
                self.port,
                line,
                fp,
            );
        } else {
            *self.rejected.lock().unwrap() = Some(format!(
                "Host key for {} not trusted, connection cancelled",
                self.host
            ));
        }
        Ok(accepted)
    }
}

type ClientHandle = Handle<ClientHandler>;

#[derive(uniffi::Object)]
pub struct Session {
    jump: Option<ClientHandle>,
    handle: Mutex<ClientHandle>,
    sftp: SftpSession,
    label: String,
    home: String,
}

fn ssh_config() -> Arc<client::Config> {
    Arc::new(client::Config {
        inactivity_timeout: None,
        keepalive_interval: Some(Duration::from_secs(10)),
        keepalive_max: 3,
        nodelay: true,
        ..Default::default()
    })
}

fn describe_connect(e: russh::Error, host: &str, rejected: &StdMutex<Option<String>>) -> CoreError {
    if let Some(msg) = rejected.lock().unwrap().take() {
        return CoreError::HostKeyRejected(msg);
    }
    match e {
        russh::Error::IO(io) => match io.kind() {
            std::io::ErrorKind::ConnectionRefused => CoreError::Connect(format!(
                "Connection to {host} refused (check the host and port)"
            )),
            std::io::ErrorKind::TimedOut => CoreError::Connect(format!(
                "Timed out connecting to {host} (check the network or VPN)"
            )),
            _ if io.to_string().contains("failed to lookup")
                || io.to_string().contains("Name or service") =>
            {
                CoreError::Connect(format!(
                    "Host {host} not found (check the host name or VPN)"
                ))
            }
            _ => CoreError::Connect(format!("Could not connect to {host}: {io}")),
        },
        russh::Error::UnknownKey => {
            CoreError::HostKeyRejected(format!("Host key for {host} not trusted"))
        }
        other => CoreError::Connect(format!("Could not connect to {host}: {other}")),
    }
}

async fn authenticate(handle: &mut ClientHandle, hop: &Hop) -> Result<(), CoreError> {
    let key = decode(&hop.key_pem, hop.passphrase.as_deref())?;
    let hash = handle
        .best_supported_rsa_hash()
        .await
        .ok()
        .flatten()
        .flatten();
    let res = handle
        .authenticate_publickey(
            hop.user.clone(),
            PrivateKeyWithHashAlg::new(Arc::new(key), hash),
        )
        .await
        .map_err(|e| {
            CoreError::Auth(format!(
                "Authentication for {}@{} failed: {e}",
                hop.user, hop.host
            ))
        })?;
    if res.success() {
        Ok(())
    } else {
        Err(CoreError::Auth(format!(
            "Authentication for {}@{} was rejected. Make sure the public key is in ~/.ssh/authorized_keys and the user name is correct.",
            hop.user, hop.host
        )))
    }
}

fn handler(
    hop: &Hop,
    cfg: &ConnectConfig,
    listener: &Arc<dyn ConnectListener>,
    rejected: &Arc<StdMutex<Option<String>>>,
) -> ClientHandler {
    ClientHandler {
        host: hop.host.clone(),
        port: hop.port,
        known_hosts: cfg.known_hosts_file.clone(),
        listener: listener.clone(),
        rejected: rejected.clone(),
    }
}

async fn with_timeout<T>(
    secs: u32,
    what: &str,
    f: impl std::future::Future<Output = Result<T, CoreError>>,
) -> Result<T, CoreError> {
    match tokio::time::timeout(Duration::from_secs(secs.max(5) as u64), f).await {
        Ok(r) => r,
        Err(_) => Err(CoreError::Connect(format!("Timed out: {what}"))),
    }
}

async fn connect_async(
    cfg: ConnectConfig,
    listener: Arc<dyn ConnectListener>,
) -> Result<Session, CoreError> {
    let config = ssh_config();
    let rejected = Arc::new(StdMutex::new(None));
    let t = cfg.timeout_secs;

    let jump = match &cfg.jump {
        Some(j) => {
            listener.progress(format!("Connecting to jump host {}…", j.host));
            let h = handler(j, &cfg, &listener, &rejected);
            let mut handle = with_timeout(t, &format!("connecting to {}", j.host), async {
                client::connect(config.clone(), (j.host.as_str(), j.port), h)
                    .await
                    .map_err(|e| describe_connect(e, &j.host, &rejected))
            })
            .await?;
            listener.progress(format!("Authenticating {}@{}…", j.user, j.host));
            authenticate(&mut handle, j).await?;
            Some(handle)
        }
        None => None,
    };

    let target = &cfg.target;
    let h = handler(target, &cfg, &listener, &rejected);
    let mut handle = match &jump {
        Some(jh) => {
            listener.progress(format!(
                "Opening tunnel to {}:{}…",
                target.host, target.port
            ));
            let ch = jh
                .channel_open_direct_tcpip(target.host.clone(), target.port as u32, "127.0.0.1", 0)
                .await
                .map_err(|e| {
                    CoreError::Connect(format!(
                        "Jump host refused the tunnel to {}:{}: {e}",
                        target.host, target.port
                    ))
                })?;
            with_timeout(t, &format!("handshake {}", target.host), async {
                client::connect_stream(config.clone(), ch.into_stream(), h)
                    .await
                    .map_err(|e| describe_connect(e, &target.host, &rejected))
            })
            .await?
        }
        None => {
            listener.progress(format!("Connecting to {}…", target.host));
            with_timeout(t, &format!("connecting to {}", target.host), async {
                client::connect(config.clone(), (target.host.as_str(), target.port), h)
                    .await
                    .map_err(|e| describe_connect(e, &target.host, &rejected))
            })
            .await?
        }
    };
    listener.progress(format!("Authenticating {}@{}…", target.user, target.host));
    authenticate(&mut handle, target).await?;

    listener.progress("Opening SFTP…".into());
    let ch = handle.channel_open_session().await?;
    ch.request_subsystem(true, "sftp").await?;
    let sftp = SftpSession::new(ch.into_stream()).await?;
    let home = sftp.canonicalize(".").await?;

    let mut label = format!("{}@{}", target.user, target.host);
    if target.port != 22 {
        label.push_str(&format!(":{}", target.port));
    }
    if let Some(j) = &cfg.jump {
        label.push_str(&format!(" via {}", j.host));
    }
    Ok(Session {
        jump,
        handle: Mutex::new(handle),
        sftp,
        label,
        home,
    })
}

#[uniffi::export]
impl Session {
    #[uniffi::constructor]
    pub fn connect(
        config: ConnectConfig,
        listener: Box<dyn ConnectListener>,
    ) -> Result<Arc<Self>, CoreError> {
        let listener: Arc<dyn ConnectListener> = Arc::from(listener);
        rt().block_on(connect_async(config, listener)).map(Arc::new)
    }

    pub fn label(&self) -> String {
        self.label.clone()
    }

    pub fn home(&self) -> String {
        self.home.clone()
    }

    pub fn is_connected(&self) -> bool {
        rt().block_on(async {
            let h = self.handle.lock().await;
            !h.is_closed() && self.jump.as_ref().is_none_or(|j| !j.is_closed())
        })
    }

    /// Round trip of one SFTP request, in milliseconds.
    pub fn ping(&self, timeout_ms: u32) -> Result<u32, CoreError> {
        rt().block_on(async {
            let start = Instant::now();
            let limit = Duration::from_millis(timeout_ms.max(100) as u64);
            match tokio::time::timeout(limit, self.sftp.canonicalize(".")).await {
                Err(_) => Err(CoreError::Connect(
                    "The server did not respond in time".into(),
                )),
                Ok(r) => {
                    r?;
                    Ok(start.elapsed().as_millis().min(u32::MAX as u128) as u32)
                }
            }
        })
    }

    pub fn list(&self, dir: String) -> Result<Vec<RemoteEntry>, CoreError> {
        rt().block_on(async {
            let mut out = Vec::new();
            for e in self.sftp.read_dir(dir.clone()).await? {
                let name = e.file_name();
                if name == "." || name == ".." {
                    continue;
                }
                let meta = e.metadata();
                let path = join(&dir, &name);
                let is_link = e.file_type().is_symlink();
                let is_dir = if is_link {
                    self.sftp
                        .metadata(path.clone())
                        .await
                        .map(|m| m.is_dir())
                        .unwrap_or(false)
                } else {
                    meta.is_dir()
                };
                out.push(RemoteEntry {
                    path,
                    name,
                    is_dir,
                    is_link,
                    size: meta.size.unwrap_or(0),
                    mtime: meta.mtime.unwrap_or(0) as u64,
                });
            }
            out.sort_by(|a, b| {
                b.is_dir
                    .cmp(&a.is_dir)
                    .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
            });
            Ok(out)
        })
    }

    pub fn stat(&self, path: String) -> Result<Option<RemoteEntry>, CoreError> {
        rt().block_on(async {
            if !self.sftp.try_exists(path.clone()).await? {
                return Ok(None);
            }
            let m = self.sftp.metadata(path.clone()).await?;
            Ok(Some(RemoteEntry {
                name: crate::paths::name(&path),
                path,
                is_dir: m.is_dir(),
                is_link: false,
                size: m.size.unwrap_or(0),
                mtime: m.mtime.unwrap_or(0) as u64,
            }))
        })
    }

    pub fn read(&self, path: String, max_bytes: u64) -> Result<Vec<u8>, CoreError> {
        rt().block_on(async {
            let size = self.sftp.metadata(path.clone()).await?.size.unwrap_or(0);
            if size > max_bytes {
                return Err(CoreError::TooLarge { path, size });
            }
            Ok(self.sftp.read(path).await?)
        })
    }

    pub fn write(&self, path: String, data: Vec<u8>) -> Result<(), CoreError> {
        rt().block_on(async {
            let mut f = self.sftp.create(path).await?;
            f.write_all(&data).await?;
            f.shutdown().await?;
            Ok(())
        })
    }

    pub fn mkdir(&self, path: String) -> Result<(), CoreError> {
        rt().block_on(async { Ok(self.sftp.create_dir(path).await?) })
    }

    pub fn rename(&self, from: String, to: String) -> Result<(), CoreError> {
        rt().block_on(async { Ok(self.sftp.rename(from, to).await?) })
    }

    pub fn delete(&self, path: String, is_dir: bool) -> Result<(), CoreError> {
        if is_dir {
            let r = self.exec(format!("rm -rf -- {}", quote(&path)), 120)?;
            if r.exit_code != 0 {
                return Err(CoreError::Io(format!(
                    "Could not delete {path}: {}",
                    r.stderr.trim()
                )));
            }
            Ok(())
        } else {
            rt().block_on(async { Ok(self.sftp.remove_file(path).await?) })
        }
    }

    pub fn exec(&self, command: String, timeout_secs: u32) -> Result<ExecResult, CoreError> {
        rt().block_on(async {
            let mut ch = self.handle.lock().await.channel_open_session().await?;
            ch.exec(true, command).await?;
            let deadline =
                tokio::time::Instant::now() + Duration::from_secs(timeout_secs.max(1) as u64);
            let (mut out, mut err, mut code) = (Vec::new(), Vec::new(), None);
            loop {
                match tokio::time::timeout_at(deadline, ch.wait()).await {
                    Err(_) => {
                        let _ = ch.close().await;
                        return Err(CoreError::Io(format!(
                            "Command exceeded the {timeout_secs} second time limit"
                        )));
                    }
                    Ok(None) => break,
                    Ok(Some(ChannelMsg::Data { data })) => out.extend_from_slice(&data),
                    Ok(Some(ChannelMsg::ExtendedData { data, ext: 1 })) => {
                        err.extend_from_slice(&data)
                    }
                    Ok(Some(ChannelMsg::ExitStatus { exit_status })) => {
                        code = Some(exit_status as i32)
                    }
                    Ok(Some(ChannelMsg::ExitSignal { signal_name, .. })) => {
                        err.extend_from_slice(
                            format!("\n(killed by signal {signal_name:?})").as_bytes(),
                        );
                        code = Some(128);
                    }
                    Ok(Some(_)) => {}
                }
            }
            Ok(ExecResult {
                exit_code: code.unwrap_or(-1),
                stdout: String::from_utf8_lossy(&out).into_owned(),
                stderr: String::from_utf8_lossy(&err).into_owned(),
            })
        })
    }

    pub fn open_terminal(
        &self,
        cols: u16,
        rows: u16,
        command: Option<String>,
        listener: Box<dyn TerminalListener>,
    ) -> Result<Arc<Terminal>, CoreError> {
        let listener: Arc<dyn TerminalListener> = Arc::from(listener);
        rt().block_on(async {
            let ch = self.handle.lock().await.channel_open_session().await?;
            ch.request_pty(true, "xterm-256color", cols as u32, rows as u32, 0, 0, &[])
                .await?;
            match command {
                Some(cmd) => ch.exec(true, cmd).await?,
                None => ch.request_shell(true).await?,
            }
            let (mut reader, writer) = ch.split();
            let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Outgoing>();
            let term = Terminal::live(cols, rows, tx);

            rt().spawn(async move {
                while let Some(msg) = rx.recv().await {
                    let r = match msg {
                        Outgoing::Data(d) => writer.data_bytes(d).await,
                        Outgoing::Resize(c, r) => {
                            writer.window_change(c as u32, r as u32, 0, 0).await
                        }
                        Outgoing::Close => {
                            let _ = writer.eof().await;
                            let _ = writer.close().await;
                            break;
                        }
                    };
                    if r.is_err() {
                        break;
                    }
                }
            });

            let t = term.clone();
            rt().spawn(async move {
                let mut exit: Option<String> = None;
                loop {
                    match reader.wait().await {
                        Some(ChannelMsg::Data { data })
                        | Some(ChannelMsg::ExtendedData { data, .. }) => {
                            t.ingest(&data);
                            listener.on_update();
                        }
                        Some(ChannelMsg::ExitStatus { exit_status }) => {
                            exit = Some(format!("Shell exited (code {exit_status})"));
                        }
                        Some(ChannelMsg::Close) | None => break,
                        Some(_) => {}
                    }
                }
                let msg = exit.or_else(|| Some("Shell connection lost".into()));
                t.mark_closed(msg.clone());
                listener.on_exit(msg);
            });
            Ok(term)
        })
    }

    /// Starts a language server with `command` (see `lsp_launch_command`) and runs the
    /// LSP `initialize` handshake.
    pub fn start_language_server(
        &self,
        command: String,
        root: String,
        settings_json: String,
        init_options_json: String,
        init_timeout_secs: u32,
        wait_for_index: bool,
        listener: Box<dyn LspListener>,
    ) -> Result<Arc<LanguageServer>, CoreError> {
        let listener: Arc<dyn LspListener> = Arc::from(listener);
        let parse = |s: &str| serde_json::from_str(s).unwrap_or(serde_json::Value::Null);
        let start = LspStart {
            root,
            settings: parse(&settings_json),
            init_options: parse(&init_options_json),
            init_timeout: Duration::from_secs(init_timeout_secs.max(5) as u64),
            wait_for_index,
        };
        rt().block_on(async {
            let ch = self.handle.lock().await.channel_open_session().await?;
            ch.exec(true, command).await?;
            let (mut reader, writer) = ch.split();
            let (out_tx, mut out_rx) = tokio::sync::mpsc::unbounded_channel::<Outbound>();
            let (in_tx, in_rx) = tokio::sync::mpsc::unbounded_channel::<Incoming>();
            rt().spawn(async move {
                while let Some(msg) = out_rx.recv().await {
                    match msg {
                        Outbound::Frame(bytes) => {
                            if writer.data_bytes(bytes).await.is_err() {
                                break;
                            }
                        }
                        Outbound::Close => {
                            let _ = writer.eof().await;
                            let _ = writer.close().await;
                            break;
                        }
                    }
                }
            });
            rt().spawn(async move {
                loop {
                    let msg = match reader.wait().await {
                        Some(ChannelMsg::Data { data }) => Incoming::Stdout(data.to_vec()),
                        Some(ChannelMsg::ExtendedData { data, .. }) => {
                            Incoming::Stderr(data.to_vec())
                        }
                        Some(ChannelMsg::ExitStatus { exit_status }) => {
                            Incoming::Exit(Some(exit_status))
                        }
                        Some(ChannelMsg::Close) | None => {
                            let _ = in_tx.send(Incoming::Closed);
                            break;
                        }
                        Some(_) => continue,
                    };
                    if in_tx.send(msg).is_err() {
                        break;
                    }
                }
            });
            LanguageServer::start(
                LspIo {
                    out: out_tx,
                    incoming: in_rx,
                },
                start,
                listener,
            )
            .await
        })
    }

    pub fn disconnect(&self) {
        rt().block_on(async {
            let _ = self.sftp.close().await;
            let _ = self
                .handle
                .lock()
                .await
                .disconnect(Disconnect::ByApplication, "", "en")
                .await;
            if let Some(j) = &self.jump {
                let _ = j.disconnect(Disconnect::ByApplication, "", "en").await;
            }
        })
    }
}
