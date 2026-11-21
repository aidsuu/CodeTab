#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    #[error("{0}")]
    Connect(String),
    #[error("{0}")]
    Auth(String),
    #[error("{0}")]
    HostKeyRejected(String),
    #[error("{0}")]
    Io(String),
    #[error("File terlalu besar untuk editor ({size} byte): {path}")]
    TooLarge { path: String, size: u64 },
    #[error("{0}")]
    Key(String),
    #[error("Koneksi sudah tertutup")]
    Closed,
}

impl From<russh_sftp::client::error::Error> for CoreError {
    fn from(e: russh_sftp::client::error::Error) -> Self {
        CoreError::Io(format!("SFTP: {e}"))
    }
}

impl From<russh::Error> for CoreError {
    fn from(e: russh::Error) -> Self {
        match e {
            russh::Error::Disconnect | russh::Error::SendError | russh::Error::RecvError => {
                CoreError::Closed
            }
            other => CoreError::Io(format!("SSH: {other}")),
        }
    }
}

impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        CoreError::Io(e.to_string())
    }
}
