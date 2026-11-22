use crate::error::CoreError;
use crate::runtime::rt;
use serde_json::{json, Value};
use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, oneshot};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct LspPosition {
    pub line: u32,
    pub character: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct LspRange {
    pub start: LspPosition,
    pub end: LspPosition,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LspTextEdit {
    pub range: LspRange,
    pub new_text: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LspChange {
    pub range: Option<LspRange>,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LspDiagnostic {
    pub range: LspRange,
    /// 1 error, 2 warning, 3 information, 4 hint.
    pub severity: u8,
    pub message: String,
    pub source: Option<String>,
    pub code: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LspCompletionItem {
    pub label: String,
    pub kind: u8,
    pub detail: Option<String>,
    pub documentation: Option<String>,
    pub sort_text: String,
    pub filter_text: String,
    pub insert_text: String,
    pub snippet: bool,
    pub edit_range: Option<LspRange>,
    pub additional_edits: Vec<LspTextEdit>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LspCompletion {
    pub incomplete: bool,
    pub items: Vec<LspCompletionItem>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LspLocation {
    pub path: String,
    pub range: LspRange,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LspSignature {
    pub label: String,
    pub documentation: Option<String>,
    pub active_start: Option<u32>,
    pub active_end: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LspServerInfo {
    pub name: String,
    pub version: Option<String>,
    pub trigger_characters: Vec<String>,
    pub signature_triggers: Vec<String>,
    pub incremental_sync: bool,
    pub completion: bool,
    pub hover: bool,
    pub definition: bool,
    pub signature_help: bool,
}

#[uniffi::export(callback_interface)]
pub trait LspListener: Send + Sync {
    fn on_diagnostics(&self, path: String, diagnostics: Vec<LspDiagnostic>);
    /// Current background work such as indexing, or `None` once it is finished.
    fn on_progress(&self, message: Option<String>);
    fn on_message(&self, severity: u8, message: String);
    fn on_exit(&self, message: String);
}

/// Splits a byte stream into LSP messages. Bytes before a header (for example
/// output from shell startup files) are skipped.
#[derive(Default)]
pub(crate) struct FrameReader {
    buf: Vec<u8>,
}

impl FrameReader {
    pub(crate) fn push(&mut self, data: &[u8]) {
        self.buf.extend_from_slice(data);
    }

    pub(crate) fn next_frame(&mut self) -> Option<Vec<u8>> {
        loop {
            let start = find_ci(&self.buf, b"content-length:")?;
            let header_end = find(&self.buf[start..], b"\r\n\r\n").map(|i| start + i)?;
            let header = String::from_utf8_lossy(&self.buf[start..header_end]).to_string();
            let len = header
                .lines()
                .find_map(|l| {
                    let (k, v) = l.split_once(':')?;
                    k.trim()
                        .eq_ignore_ascii_case("content-length")
                        .then(|| v.trim().parse::<usize>().ok())?
                })
                .unwrap_or(usize::MAX);
            if len == usize::MAX {
                self.buf.drain(..start + "content-length:".len());
                continue;
            }
            let body = header_end + 4;
            if self.buf.len() < body + len {
                return None;
            }
            let frame = self.buf[body..body + len].to_vec();
            self.buf.drain(..body + len);
            return Some(frame);
        }
    }
}

fn find(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len()).position(|w| w == needle)
}

fn find_ci(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len())
        .position(|w| w.eq_ignore_ascii_case(needle))
}

pub(crate) fn encode_frame(msg: &Value) -> Vec<u8> {
    let body = msg.to_string();
    let mut out = format!("Content-Length: {}\r\n\r\n", body.len()).into_bytes();
    out.extend_from_slice(body.as_bytes());
    out
}

const URI_SAFE: &str = "-._~/!$&'()*+,;=:@";

pub fn path_to_uri(path: &str) -> String {
    let mut out = String::from("file://");
    for b in path.bytes() {
        if b.is_ascii_alphanumeric() || URI_SAFE.as_bytes().contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

pub fn uri_to_path(uri: &str) -> Option<String> {
    let rest = uri.strip_prefix("file://")?;
    let rest = match rest.find('/') {
        Some(i) => &rest[i..],
        None => return None,
    };
    let bytes = rest.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(v) = u8::from_str_radix(&rest[i + 1..i + 3], 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8(out).ok()
}

fn position(v: &Value) -> Option<LspPosition> {
    Some(LspPosition {
        line: v.get("line")?.as_u64()? as u32,
        character: v.get("character")?.as_u64()? as u32,
    })
}

fn range(v: &Value) -> Option<LspRange> {
    Some(LspRange {
        start: position(v.get("start")?)?,
        end: position(v.get("end")?)?,
    })
}

fn range_json(r: &LspRange) -> Value {
    json!({
        "start": {"line": r.start.line, "character": r.start.character},
        "end": {"line": r.end.line, "character": r.end.character},
    })
}

/// Flattens `MarkupContent`, `MarkedString`, or a list of them into Markdown.
fn markup(v: &Value) -> Option<String> {
    let text = match v {
        Value::String(s) => s.clone(),
        Value::Array(items) => items
            .iter()
            .filter_map(markup)
            .collect::<Vec<_>>()
            .join("\n\n"),
        Value::Object(o) => {
            let value = o.get("value")?.as_str()?.to_string();
            match o.get("language").and_then(Value::as_str) {
                Some(lang) => format!("```{lang}\n{value}\n```"),
                None => value,
            }
        }
        _ => return None,
    };
    let text = text.trim().to_string();
    (!text.is_empty()).then_some(text)
}

fn text_edit(v: &Value) -> Option<LspTextEdit> {
    let r = v.get("range").or_else(|| v.get("insert")).and_then(range)?;
    Some(LspTextEdit {
        range: r,
        new_text: v.get("newText")?.as_str()?.to_string(),
    })
}

pub(crate) fn parse_completion(result: &Value) -> LspCompletion {
    let (items, incomplete, defaults) = match result {
        Value::Array(a) => (a.as_slice(), false, None),
        Value::Object(o) => (
            o.get("items")
                .and_then(Value::as_array)
                .map(Vec::as_slice)
                .unwrap_or(&[]),
            o.get("isIncomplete")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            o.get("itemDefaults"),
        ),
        _ => (&[][..], false, None),
    };
    let default_range = defaults.and_then(|d| {
        let r = d.get("editRange")?;
        range(r).or_else(|| r.get("insert").and_then(range))
    });
    let default_snippet = defaults
        .and_then(|d| d.get("insertTextFormat"))
        .and_then(Value::as_u64)
        == Some(2);
    let items = items
        .iter()
        .filter_map(|it| {
            let label = it.get("label")?.as_str()?.to_string();
            let edit = it.get("textEdit").and_then(text_edit);
            let insert_text = edit
                .as_ref()
                .map(|e| e.new_text.clone())
                .or_else(|| {
                    it.get("insertText")
                        .and_then(Value::as_str)
                        .map(String::from)
                })
                .unwrap_or_else(|| label.clone());
            let snippet = match it.get("insertTextFormat").and_then(Value::as_u64) {
                Some(f) => f == 2,
                None => default_snippet,
            };
            let mut detail = it
                .get("labelDetails")
                .and_then(|d| d.get("description").or_else(|| d.get("detail")))
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(String::from);
            if let Some(d) = it.get("detail").and_then(Value::as_str) {
                let d = d.trim();
                if !d.is_empty() {
                    detail = Some(d.to_string());
                }
            }
            Some(LspCompletionItem {
                sort_text: it
                    .get("sortText")
                    .and_then(Value::as_str)
                    .unwrap_or(&label)
                    .to_string(),
                filter_text: it
                    .get("filterText")
                    .and_then(Value::as_str)
                    .unwrap_or(&label)
                    .to_string(),
                kind: it.get("kind").and_then(Value::as_u64).unwrap_or(0) as u8,
                detail,
                documentation: it.get("documentation").and_then(markup),
                insert_text,
                snippet,
                edit_range: edit.map(|e| e.range).or(default_range),
                additional_edits: it
                    .get("additionalTextEdits")
                    .and_then(Value::as_array)
                    .map(|a| a.iter().filter_map(text_edit).collect())
                    .unwrap_or_default(),
                label,
            })
        })
        .collect();
    LspCompletion { incomplete, items }
}

pub(crate) fn parse_locations(result: &Value) -> Vec<LspLocation> {
    let one = |v: &Value| -> Option<LspLocation> {
        let uri = v.get("uri").or_else(|| v.get("targetUri"))?.as_str()?;
        let r = v
            .get("range")
            .or_else(|| v.get("targetSelectionRange"))
            .or_else(|| v.get("targetRange"))
            .and_then(range)?;
        Some(LspLocation {
            path: uri_to_path(uri)?,
            range: r,
        })
    };
    match result {
        Value::Array(a) => a.iter().filter_map(one).collect(),
        Value::Object(_) => one(result).into_iter().collect(),
        _ => Vec::new(),
    }
}

pub(crate) fn parse_signature(result: &Value) -> Option<LspSignature> {
    let sigs = result.get("signatures")?.as_array()?;
    let index = result
        .get("activeSignature")
        .and_then(Value::as_u64)
        .unwrap_or(0) as usize;
    let sig = sigs.get(index).or_else(|| sigs.first())?;
    let label = sig.get("label")?.as_str()?.to_string();
    let active = sig
        .get("activeParameter")
        .or_else(|| result.get("activeParameter"))
        .and_then(Value::as_u64)
        .unwrap_or(0) as usize;
    let (mut start, mut end) = (None, None);
    if let Some(p) = sig
        .get("parameters")
        .and_then(Value::as_array)
        .and_then(|p| p.get(active))
    {
        match p.get("label") {
            Some(Value::String(s)) => {
                if let Some(byte) = label.find(s.as_str()) {
                    let s16 = label[..byte].encode_utf16().count() as u32;
                    start = Some(s16);
                    end = Some(s16 + s.encode_utf16().count() as u32);
                }
            }
            Some(Value::Array(a)) if a.len() == 2 => {
                start = a[0].as_u64().map(|v| v as u32);
                end = a[1].as_u64().map(|v| v as u32);
            }
            _ => {}
        }
    }
    Some(LspSignature {
        label,
        documentation: sig.get("documentation").and_then(markup),
        active_start: start,
        active_end: end,
    })
}

pub(crate) fn parse_diagnostics(params: &Value) -> Option<(String, Vec<LspDiagnostic>)> {
    let path = uri_to_path(params.get("uri")?.as_str()?)?;
    let diags = params
        .get("diagnostics")?
        .as_array()?
        .iter()
        .filter_map(|d| {
            Some(LspDiagnostic {
                range: range(d.get("range")?)?,
                severity: d.get("severity").and_then(Value::as_u64).unwrap_or(1) as u8,
                message: d.get("message")?.as_str()?.to_string(),
                source: d.get("source").and_then(Value::as_str).map(String::from),
                code: match d.get("code") {
                    Some(Value::String(s)) => Some(s.clone()),
                    Some(Value::Number(n)) => Some(n.to_string()),
                    _ => None,
                },
            })
        })
        .collect();
    Some((path, diags))
}

fn server_info(init: &Value) -> LspServerInfo {
    let caps = init.get("capabilities").cloned().unwrap_or(Value::Null);
    let enabled = |k: &str| match caps.get(k) {
        Some(Value::Bool(b)) => *b,
        Some(Value::Object(_)) => true,
        _ => false,
    };
    let strings = |v: Option<&Value>| -> Vec<String> {
        v.and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(Value::as_str)
                    .map(String::from)
                    .collect()
            })
            .unwrap_or_default()
    };
    let sync = match caps.get("textDocumentSync") {
        Some(Value::Number(n)) => n.as_u64().unwrap_or(1),
        Some(o @ Value::Object(_)) => o.get("change").and_then(Value::as_u64).unwrap_or(1),
        _ => 1,
    };
    let sig = caps.get("signatureHelpProvider");
    let mut signature_triggers = strings(sig.and_then(|s| s.get("triggerCharacters")));
    signature_triggers.extend(strings(sig.and_then(|s| s.get("retriggerCharacters"))));
    signature_triggers.dedup();
    LspServerInfo {
        name: init
            .pointer("/serverInfo/name")
            .and_then(Value::as_str)
            .unwrap_or("language server")
            .to_string(),
        version: init
            .pointer("/serverInfo/version")
            .and_then(Value::as_str)
            .map(String::from),
        trigger_characters: strings(caps.pointer("/completionProvider/triggerCharacters")),
        signature_triggers,
        incremental_sync: sync == 2,
        completion: enabled("completionProvider"),
        hover: enabled("hoverProvider"),
        definition: enabled("definitionProvider"),
        signature_help: sig.is_some(),
    }
}

fn save_includes_text(init: &Value) -> bool {
    init.pointer("/capabilities/textDocumentSync/save/includeText")
        .and_then(Value::as_bool)
        .unwrap_or(false)
}

fn lookup<'a>(settings: &'a Value, section: Option<&str>) -> Value {
    let Some(section) = section.filter(|s| !s.is_empty()) else {
        return settings.clone();
    };
    let mut cur: &'a Value = settings;
    for part in section.split('.') {
        match cur.get(part) {
            Some(v) => cur = v,
            None => return Value::Null,
        }
    }
    cur.clone()
}

/// Strips snippet syntax (`$1`, `${2:name}`, `${3|a,b|}`) and keeps the default text.
#[uniffi::export]
pub fn lsp_snippet_to_text(snippet: String) -> String {
    let chars: Vec<char> = snippet.chars().collect();
    let mut out = String::new();
    let mut i = 0;
    fn parse(chars: &[char], i: &mut usize, out: &mut String, stop: Option<char>) {
        while *i < chars.len() {
            let c = chars[*i];
            if Some(c) == stop {
                return;
            }
            if c == '\\' && *i + 1 < chars.len() {
                out.push(chars[*i + 1]);
                *i += 2;
                continue;
            }
            if c == '$' && *i + 1 < chars.len() {
                let n = chars[*i + 1];
                if n.is_ascii_digit() {
                    *i += 1;
                    while *i < chars.len() && chars[*i].is_ascii_digit() {
                        *i += 1;
                    }
                    continue;
                }
                if n == '{' {
                    *i += 2;
                    let mut name = String::new();
                    while *i < chars.len()
                        && (chars[*i].is_ascii_alphanumeric() || chars[*i] == '_')
                    {
                        name.push(chars[*i]);
                        *i += 1;
                    }
                    if *i < chars.len() && chars[*i] == ':' {
                        *i += 1;
                        parse(chars, i, out, Some('}'));
                    } else if *i < chars.len() && chars[*i] == '|' {
                        *i += 1;
                        let mut first = String::new();
                        while *i < chars.len() && chars[*i] != ',' && chars[*i] != '|' {
                            first.push(chars[*i]);
                            *i += 1;
                        }
                        out.push_str(&first);
                        while *i < chars.len() && chars[*i] != '}' {
                            *i += 1;
                        }
                    }
                    if *i < chars.len() && chars[*i] == '}' {
                        *i += 1;
                    }
                    continue;
                }
                if n.is_ascii_alphabetic() || n == '_' {
                    *i += 1;
                    while *i < chars.len()
                        && (chars[*i].is_ascii_alphanumeric() || chars[*i] == '_')
                    {
                        *i += 1;
                    }
                    continue;
                }
            }
            out.push(c);
            *i += 1;
        }
    }
    parse(&chars, &mut i, &mut out, None);
    out
}

type Reply = Result<Value, String>;

pub(crate) enum Outbound {
    Frame(Vec<u8>),
    Close,
}

struct Shared {
    out: mpsc::UnboundedSender<Outbound>,
    pending: Mutex<HashMap<i64, oneshot::Sender<Reply>>>,
    next_id: AtomicI64,
    alive: AtomicBool,
    stderr: Mutex<VecDeque<u8>>,
    settings: Value,
    root: String,
    progress: Mutex<Vec<(String, String)>>,
    listener: Arc<dyn LspListener>,
    wait_for_index: bool,
    indexed: AtomicBool,
    started: Instant,
}

const STDERR_TAIL: usize = 8192;
const INDEX_WAIT_LIMIT: Duration = Duration::from_secs(90);

impl Shared {
    fn send(&self, msg: Value) -> Result<(), CoreError> {
        if !self.alive.load(Ordering::SeqCst) {
            return Err(CoreError::Closed);
        }
        self.out
            .send(Outbound::Frame(encode_frame(&msg)))
            .map_err(|_| CoreError::Closed)
    }

    fn notify(&self, method: &str, params: Value) -> Result<(), CoreError> {
        self.send(json!({"jsonrpc": "2.0", "method": method, "params": params}))
    }

    async fn request(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<Value, CoreError> {
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let (tx, rx) = oneshot::channel();
        self.pending.lock().unwrap().insert(id, tx);
        if let Err(e) =
            self.send(json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params}))
        {
            self.pending.lock().unwrap().remove(&id);
            return Err(e);
        }
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(Ok(v))) => Ok(v),
            Ok(Ok(Err(msg))) => Err(CoreError::Io(msg)),
            Ok(Err(_)) => Err(CoreError::Closed),
            Err(_) => {
                self.pending.lock().unwrap().remove(&id);
                let _ = self.notify("$/cancelRequest", json!({"id": id}));
                Err(CoreError::Io(format!("{method} timed out")))
            }
        }
    }

    fn stderr_tail(&self) -> String {
        let buf = self.stderr.lock().unwrap();
        let (a, b) = buf.as_slices();
        let mut v = a.to_vec();
        v.extend_from_slice(b);
        String::from_utf8_lossy(&v).into_owned()
    }

    fn on_stderr(&self, data: &[u8]) {
        let mut buf = self.stderr.lock().unwrap();
        buf.extend(data);
        while buf.len() > STDERR_TAIL {
            buf.pop_front();
        }
    }

    fn reply(&self, id: Value, result: Result<Value, (i64, &str)>) {
        let msg = match result {
            Ok(v) => json!({"jsonrpc": "2.0", "id": id, "result": v}),
            Err((code, m)) => {
                json!({"jsonrpc": "2.0", "id": id, "error": {"code": code, "message": m}})
            }
        };
        let _ = self.send(msg);
    }

    fn handle(&self, msg: Value) {
        let method = msg.get("method").and_then(Value::as_str);
        let id = msg.get("id").cloned();
        match (method, id) {
            (None, Some(id)) => {
                let Some(id) = id.as_i64() else { return };
                let Some(tx) = self.pending.lock().unwrap().remove(&id) else {
                    return;
                };
                let reply = match msg.get("error") {
                    Some(e) => Err(e
                        .get("message")
                        .and_then(Value::as_str)
                        .unwrap_or("language server error")
                        .to_string()),
                    None => Ok(msg.get("result").cloned().unwrap_or(Value::Null)),
                };
                let _ = tx.send(reply);
            }
            (Some(method), Some(id)) => {
                let params = msg.get("params").cloned().unwrap_or(Value::Null);
                self.server_request(method, id, &params);
            }
            (Some(method), None) => {
                let params = msg.get("params").cloned().unwrap_or(Value::Null);
                self.server_notification(method, &params);
            }
            _ => {}
        }
    }

    fn server_request(&self, method: &str, id: Value, params: &Value) {
        let result = match method {
            "workspace/configuration" => Ok(Value::Array(
                params
                    .get("items")
                    .and_then(Value::as_array)
                    .map(|items| {
                        items
                            .iter()
                            .map(|it| {
                                lookup(&self.settings, it.get("section").and_then(Value::as_str))
                            })
                            .collect()
                    })
                    .unwrap_or_default(),
            )),
            "workspace/workspaceFolders" => Ok(json!([{
                "uri": path_to_uri(&self.root),
                "name": crate::paths::name(&self.root),
            }])),
            "workspace/applyEdit" => Ok(json!({"applied": false})),
            "window/showDocument" => Ok(json!({"success": false})),
            "window/workDoneProgress/create"
            | "client/registerCapability"
            | "client/unregisterCapability"
            | "window/showMessageRequest"
            | "workspace/semanticTokens/refresh"
            | "workspace/inlayHint/refresh"
            | "workspace/inlineValue/refresh"
            | "workspace/codeLens/refresh"
            | "workspace/diagnostic/refresh" => Ok(Value::Null),
            _ => Err((-32601, "Method not supported by CodeTab")),
        };
        self.reply(id, result);
    }

    fn server_notification(&self, method: &str, params: &Value) {
        match method {
            "indexingEnded" => self.indexed.store(true, Ordering::SeqCst),
            "textDocument/publishDiagnostics" => {
                if let Some((path, diags)) = parse_diagnostics(params) {
                    self.listener.on_diagnostics(path, diags);
                }
            }
            "window/showMessage" => {
                if let Some(m) = params.get("message").and_then(Value::as_str) {
                    let kind = params.get("type").and_then(Value::as_u64).unwrap_or(3) as u8;
                    self.listener.on_message(kind, m.to_string());
                }
            }
            "$/progress" => {
                let token = match params.get("token") {
                    Some(Value::String(s)) => s.clone(),
                    Some(v) => v.to_string(),
                    None => return,
                };
                let Some(value) = params.get("value") else {
                    return;
                };
                let current = {
                    let mut progress = self.progress.lock().unwrap();
                    match value.get("kind").and_then(Value::as_str) {
                        Some("begin") => {
                            let title = value
                                .get("title")
                                .and_then(Value::as_str)
                                .unwrap_or("Working");
                            progress.retain(|(t, _)| *t != token);
                            progress.push((token, progress_text(title, value)));
                        }
                        Some("report") => {
                            if let Some(entry) = progress.iter_mut().find(|(t, _)| *t == token) {
                                let title = entry.1.split(" · ").next().unwrap_or("").to_string();
                                entry.1 = progress_text(&title, value);
                            }
                        }
                        Some("end") => {
                            progress.retain(|(t, _)| *t != token);
                            if progress.is_empty() {
                                self.indexed.store(true, Ordering::SeqCst);
                            }
                        }
                        _ => return,
                    }
                    progress.last().map(|(_, m)| m.clone())
                };
                self.listener.on_progress(current);
            }
            _ => {}
        }
    }

    fn finish(&self, message: String) {
        if !self.alive.swap(false, Ordering::SeqCst) {
            return;
        }
        self.pending.lock().unwrap().clear();
        self.listener.on_progress(None);
        self.listener.on_exit(message);
    }
}

fn progress_text(title: &str, value: &Value) -> String {
    let mut s = title.to_string();
    if let Some(m) = value
        .get("message")
        .and_then(Value::as_str)
        .filter(|m| !m.is_empty())
    {
        s.push_str(" · ");
        s.push_str(m);
    }
    if let Some(p) = value.get("percentage").and_then(Value::as_u64) {
        s.push_str(&format!(" · {p}%"));
    }
    s
}

#[derive(uniffi::Object)]
pub struct LanguageServer {
    shared: Arc<Shared>,
    info: LspServerInfo,
    save_text: bool,
}

pub(crate) struct LspStart {
    pub root: String,
    pub settings: Value,
    pub init_options: Value,
    pub init_timeout: Duration,
    pub wait_for_index: bool,
}

pub(crate) struct LspIo {
    pub out: mpsc::UnboundedSender<Outbound>,
    pub incoming: mpsc::UnboundedReceiver<Incoming>,
}

pub(crate) enum Incoming {
    Stdout(Vec<u8>),
    Stderr(Vec<u8>),
    Exit(Option<u32>),
    Closed,
}

fn exit_message(code: Option<u32>, stderr: &str) -> String {
    let tail: Vec<&str> = stderr.lines().filter(|l| !l.trim().is_empty()).collect();
    let tail = tail[tail.len().saturating_sub(3)..].join("\n");
    let head = match code {
        Some(127) => "Language server command not found".to_string(),
        Some(c) => format!("Language server exited (code {c})"),
        None => "Language server connection closed".to_string(),
    };
    if tail.is_empty() {
        head
    } else {
        format!("{head}: {tail}")
    }
}

impl LanguageServer {
    pub(crate) async fn start(
        io: LspIo,
        cfg: LspStart,
        listener: Arc<dyn LspListener>,
    ) -> Result<Arc<LanguageServer>, CoreError> {
        let LspIo { out, mut incoming } = io;
        let shared = Arc::new(Shared {
            out,
            pending: Mutex::new(HashMap::new()),
            next_id: AtomicI64::new(1),
            alive: AtomicBool::new(true),
            stderr: Mutex::new(VecDeque::new()),
            settings: cfg.settings.clone(),
            root: cfg.root.clone(),
            progress: Mutex::new(Vec::new()),
            listener,
            wait_for_index: cfg.wait_for_index,
            indexed: AtomicBool::new(false),
            started: Instant::now(),
        });

        let s = shared.clone();
        rt().spawn(async move {
            let mut frames = FrameReader::default();
            let mut code = None;
            while let Some(msg) = incoming.recv().await {
                match msg {
                    Incoming::Stdout(data) => {
                        frames.push(&data);
                        while let Some(frame) = frames.next_frame() {
                            if let Ok(v) = serde_json::from_slice::<Value>(&frame) {
                                s.handle(v);
                            }
                        }
                    }
                    Incoming::Stderr(data) => s.on_stderr(&data),
                    Incoming::Exit(c) => code = c,
                    Incoming::Closed => break,
                }
            }
            s.finish(exit_message(code, &s.stderr_tail()));
        });

        let root_uri = path_to_uri(&cfg.root);
        let mut params = json!({
            "processId": null,
            "clientInfo": {"name": "CodeTab", "version": env!("CARGO_PKG_VERSION")},
            "rootPath": cfg.root,
            "rootUri": root_uri,
            "workspaceFolders": [{"uri": root_uri, "name": crate::paths::name(&cfg.root)}],
            "capabilities": client_capabilities(),
        });
        if !cfg.init_options.is_null() {
            params["initializationOptions"] = cfg.init_options.clone();
        }
        let init = match shared.request("initialize", params, cfg.init_timeout).await {
            Ok(v) => v,
            Err(e) => {
                let detail = exit_message(None, &shared.stderr_tail());
                let _ = shared.out.send(Outbound::Close);
                return Err(match e {
                    CoreError::Closed => {
                        tokio::time::sleep(Duration::from_millis(200)).await;
                        CoreError::Io(exit_message(None, &shared.stderr_tail()).replace(
                            "Language server connection closed",
                            "Language server stopped during startup",
                        ))
                    }
                    CoreError::Io(m) if m.contains("timed out") => CoreError::Io(format!(
                        "Language server did not start within {} s{}",
                        cfg.init_timeout.as_secs(),
                        detail
                            .split_once(": ")
                            .map(|(_, t)| format!(": {t}"))
                            .unwrap_or_default()
                    )),
                    other => other,
                });
            }
        };
        shared.notify("initialized", json!({}))?;
        if !cfg.settings.is_null() {
            shared.notify(
                "workspace/didChangeConfiguration",
                json!({"settings": cfg.settings}),
            )?;
        }
        Ok(Arc::new(LanguageServer {
            info: server_info(&init),
            save_text: save_includes_text(&init),
            shared,
        }))
    }

    fn doc(path: &str) -> Value {
        json!({"uri": path_to_uri(path)})
    }

    fn at(path: &str, line: u32, character: u32) -> Value {
        json!({
            "textDocument": {"uri": path_to_uri(path)},
            "position": {"line": line, "character": character},
        })
    }
}

fn client_capabilities() -> Value {
    json!({
        "general": {"positionEncodings": ["utf-16"]},
        "workspace": {
            "configuration": true,
            "workspaceFolders": true,
            "didChangeConfiguration": {"dynamicRegistration": false},
        },
        "window": {"workDoneProgress": true, "showMessage": {}},
        "textDocument": {
            "synchronization": {"didSave": true, "willSave": false, "dynamicRegistration": false},
            "completion": {
                "contextSupport": true,
                "completionItem": {
                    "snippetSupport": true,
                    "documentationFormat": ["markdown", "plaintext"],
                    "labelDetailsSupport": true,
                    "insertReplaceSupport": false,
                },
                "completionItemKind": {"valueSet": (1..=25).collect::<Vec<u8>>()},
                "completionList": {"itemDefaults": ["editRange", "insertTextFormat"]},
            },
            "hover": {"contentFormat": ["markdown", "plaintext"]},
            "signatureHelp": {
                "signatureInformation": {
                    "documentationFormat": ["markdown", "plaintext"],
                    "parameterInformation": {"labelOffsetSupport": true},
                    "activeParameterSupport": true,
                },
            },
            "definition": {"linkSupport": true},
            "publishDiagnostics": {"relatedInformation": false},
        },
    })
}

fn ms(timeout_ms: u32) -> Duration {
    Duration::from_millis(timeout_ms.max(50) as u64)
}

#[uniffi::export]
impl LanguageServer {
    pub fn info(&self) -> LspServerInfo {
        self.info.clone()
    }

    pub fn is_alive(&self) -> bool {
        self.shared.alive.load(Ordering::SeqCst)
    }

    pub fn stderr_tail(&self) -> String {
        self.shared.stderr_tail()
    }

    pub fn did_open(
        &self,
        path: String,
        language_id: String,
        version: i32,
        text: String,
    ) -> Result<(), CoreError> {
        self.shared.notify(
            "textDocument/didOpen",
            json!({"textDocument": {
                "uri": path_to_uri(&path), "languageId": language_id, "version": version, "text": text,
            }}),
        )
    }

    pub fn did_change(
        &self,
        path: String,
        version: i32,
        changes: Vec<LspChange>,
    ) -> Result<(), CoreError> {
        let changes: Vec<Value> = changes
            .iter()
            .map(|c| match &c.range {
                Some(r) => json!({"range": range_json(r), "text": c.text}),
                None => json!({"text": c.text}),
            })
            .collect();
        self.shared.notify(
            "textDocument/didChange",
            json!({
                "textDocument": {"uri": path_to_uri(&path), "version": version},
                "contentChanges": changes,
            }),
        )
    }

    pub fn did_save(&self, path: String, text: String) -> Result<(), CoreError> {
        let mut params = json!({"textDocument": Self::doc(&path)});
        if self.save_text {
            params["text"] = Value::String(text);
        }
        self.shared.notify("textDocument/didSave", params)
    }

    pub fn did_close(&self, path: String) -> Result<(), CoreError> {
        self.shared.notify(
            "textDocument/didClose",
            json!({"textDocument": Self::doc(&path)}),
        )
    }

    pub fn completion(
        &self,
        path: String,
        line: u32,
        character: u32,
        trigger: Option<String>,
        timeout_ms: u32,
    ) -> Result<LspCompletion, CoreError> {
        if self.shared.wait_for_index
            && !self.shared.indexed.load(Ordering::SeqCst)
            && self.shared.started.elapsed() < INDEX_WAIT_LIMIT
        {
            return Ok(LspCompletion {
                incomplete: true,
                items: Vec::new(),
            });
        }
        let mut params = Self::at(&path, line, character);
        params["context"] = match trigger {
            Some(t) => json!({"triggerKind": 2, "triggerCharacter": t}),
            None => json!({"triggerKind": 1}),
        };
        let r = rt().block_on(self.shared.request(
            "textDocument/completion",
            params,
            ms(timeout_ms),
        ))?;
        Ok(parse_completion(&r))
    }

    pub fn hover(
        &self,
        path: String,
        line: u32,
        character: u32,
        timeout_ms: u32,
    ) -> Result<Option<String>, CoreError> {
        let r = rt().block_on(self.shared.request(
            "textDocument/hover",
            Self::at(&path, line, character),
            ms(timeout_ms),
        ))?;
        Ok(r.get("contents").and_then(markup))
    }

    pub fn definition(
        &self,
        path: String,
        line: u32,
        character: u32,
        timeout_ms: u32,
    ) -> Result<Vec<LspLocation>, CoreError> {
        let r = rt().block_on(self.shared.request(
            "textDocument/definition",
            Self::at(&path, line, character),
            ms(timeout_ms),
        ))?;
        Ok(parse_locations(&r))
    }

    pub fn signature_help(
        &self,
        path: String,
        line: u32,
        character: u32,
        timeout_ms: u32,
    ) -> Result<Option<LspSignature>, CoreError> {
        let r = rt().block_on(self.shared.request(
            "textDocument/signatureHelp",
            Self::at(&path, line, character),
            ms(timeout_ms),
        ))?;
        Ok(parse_signature(&r))
    }

    /// Asks the server to exit and closes the channel.
    pub fn shutdown(&self) {
        if !self.is_alive() {
            return;
        }
        rt().block_on(async {
            let _ = self
                .shared
                .request("shutdown", Value::Null, Duration::from_secs(3))
                .await;
            let _ = self.shared.notify("exit", Value::Null);
            tokio::time::sleep(Duration::from_millis(50)).await;
            let _ = self.shared.out.send(Outbound::Close);
        });
    }
}

impl Drop for LanguageServer {
    fn drop(&mut self) {
        let _ = self.shared.out.send(Outbound::Close);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_survive_noise_and_splits() {
        let mut r = FrameReader::default();
        let a = encode_frame(&json!({"id": 1}));
        let b = encode_frame(&json!({"id": 2, "s": "ü"}));
        r.push(b"Welcome to the cluster!\n");
        r.push(&a[..10]);
        assert!(r.next_frame().is_none());
        r.push(&a[10..]);
        r.push(&b[..b.len() - 2]);
        assert_eq!(r.next_frame().unwrap(), br#"{"id":1}"#);
        assert!(r.next_frame().is_none());
        r.push(&b[b.len() - 2..]);
        let v: Value = serde_json::from_slice(&r.next_frame().unwrap()).unwrap();
        assert_eq!(v["s"], "ü");
        r.push(b"content-length: 2\r\nContent-Type: x\r\n\r\n{}");
        assert_eq!(r.next_frame().unwrap(), b"{}");
    }

    #[test]
    fn uris_round_trip() {
        let p = "/home/me/my project/naïve#1.py";
        let uri = path_to_uri(p);
        assert_eq!(uri, "file:///home/me/my%20project/na%C3%AFve%231.py");
        assert_eq!(uri_to_path(&uri).unwrap(), p);
        assert_eq!(uri_to_path("file://localhost/tmp/a").unwrap(), "/tmp/a");
        assert!(uri_to_path("jdt://contents/x").is_none());
    }

    #[test]
    fn completion_results_are_normalized() {
        let r = json!({
            "isIncomplete": true,
            "itemDefaults": {"editRange": {"start": {"line": 1, "character": 4}, "end": {"line": 1, "character": 6}}},
            "items": [
                {"label": "append", "kind": 2, "detail": "(obj) -> None",
                 "documentation": {"kind": "markdown", "value": "Append object."}},
                {"label": "print", "insertText": "print(${1:x})$0", "insertTextFormat": 2, "sortText": "0"},
                {"label": "np", "textEdit": {"range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 1}}, "newText": "numpy"},
                 "additionalTextEdits": [{"range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 0}}, "newText": "import numpy\n"}]},
            ],
        });
        let c = parse_completion(&r);
        assert!(c.incomplete);
        assert_eq!(c.items.len(), 3);
        assert_eq!(c.items[0].documentation.as_deref(), Some("Append object."));
        assert_eq!(c.items[0].edit_range.unwrap().start.character, 4);
        assert!(c.items[1].snippet);
        assert_eq!(c.items[1].sort_text, "0");
        assert_eq!(c.items[2].insert_text, "numpy");
        assert_eq!(c.items[2].edit_range.unwrap().end.character, 1);
        assert_eq!(c.items[2].additional_edits.len(), 1);
        assert_eq!(
            parse_completion(&json!([{"label": "x"}])).items[0].insert_text,
            "x"
        );
        assert!(parse_completion(&Value::Null).items.is_empty());
    }

    #[test]
    fn hover_locations_and_signatures() {
        assert_eq!(
            markup(&json!([{"language": "python", "value": "def f()"}, "Docs"])).unwrap(),
            "```python\ndef f()\n```\n\nDocs"
        );
        let link = json!([{"targetUri": "file:///a/b.rs", "targetRange": {"start": {"line": 3, "character": 0}, "end": {"line": 9, "character": 1}},
            "targetSelectionRange": {"start": {"line": 3, "character": 3}, "end": {"line": 3, "character": 4}}}]);
        let l = parse_locations(&link);
        assert_eq!(l[0].path, "/a/b.rs");
        assert_eq!(l[0].range.start.character, 3);
        let sig = parse_signature(&json!({"signatures": [{"label": "f(a, b)", "parameters": [{"label": "a"}, {"label": [5, 6]}]}], "activeParameter": 1})).unwrap();
        assert_eq!((sig.active_start, sig.active_end), (Some(5), Some(6)));
        let sig = parse_signature(&json!({"signatures": [{"label": "g(x, y)", "parameters": [{"label": "x"}, {"label": "y"}]}], "activeParameter": 1})).unwrap();
        assert_eq!((sig.active_start, sig.active_end), (Some(5), Some(6)));
    }

    #[test]
    fn snippets_become_plain_text() {
        assert_eq!(lsp_snippet_to_text("print(${1:x})$0".into()), "print(x)");
        assert_eq!(
            lsp_snippet_to_text("for ${1:i} in ${2:range(${3:n})}:\n\t$0".into()),
            "for i in range(n):\n\t"
        );
        assert_eq!(
            lsp_snippet_to_text("${1|public,private|} \\$x $TM_FILENAME".into()),
            "public $x "
        );
    }

    #[test]
    fn capabilities_and_configuration() {
        let info = server_info(&json!({"capabilities": {
            "textDocumentSync": {"change": 2, "save": {"includeText": true}},
            "completionProvider": {"triggerCharacters": [".", "::"]},
            "hoverProvider": true,
            "signatureHelpProvider": {"triggerCharacters": ["("], "retriggerCharacters": [","]},
        }, "serverInfo": {"name": "pyright", "version": "1.1"}}));
        assert!(
            info.incremental_sync
                && info.completion
                && info.hover
                && info.signature_help
                && !info.definition
        );
        assert_eq!(info.trigger_characters, vec![".", "::"]);
        assert_eq!(info.signature_triggers, vec!["(", ","]);
        assert_eq!(info.name, "pyright");
        let settings = json!({"python": {"pythonPath": "/env/bin/python"}});
        assert_eq!(
            lookup(&settings, Some("python.pythonPath")),
            "/env/bin/python"
        );
        assert_eq!(lookup(&settings, Some("missing")), Value::Null);
        assert_eq!(lookup(&settings, None), settings);
    }

    #[test]
    fn diagnostics_are_parsed() {
        let (path, d) = parse_diagnostics(&json!({"uri": "file:///p/a.py", "diagnostics": [
            {"range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 3}}, "severity": 2, "message": "unused", "code": 401, "source": "Pyright"}
        ]})).unwrap();
        assert_eq!(path, "/p/a.py");
        assert_eq!(d[0].severity, 2);
        assert_eq!(d[0].code.as_deref(), Some("401"));
    }
}
