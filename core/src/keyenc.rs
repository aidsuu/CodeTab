#[derive(Debug, Clone, Copy, PartialEq, uniffi::Enum)]
pub enum TermKey {
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    Insert,
    Delete,
    Escape,
    Tab,
    Enter,
    Backspace,
    F { n: u8 },
}

#[derive(Debug, Clone, Copy, Default, PartialEq, uniffi::Record)]
pub struct Mods {
    pub ctrl: bool,
    pub alt: bool,
    pub shift: bool,
}

fn modifier_param(m: Mods) -> u8 {
    1 + m.shift as u8 + 2 * m.alt as u8 + 4 * m.ctrl as u8
}

#[uniffi::export]
pub fn encode_key(key: TermKey, mods: Mods, app_cursor: bool) -> Vec<u8> {
    let modp = modifier_param(mods);
    let has_mod = modp > 1;
    let cursor = |c: char| -> Vec<u8> {
        if has_mod {
            format!("\x1b[1;{modp}{c}").into_bytes()
        } else if app_cursor {
            format!("\x1bO{c}").into_bytes()
        } else {
            format!("\x1b[{c}").into_bytes()
        }
    };
    let tilde = |n: u8| -> Vec<u8> {
        if has_mod {
            format!("\x1b[{n};{modp}~")
        } else {
            format!("\x1b[{n}~")
        }
        .into_bytes()
    };
    let alt = |b: &[u8]| -> Vec<u8> {
        if mods.alt {
            [b"\x1b".as_slice(), b].concat()
        } else {
            b.to_vec()
        }
    };
    match key {
        TermKey::Up => cursor('A'),
        TermKey::Down => cursor('B'),
        TermKey::Right => cursor('C'),
        TermKey::Left => cursor('D'),
        TermKey::Home => cursor('H'),
        TermKey::End => cursor('F'),
        TermKey::Insert => tilde(2),
        TermKey::Delete => tilde(3),
        TermKey::PageUp => tilde(5),
        TermKey::PageDown => tilde(6),
        TermKey::Escape => alt(b"\x1b"),
        TermKey::Tab => {
            if mods.shift {
                b"\x1b[Z".to_vec()
            } else {
                alt(b"\t")
            }
        }
        TermKey::Enter => alt(b"\r"),
        TermKey::Backspace => {
            if mods.ctrl {
                alt(b"\x08")
            } else {
                alt(b"\x7f")
            }
        }
        TermKey::F { n } => match n {
            1..=4 => {
                let c = (b'P' + n - 1) as char;
                if has_mod {
                    format!("\x1b[1;{modp}{c}").into_bytes()
                } else {
                    format!("\x1bO{c}").into_bytes()
                }
            }
            5 => tilde(15),
            6 => tilde(17),
            7 => tilde(18),
            8 => tilde(19),
            9 => tilde(20),
            10 => tilde(21),
            11 => tilde(23),
            12 => tilde(24),
            _ => Vec::new(),
        },
    }
}

#[uniffi::export]
pub fn encode_text(text: String, mods: Mods) -> Vec<u8> {
    let mut out = Vec::with_capacity(text.len());
    for ch in text.chars() {
        let ch = if ch == '\n' { '\r' } else { ch };
        if mods.alt {
            out.push(0x1b);
        }
        if mods.ctrl {
            if let Some(b) = ctrl_byte(ch) {
                out.push(b);
                continue;
            }
        }
        let mut buf = [0u8; 4];
        out.extend_from_slice(ch.encode_utf8(&mut buf).as_bytes());
    }
    out
}

/// Mouse wheel report for programs that enabled mouse tracking, such as tmux with `mouse on`.
#[uniffi::export]
pub fn encode_wheel(up: bool, row: u16, col: u16, sgr: bool) -> Vec<u8> {
    let button = if up { 64 } else { 65 };
    if sgr {
        format!("\x1b[<{button};{};{}M", col + 1, row + 1).into_bytes()
    } else {
        let pos = |v: u16| (32 + 1 + v.min(222)) as u8;
        vec![0x1b, b'[', b'M', 32 + button, pos(col), pos(row)]
    }
}

