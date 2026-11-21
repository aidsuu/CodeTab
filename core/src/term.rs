use crate::keyenc::{encode_key, encode_text, Mods, TermKey};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::mpsc::UnboundedSender;

const SCROLLBACK: usize = 5000;
const PREDICT_MIN_RTT_MS: u32 = 40;
pub const COLOR_DEFAULT: i32 = -1;
pub const COLOR_RGB_FLAG: i32 = 0x100_0000;

pub(crate) enum Outgoing {
    Data(Vec<u8>),
    Resize(u16, u16),
    Close,
}

#[derive(Default)]
pub(crate) struct Cb {
    title: String,
}

impl vt100::Callbacks for Cb {
    fn set_window_title(&mut self, _: &mut vt100::Screen, title: &[u8]) {
        self.title = String::from_utf8_lossy(title).into_owned();
    }
}

#[uniffi::export(callback_interface)]
pub trait TerminalListener: Send + Sync {
    fn on_update(&self);
    fn on_exit(&self, message: Option<String>);
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TermRun {
    pub col: u16,
    pub text: String,
    /// -1 is the default color, 0..=255 a palette index, `0x1000000 | rgb` a truecolor value.
    pub fg: i32,
    pub bg: i32,
    pub bold: bool,
    pub dim: bool,
    pub italic: bool,
    pub underline: bool,
    pub inverse: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TermRow {
    pub runs: Vec<TermRun>,
}

/// Locally echoed text that the server has not confirmed yet.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TermPrediction {
    pub row: u16,
    pub col: u16,
    pub text: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TermSnapshot {
    pub cols: u16,
    pub rows: u16,
    pub lines: Vec<TermRow>,
    pub cursor_row: u16,
    pub cursor_col: u16,
    pub cursor_visible: bool,
    pub app_cursor: bool,
    pub bracketed_paste: bool,
    pub alt_screen: bool,
    pub scrollback_available: u32,
    pub scroll_offset: u32,
    pub title: String,
    pub predictions: Vec<TermPrediction>,
    pub mouse_reporting: bool,
    pub mouse_sgr: bool,
}

struct Pred {
    row: u16,
    col: u16,
    ch: char,
    epoch: u64,
    at: Instant,
}

/// Mosh-style local echo. Typed characters are drawn immediately once the server has echoed
/// at least one character in the current epoch, so hidden input such as passwords never shows.
struct Predictor {
    enabled: bool,
    rtt_ms: u32,
    epoch: u64,
    confirmed_epoch: u64,
    preds: Vec<Pred>,
}

impl Default for Predictor {
    fn default() -> Self {
        Predictor {
            enabled: true,
            rtt_ms: 0,
            epoch: 1,
            confirmed_epoch: 0,
            preds: Vec::new(),
        }
    }
}

fn cell_text(screen: &vt100::Screen, row: u16, col: u16) -> String {
    screen
        .cell(row, col)
        .map(|c| c.contents().to_string())
        .unwrap_or_default()
}

impl Predictor {
    fn active(&self) -> bool {
        self.enabled && self.rtt_ms >= PREDICT_MIN_RTT_MS
    }

    fn timeout(&self) -> Duration {
        Duration::from_millis((self.rtt_ms as u64 * 3).max(1500))
    }

    fn reset(&mut self) {
        self.preds.clear();
        self.epoch += 1;
    }

    fn add(&mut self, screen: &vt100::Screen, ch: char) {
        if !self.active() || screen.alternate_screen() || screen.hide_cursor() {
            return;
        }
        let (rows, cols) = screen.size();
        let (row, col) = match self.preds.last() {
            Some(p) => (p.row, p.col + 1),
            None => screen.cursor_position(),
        };
        if row >= rows || col + 1 >= cols || !cell_text(screen, row, col).trim().is_empty() {
            self.reset();
            return;
        }
        self.preds.push(Pred {
            row,
            col,
            ch,
            epoch: self.epoch,
            at: Instant::now(),
        });
    }

    fn validate(&mut self, screen: &vt100::Screen) {
        let (cr, cc) = screen.cursor_position();
        let timeout = self.timeout();
        while let Some(p) = self.preds.first() {
            let text = cell_text(screen, p.row, p.col);
            let moved_past = cr != p.row || cc > p.col;
            let confirmed =
                text == p.ch.to_string() || (p.ch == ' ' && text.trim().is_empty() && moved_past);
            if confirmed {
                self.confirmed_epoch = self.confirmed_epoch.max(p.epoch);
                self.preds.remove(0);
                continue;
            }
            if !text.trim().is_empty() || moved_past || p.at.elapsed() > timeout {
                self.reset();
            }
            break;
        }
    }

    fn visible(&self) -> Vec<TermPrediction> {
        let mut out: Vec<TermPrediction> = Vec::new();
        if !self.active() {
            return out;
        }
        for p in self
            .preds
            .iter()
            .filter(|p| p.epoch <= self.confirmed_epoch)
        {
            match out.last_mut() {
                Some(last)
                    if last.row == p.row
                        && last.col + last.text.chars().count() as u16 == p.col =>
                {
                    last.text.push(p.ch)
                }
                _ => out.push(TermPrediction {
                    row: p.row,
                    col: p.col,
                    text: p.ch.to_string(),
                }),
            }
        }
        out
    }
}

#[derive(uniffi::Object)]
pub struct Terminal {
    parser: Mutex<vt100::Parser<Cb>>,
    tx: Option<UnboundedSender<Outgoing>>,
    open: AtomicBool,
    exit_message: Mutex<Option<String>>,
    predict: Mutex<Predictor>,
}

fn color(c: vt100::Color) -> i32 {
    match c {
        vt100::Color::Default => COLOR_DEFAULT,
        vt100::Color::Idx(i) => i as i32,
        vt100::Color::Rgb(r, g, b) => {
            COLOR_RGB_FLAG | (r as i32) << 16 | (g as i32) << 8 | b as i32
        }
    }
}

impl Terminal {
    pub(crate) fn live(cols: u16, rows: u16, tx: UnboundedSender<Outgoing>) -> Arc<Self> {
        Arc::new(Self::build(cols, rows, Some(tx)))
    }

    fn build(cols: u16, rows: u16, tx: Option<UnboundedSender<Outgoing>>) -> Self {
        let open = tx.is_some();
        Terminal {
            parser: Mutex::new(vt100::Parser::new_with_callbacks(
                rows.max(2),
                cols.max(2),
                SCROLLBACK,
                Cb::default(),
            )),
            tx,
            open: AtomicBool::new(open),
            exit_message: Mutex::new(None),
            predict: Mutex::new(Predictor::default()),
        }
    }

    pub(crate) fn ingest(&self, data: &[u8]) {
        let reply = {
            let mut p = self.parser.lock().unwrap();
            p.process(data);
            let mut reply = Vec::new();
            if contains(data, b"\x1b[6n") {
                let (r, c) = p.screen().cursor_position();
                reply.extend(format!("\x1b[{};{}R", r + 1, c + 1).into_bytes());
            }
            if contains(data, b"\x1b[5n") {
                reply.extend_from_slice(b"\x1b[0n");
            }
            if contains(data, b"\x1b[c") || contains(data, b"\x1b[0c") {
                reply.extend_from_slice(b"\x1b[?1;2c");
            }
            self.predict.lock().unwrap().validate(p.screen());
            reply
        };
        if !reply.is_empty() {
            self.send(reply);
        }
    }

    pub(crate) fn mark_closed(&self, message: Option<String>) {
        self.open.store(false, Ordering::SeqCst);
        *self.exit_message.lock().unwrap() = message;
    }
}

fn contains(hay: &[u8], needle: &[u8]) -> bool {
    hay.windows(needle.len()).any(|w| w == needle)
}

#[uniffi::export]
impl Terminal {
    #[uniffi::constructor]
    pub fn new(cols: u16, rows: u16) -> Arc<Self> {
        Arc::new(Self::build(cols, rows, None))
    }

    pub fn feed(&self, data: Vec<u8>) {
        self.ingest(&data);
    }

    pub fn send(&self, data: Vec<u8>) -> bool {
        match &self.tx {
            Some(tx) if self.is_open() => tx.send(Outgoing::Data(data)).is_ok(),
            _ => false,
        }
    }

    /// Sends typed text and predicts its echo when the connection is slow.
    pub fn type_text(&self, text: String, mods: Mods) -> bool {
        {
            let p = self.parser.lock().unwrap();
            let mut pr = self.predict.lock().unwrap();
            for ch in text.chars() {
                if !mods.ctrl && !mods.alt && (' '..='~').contains(&ch) {
                    pr.add(p.screen(), ch);
                } else {
                    pr.reset();
                }
            }
        }
        self.send(encode_text(text, mods))
    }

    pub fn type_key(&self, key: TermKey, mods: Mods) -> bool {
        let app_cursor = {
            let p = self.parser.lock().unwrap();
            let mut pr = self.predict.lock().unwrap();
            if key == TermKey::Backspace && !mods.ctrl && !mods.alt && !pr.preds.is_empty() {
                pr.preds.pop();
            } else {
                pr.reset();
            }
            p.screen().application_cursor()
        };
        self.send(encode_key(key, mods, app_cursor))
    }

    pub fn set_latency(&self, rtt_ms: u32) {
        self.predict.lock().unwrap().rtt_ms = rtt_ms;
    }

    pub fn set_prediction(&self, enabled: bool) {
        let mut pr = self.predict.lock().unwrap();
        pr.enabled = enabled;
        if !enabled {
            pr.reset();
        }
    }

    pub fn clear_predictions(&self) {
        self.predict.lock().unwrap().reset();
    }

    pub fn resize(&self, cols: u16, rows: u16) {
        let (cols, rows) = (cols.max(2), rows.max(2));
        {
            let mut p = self.parser.lock().unwrap();
            if p.screen().size() == (rows, cols) {
                return;
            }
            p.screen_mut().set_size(rows, cols);
            self.predict.lock().unwrap().reset();
        }
        if let Some(tx) = &self.tx {
            let _ = tx.send(Outgoing::Resize(cols, rows));
        }
    }

    pub fn size(&self) -> Vec<u16> {
        let (r, c) = self.parser.lock().unwrap().screen().size();
        vec![c, r]
    }

    pub fn snapshot(&self, scroll_offset: u32) -> TermSnapshot {
        let mut p = self.parser.lock().unwrap();
        let title = p.callbacks().title.clone();
        let screen = p.screen_mut();
        screen.set_scrollback(usize::MAX);
        let available = screen.scrollback() as u32;
        let offset = if screen.alternate_screen() {
            0
        } else {
            scroll_offset.min(available)
        };
        screen.set_scrollback(offset as usize);

        let screen = p.screen();
        let (rows, cols) = screen.size();
        let mut lines = Vec::with_capacity(rows as usize);
        for r in 0..rows {
            let mut runs: Vec<TermRun> = Vec::new();
            let mut c = 0u16;
            while c < cols {
                let Some(cell) = screen.cell(r, c) else { break };
                if cell.is_wide_continuation() {
                    c += 1;
                    continue;
                }
                let text = if cell.has_contents() {
                    cell.contents()
                } else {
                    " "
                };
                let attrs = (
                    color(cell.fgcolor()),
                    color(cell.bgcolor()),
                    cell.bold(),
                    cell.dim(),
                    cell.italic(),
                    cell.underline(),
                    cell.inverse(),
                );
                let wide = cell.is_wide();
                let extend = runs.last().is_some_and(|l| {
                    !wide
                        && (l.fg, l.bg, l.bold, l.dim, l.italic, l.underline, l.inverse) == attrs
                        && l.col + l.text.chars().count() as u16 == c
                });
                if extend {
                    runs.last_mut().unwrap().text.push_str(text);
                } else {
                    runs.push(TermRun {
                        col: c,
                        text: text.to_string(),
                        fg: attrs.0,
                        bg: attrs.1,
                        bold: attrs.2,
                        dim: attrs.3,
                        italic: attrs.4,
                        underline: attrs.5,
                        inverse: attrs.6,
                    });
                }
                c += if wide { 2 } else { 1 };
            }
            while let Some(last) = runs.last_mut() {
                let plain = last.bg == COLOR_DEFAULT && !last.inverse && !last.underline;
                if !plain {
                    break;
                }
                let trimmed = last.text.trim_end_matches(' ').len();
                if trimmed == 0 {
                    runs.pop();
                } else {
                    last.text.truncate(trimmed);
                    break;
                }
            }
            lines.push(TermRow { runs });
        }
        let (mut cr, mut cc) = screen.cursor_position();
        let predictions = if offset == 0 && !screen.alternate_screen() {
            let mut pr = self.predict.lock().unwrap();
            pr.validate(screen);
            pr.visible()
        } else {
            Vec::new()
        };
        if let Some(last) = predictions.last() {
            cr = last.row;
            cc = last.col + last.text.chars().count() as u16;
        }
        let snap = TermSnapshot {
            cols,
            rows,
            lines,
            cursor_row: cr,
            cursor_col: cc,
            cursor_visible: !screen.hide_cursor() && offset == 0,
            app_cursor: screen.application_cursor(),
            bracketed_paste: screen.bracketed_paste(),
            alt_screen: screen.alternate_screen(),
            scrollback_available: available,
            scroll_offset: offset,
            title,
            predictions,
            mouse_reporting: screen.mouse_protocol_mode() != vt100::MouseProtocolMode::None,
            mouse_sgr: screen.mouse_protocol_encoding() == vt100::MouseProtocolEncoding::Sgr,
        };
        p.screen_mut().set_scrollback(0);
        snap
    }

    pub fn screen_text(&self) -> String {
        self.parser.lock().unwrap().screen().contents()
    }

    pub fn is_open(&self) -> bool {
        self.open.load(Ordering::SeqCst)
    }

    pub fn exit_message(&self) -> Option<String> {
        self.exit_message.lock().unwrap().clone()
    }

    pub fn terminate(&self) {
        if let Some(tx) = &self.tx {
            let _ = tx.send(Outgoing::Close);
        }
        self.open.store(false, Ordering::SeqCst);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text_of(row: &TermRow) -> String {
        let mut s = String::new();
        for r in &row.runs {
            while s.chars().count() < r.col as usize {
                s.push(' ');
            }
            s.push_str(&r.text);
        }
        s
    }

    #[test]
    fn prints_lines_and_tracks_cursor() {
        let t = Terminal::new(20, 5);
        t.feed(b"hello\r\nworld".to_vec());
        let s = t.snapshot(0);
        assert_eq!(text_of(&s.lines[0]), "hello");
        assert_eq!(text_of(&s.lines[1]), "world");
        assert_eq!((s.cursor_row, s.cursor_col), (1, 5));
        assert!(s.cursor_visible);
        assert!(s.lines[2].runs.is_empty());
    }

    #[test]
    fn colors_become_separate_runs() {
        let t = Terminal::new(30, 3);
        t.feed(b"\x1b[1;32mOK\x1b[0m done \x1b[38;2;255;0;10mrgb\x1b[48;5;196m!".to_vec());
        let row = &t.snapshot(0).lines[0];
        assert_eq!(row.runs[0].text, "OK");
        assert_eq!(row.runs[0].fg, 2);
        assert!(row.runs[0].bold);
        assert_eq!(row.runs[1].text, " done ");
        assert_eq!(row.runs[1].fg, COLOR_DEFAULT);
        assert_eq!(row.runs[2].fg, COLOR_RGB_FLAG | 0xFF000A);
        assert_eq!(row.runs[3].bg, 196);
    }

    #[test]
    fn scrollback_and_alt_screen() {
        let t = Terminal::new(10, 3);
        for i in 0..10 {
            t.feed(format!("line{i}\r\n").into_bytes());
        }
        let live = t.snapshot(0);
        assert_eq!(text_of(&live.lines[0]), "line8");
        assert!(live.scrollback_available >= 7);

        let back = t.snapshot(3);
        assert_eq!(back.scroll_offset, 3);
        assert_eq!(text_of(&back.lines[0]), "line5");
        assert!(!back.cursor_visible);
        assert_eq!(text_of(&t.snapshot(0).lines[0]), "line8");

        t.feed(b"\x1b[?1049h\x1b[HVIM".to_vec());
        let alt = t.snapshot(5);
        assert!(alt.alt_screen);
        assert_eq!(alt.scroll_offset, 0);
        assert_eq!(text_of(&alt.lines[0]), "VIM");
        t.feed(b"\x1b[?1049l".to_vec());
        assert!(!t.snapshot(0).alt_screen);
        assert_eq!(text_of(&t.snapshot(0).lines[0]), "line8");
    }

    #[test]
    fn modes_title_and_wide_chars() {
        let t = Terminal::new(20, 3);
        t.feed(b"\x1b]0;user@dev: ~\x07\x1b[?1h\x1b[?2004h\x1b[?25l".to_vec());
        let s = t.snapshot(0);
        assert_eq!(s.title, "user@dev: ~");
        assert!(s.app_cursor && s.bracketed_paste && !s.cursor_visible);

        t.feed("\x1b[2J\x1b[H漢x".as_bytes().to_vec());
        let row = &t.snapshot(0).lines[0];
        assert_eq!(row.runs[0].text, "漢");
        assert_eq!(row.runs[1].col, 2);
        assert_eq!(row.runs[1].text, "x");
    }

    #[test]
    fn resize_and_cursor_report() {
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let t = Terminal::live(40, 10, tx);
        t.ingest(b"ab\x1b[6n");
        match rx.try_recv().unwrap() {
            Outgoing::Data(d) => assert_eq!(d, b"\x1b[1;3R"),
            _ => panic!("expected cursor report"),
        }
        t.resize(80, 24);
        assert!(matches!(rx.try_recv().unwrap(), Outgoing::Resize(80, 24)));
        assert_eq!(t.size(), vec![80, 24]);
        t.resize(80, 24);
        assert!(rx.try_recv().is_err());

        assert!(t.send(b"ls\r".to_vec()));
        assert!(matches!(rx.try_recv().unwrap(), Outgoing::Data(d) if d == b"ls\r"));
        t.terminate();
        assert!(!t.is_open());
        assert!(!t.send(b"x".to_vec()));
    }

    const NONE: Mods = Mods {
        ctrl: false,
        alt: false,
        shift: false,
    };

    fn predicted(t: &Terminal) -> Vec<(u16, u16, String)> {
        t.snapshot(0)
            .predictions
            .into_iter()
            .map(|p| (p.row, p.col, p.text))
            .collect()
    }

    #[test]
    fn prediction_waits_for_a_confirmed_echo() {
        let t = Terminal::new(40, 5);
        t.feed(b"$ ".to_vec());
        t.set_latency(150);
        t.type_text("ls".into(), NONE);
        assert!(predicted(&t).is_empty());
        t.feed(b"l".to_vec());
        assert_eq!(predicted(&t), vec![(0, 3, "s".to_string())]);
        assert_eq!(t.snapshot(0).cursor_col, 4);
        t.type_text(" -la".into(), NONE);
        assert_eq!(predicted(&t), vec![(0, 3, "s -la".to_string())]);
        t.feed(b"s -la".to_vec());
        assert!(predicted(&t).is_empty());
        assert_eq!(t.snapshot(0).cursor_col, 8);
    }

    #[test]
    fn hidden_input_after_enter_is_never_shown() {
        let t = Terminal::new(40, 5);
        t.set_latency(150);
        t.feed(b"$ ".to_vec());
        t.type_text("s".into(), NONE);
        t.feed(b"s".to_vec());
        t.type_key(TermKey::Enter, NONE);
        t.feed(b"\r\nPassword: ".to_vec());
        t.type_text("hunter2".into(), NONE);
        assert!(predicted(&t).is_empty());
    }

    #[test]
    fn mismatch_backspace_and_low_latency() {
        let t = Terminal::new(40, 5);
        t.set_latency(150);
        t.feed(b"$ ".to_vec());
        t.type_text("a".into(), NONE);
        t.feed(b"a".to_vec());
        t.type_text("bc".into(), NONE);
        t.type_key(TermKey::Backspace, NONE);
        assert_eq!(predicted(&t), vec![(0, 3, "b".to_string())]);
        t.feed(b"X".to_vec());
        assert!(predicted(&t).is_empty());
        t.type_text("d".into(), NONE);
        assert!(predicted(&t).is_empty());

        let fast = Terminal::new(40, 5);
        fast.set_latency(5);
        fast.type_text("a".into(), NONE);
        fast.feed(b"a".to_vec());
        fast.type_text("b".into(), NONE);
        assert!(predicted(&fast).is_empty());
    }

    #[test]
    fn no_prediction_in_full_screen_apps_and_mouse_modes() {
        let t = Terminal::new(40, 5);
        t.set_latency(150);
        t.feed(b"\x1b[?1049h\x1b[?1000h\x1b[?1006h".to_vec());
        t.type_text("j".into(), NONE);
        let s = t.snapshot(0);
        assert!(s.predictions.is_empty());
        assert!(s.mouse_reporting && s.mouse_sgr);
    }

    #[test]
    fn offline_terminal_cannot_send() {
        let t = Terminal::new(10, 3);
        assert!(!t.is_open());
        assert!(!t.send(b"x".to_vec()));
    }
}
