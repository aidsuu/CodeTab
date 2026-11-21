use crate::error::CoreError;
use russh::keys::ssh_key::private::{Ed25519Keypair, KeypairData};
use russh::keys::ssh_key::{HashAlg, LineEnding, PrivateKey};

#[derive(Debug, Clone, uniffi::Record)]
pub struct GeneratedKey {
    pub private_pem: String,
    pub public_line: String,
    pub fingerprint: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct KeyInfo {
    pub format: String,
    pub encrypted: bool,
    pub algorithm: Option<String>,
    pub public_line: Option<String>,
    pub fingerprint: Option<String>,
}

#[uniffi::export]
pub fn generate_ed25519(comment: String) -> Result<GeneratedKey, CoreError> {
    let mut seed = [0u8; 32];
    getrandom::fill(&mut seed).map_err(|e| CoreError::Key(format!("RNG gagal: {e}")))?;
    let kp = Ed25519Keypair::from_seed(&seed);
    seed.fill(0);
    let key = PrivateKey::new(KeypairData::from(kp), comment).map_err(key_err)?;
    let pem = key.to_openssh(LineEnding::LF).map_err(key_err)?;
    Ok(GeneratedKey {
        private_pem: pem.to_string(),
        public_line: key.public_key().to_openssh().map_err(key_err)?,
        fingerprint: key.fingerprint(HashAlg::Sha256).to_string(),
    })
}

#[uniffi::export]
pub fn inspect_private_key(text: String) -> Option<KeyInfo> {
    let t = text.trim();
    if t.starts_with("-----BEGIN OPENSSH PRIVATE KEY-----") {
        let key = PrivateKey::from_openssh(t).ok()?;
        let public = key.public_key();
        Some(KeyInfo {
            format: "OpenSSH".into(),
            encrypted: key.is_encrypted(),
            algorithm: Some(key.algorithm().to_string()),
            public_line: public.to_openssh().ok(),
            fingerprint: Some(public.fingerprint(HashAlg::Sha256).to_string()),
        })
    } else if t.starts_with("-----BEGIN ") && t.contains("PRIVATE KEY-----") {
        let encrypted = t.contains("ENCRYPTED");
        let decoded = if encrypted {
            None
        } else {
            russh::keys::decode_secret_key(t, None).ok()
        };
        if !encrypted && decoded.is_none() {
            return None;
        }
        Some(KeyInfo {
            format: "PEM".into(),
            encrypted,
            algorithm: decoded.as_ref().map(|k| k.algorithm().to_string()),
            public_line: decoded
                .as_ref()
                .and_then(|k| k.public_key().to_openssh().ok()),
            fingerprint: decoded
                .as_ref()
                .map(|k| k.fingerprint(HashAlg::Sha256).to_string()),
        })
    } else if t.starts_with("PuTTY-User-Key-File-") {
        Some(KeyInfo {
            format: "PuTTY".into(),
            encrypted: !t.lines().any(|l| l.trim() == "Encryption: none"),
            algorithm: t
                .lines()
                .next()
                .and_then(|l| l.split(": ").nth(1))
                .map(str::to_string),
            public_line: None,
            fingerprint: None,
        })
    } else {
        None
    }
}

#[uniffi::export]
pub fn unlock_private_key(text: String, passphrase: Option<String>) -> Result<KeyInfo, CoreError> {
    let key = decode(&text, passphrase.as_deref())?;
    Ok(KeyInfo {
        format: inspect_private_key(text)
            .map(|i| i.format)
            .unwrap_or_else(|| "OpenSSH".into()),
        encrypted: passphrase.is_some(),
        algorithm: Some(key.algorithm().to_string()),
        public_line: key.public_key().to_openssh().ok(),
        fingerprint: Some(key.fingerprint(HashAlg::Sha256).to_string()),
    })
}

pub(crate) fn decode(text: &str, passphrase: Option<&str>) -> Result<PrivateKey, CoreError> {
    russh::keys::decode_secret_key(text.trim(), passphrase).map_err(|e| {
        if passphrase.is_none()
            && inspect_private_key(text.to_string()).is_some_and(|i| i.encrypted)
        {
            CoreError::Key("Kunci ini memakai passphrase".into())
        } else if passphrase.is_some() {
            CoreError::Key(format!("Passphrase salah atau kunci rusak ({e})"))
        } else {
            CoreError::Key(format!("Kunci tidak bisa dibaca: {e}"))
        }
    })
}

fn key_err(e: impl std::fmt::Display) -> CoreError {
    CoreError::Key(e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_key_round_trips() {
        let g = generate_ed25519("user@tablet".into()).unwrap();
        assert!(g
            .private_pem
            .starts_with("-----BEGIN OPENSSH PRIVATE KEY-----"));
        assert!(g.public_line.starts_with("ssh-ed25519 AAAA"));
        assert!(g.public_line.ends_with(" user@tablet"));
        assert!(g.fingerprint.starts_with("SHA256:"));

        let info = inspect_private_key(g.private_pem.clone()).unwrap();
        assert_eq!(info.format, "OpenSSH");
        assert!(!info.encrypted);
        assert_eq!(info.algorithm.as_deref(), Some("ssh-ed25519"));
        assert_eq!(info.fingerprint.as_deref(), Some(g.fingerprint.as_str()));

        let k = decode(&g.private_pem, None).unwrap();
        assert_eq!(k.fingerprint(HashAlg::Sha256).to_string(), g.fingerprint);
    }

    #[test]
    fn keys_are_unique() {
        let a = generate_ed25519("a".into()).unwrap();
        let b = generate_ed25519("b".into()).unwrap();
        assert_ne!(a.fingerprint, b.fingerprint);
    }

    #[test]
    fn encrypted_key_needs_correct_passphrase() {
        let g = generate_ed25519("enc".into()).unwrap();
        let key = PrivateKey::from_openssh(&g.private_pem).unwrap();
        let mut rng = TestRng(7);
        let enc = key.encrypt(&mut rng, "rahasia").unwrap();
        let pem = enc.to_openssh(LineEnding::LF).unwrap().to_string();

        let info = inspect_private_key(pem.clone()).unwrap();
        assert!(info.encrypted);
        assert_eq!(info.fingerprint.as_deref(), Some(g.fingerprint.as_str()));

        assert!(
            matches!(unlock_private_key(pem.clone(), None), Err(CoreError::Key(m)) if m.contains("passphrase"))
        );
        assert!(unlock_private_key(pem.clone(), Some("salah".into())).is_err());
        assert_eq!(
            unlock_private_key(pem, Some("rahasia".into()))
                .unwrap()
                .fingerprint,
            Some(g.fingerprint)
        );
    }

    #[test]
    fn rejects_garbage() {
        assert_eq!(inspect_private_key("hello".into()), None);
        assert_eq!(
            inspect_private_key("ssh-ed25519 AAAAC3Nza... user".into()),
            None
        );
        assert!(unlock_private_key("nope".into(), None).is_err());
    }

    struct TestRng(u64);
    impl russh::keys::ssh_key::rand_core::TryRng for TestRng {
        type Error = core::convert::Infallible;
        fn try_next_u32(&mut self) -> Result<u32, Self::Error> {
            Ok(self.try_next_u64()? as u32)
        }
        fn try_next_u64(&mut self) -> Result<u64, Self::Error> {
            self.0 = self
                .0
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            Ok(self.0)
        }
        fn try_fill_bytes(&mut self, dst: &mut [u8]) -> Result<(), Self::Error> {
            for b in dst {
                *b = (self.try_next_u64()? >> 33) as u8;
            }
            Ok(())
        }
    }
    impl russh::keys::ssh_key::rand_core::TryCryptoRng for TestRng {}
}