fn ctrl_byte(ch: char) -> Option<u8> {
    Some(match ch {
        'a'..='z' => ch as u8 - b'a' + 1,
        'A'..='Z' => ch as u8 - b'A' + 1,
        '@' | ' ' | '2' => 0,
        '[' | '3' => 0x1b,
        '\\' | '4' => 0x1c,
        ']' | '5' => 0x1d,
        '^' | '6' => 0x1e,
        '_' | '/' | '7' => 0x1f,
        '?' | '8' => 0x7f,
        _ => return None,
    })
}

#[uniffi::export]
pub fn encode_paste(text: String, bracketed: bool) -> Vec<u8> {
    let clean = text
        .replace("\r\n", "\r")
        .replace('\n', "\r")
        .replace("\x1b[201~", "");
    if bracketed {
        format!("\x1b[200~{clean}\x1b[201~").into_bytes()
    } else {
        clean.into_bytes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wheel_reports() {
        assert_eq!(encode_wheel(true, 4, 9, true), b"\x1b[<64;10;5M");
        assert_eq!(encode_wheel(false, 0, 0, true), b"\x1b[<65;1;1M");
        assert_eq!(
            encode_wheel(true, 0, 0, false),
            vec![0x1b, b'[', b'M', 96, 33, 33]
        );
    }

    const NONE: Mods = Mods {
        ctrl: false,
        alt: false,
        shift: false,
    };
    const CTRL: Mods = Mods {
        ctrl: true,
        alt: false,
        shift: false,
    };
    const ALT: Mods = Mods {
        ctrl: false,
        alt: true,
        shift: false,
    };

    #[test]
    fn arrows_follow_cursor_mode() {
        assert_eq!(encode_key(TermKey::Up, NONE, false), b"\x1b[A");
        assert_eq!(encode_key(TermKey::Up, NONE, true), b"\x1bOA");
        assert_eq!(encode_key(TermKey::Left, CTRL, true), b"\x1b[1;5D");
        assert_eq!(
            encode_key(
                TermKey::Right,
                Mods {
                    shift: true,
                    ..NONE
                },
                false
            ),
            b"\x1b[1;2C"
        );
    }

    #[test]
    fn editing_and_function_keys() {
        assert_eq!(encode_key(TermKey::Delete, NONE, false), b"\x1b[3~");
        assert_eq!(encode_key(TermKey::PageUp, CTRL, false), b"\x1b[5;5~");
        assert_eq!(encode_key(TermKey::F { n: 1 }, NONE, false), b"\x1bOP");
        assert_eq!(encode_key(TermKey::F { n: 5 }, NONE, false), b"\x1b[15~");
        assert_eq!(encode_key(TermKey::F { n: 12 }, NONE, false), b"\x1b[24~");
        assert_eq!(
            encode_key(
                TermKey::Tab,
                Mods {
                    shift: true,
                    ..NONE
                },
                false
            ),
            b"\x1b[Z"
        );
        assert_eq!(encode_key(TermKey::Backspace, NONE, false), b"\x7f");
        assert_eq!(encode_key(TermKey::Enter, ALT, false), b"\x1b\r");
        assert_eq!(encode_key(TermKey::Escape, NONE, false), b"\x1b");
    }

    #[test]
    fn ctrl_and_alt_text() {
        assert_eq!(encode_text("c".into(), CTRL), vec![3]);
        assert_eq!(encode_text("D".into(), CTRL), vec![4]);
        assert_eq!(encode_text("[".into(), CTRL), vec![0x1b]);
        assert_eq!(encode_text("b".into(), ALT), b"\x1bb");
        assert_eq!(encode_text("ls -la\n".into(), NONE), b"ls -la\r");
        assert_eq!(encode_text("é".into(), NONE), "é".as_bytes());
        assert_eq!(encode_text("1".into(), CTRL), b"1");
    }

    #[test]
    fn paste_is_bracketed_and_sanitized() {
        assert_eq!(encode_paste("a\nb".into(), false), b"a\rb");
        assert_eq!(
            encode_paste("x\r\ny".into(), true),
            b"\x1b[200~x\ry\x1b[201~"
        );
        assert_eq!(
            encode_paste("evil\x1b[201~rm".into(), true),
            b"\x1b[200~evilrm\x1b[201~"
        );
    }
}
