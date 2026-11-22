use crate::paths::quote;
use crate::tmux::login_shell;
use serde_json::{json, Value};

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LspServerSpec {
    pub id: String,
    pub name: String,
    pub languages: Vec<String>,
    pub binary: String,
    pub command: String,
    pub install: String,
    pub root_markers: Vec<String>,
    pub init_timeout_secs: u32,
    /// The server misbehaves when asked for completions before its first index is built.
    pub wait_for_index: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LspProbe {
    pub binaries: Vec<String>,
    pub python: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LspServerConfig {
    pub settings_json: String,
    pub init_options_json: String,
}

const BIN_MARKER: &str = "__codetab_lsp__";
const PY_MARKER: &str = "__codetab_py__";
const EXTRA_PATH: &str = "$HOME/.local/bin:$HOME/bin:$HOME/go/bin:$HOME/.cargo/bin:$HOME/.dotnet/tools:$HOME/.npm-global/bin:$HOME/.juliaup/bin:$HOME/.ghcup/bin";
const NPM: &str = "npm install -g --prefix ~/.local";

fn spec(
    id: &str,
    name: &str,
    languages: &str,
    command: &str,
    install: &str,
    markers: &str,
    init_timeout_secs: u32,
) -> LspServerSpec {
    LspServerSpec {
        id: id.into(),
        name: name.into(),
        languages: languages.split_whitespace().map(String::from).collect(),
        binary: command
            .split_whitespace()
            .find(|w| !w.contains('='))
            .unwrap_or("")
            .into(),
        command: command.into(),
        install: install.into(),
        root_markers: markers.split_whitespace().map(String::from).collect(),
        init_timeout_secs,
        wait_for_index: false,
    }
}

/// Known language servers, in order of preference for each language.
#[uniffi::export]
pub fn language_servers() -> Vec<LspServerSpec> {
    let py = "pyproject.toml setup.py setup.cfg requirements.txt pyrightconfig.json Pipfile .git";
    let js = "tsconfig.json jsconfig.json package.json .git";
    let web = "package.json .git";
    let c =
        "compile_commands.json compile_flags.txt .clangd CMakeLists.txt meson.build Makefile .git";
    vec![
        spec("pyright", "Pyright", "python", "pyright-langserver --stdio", "pip install --user pyright", py, 60),
        spec("basedpyright", "basedpyright", "python", "basedpyright-langserver --stdio", "pip install --user basedpyright", py, 60),
        spec("pylsp", "python-lsp-server", "python", "pylsp", "pip install --user 'python-lsp-server[all]'", py, 60),
        spec("jedi", "Jedi Language Server", "python", "jedi-language-server", "pip install --user jedi-language-server", py, 60),
        spec("clangd", "clangd", "c cpp cuda-cpp objective-c objective-cpp", "clangd --header-insertion=never", "pip install --user clangd", c, 60),
        spec("rust-analyzer", "rust-analyzer", "rust", "rust-analyzer", "rustup component add rust-analyzer", "Cargo.toml .git", 120),
        spec("gopls", "gopls", "go", "gopls", "go install golang.org/x/tools/gopls@latest", "go.work go.mod .git", 60),
        spec("tsgo", "TypeScript 7 native server", "javascript javascriptreact typescript typescriptreact",
            "tsc --lsp --stdio", &format!("{NPM} typescript"), js, 60),
        {
            let mut ts = spec("typescript", "TypeScript Language Server", "javascript javascriptreact typescript typescriptreact",
            "NODE_PATH=\"$(cd \"$(dirname \"$(readlink -f \"$(command -v typescript-language-server)\")\")/../..\" && pwd)${NODE_PATH:+:$NODE_PATH}\" typescript-language-server --stdio", &format!("{NPM} typescript@5 typescript-language-server"), js, 60);
            ts.binary = "typescript-language-server".into();
            ts
        },
        spec("html", "HTML Language Server", "html handlebars", "vscode-html-language-server --stdio",
            &format!("{NPM} vscode-langservers-extracted"), web, 30),
        spec("css", "CSS Language Server", "css scss less", "vscode-css-language-server --stdio",
            &format!("{NPM} vscode-langservers-extracted"), web, 30),
        spec("json", "JSON Language Server", "json jsonc", "vscode-json-language-server --stdio",
            &format!("{NPM} vscode-langservers-extracted"), web, 30),
        {
            let mut s = spec("intelephense", "Intelephense", "php", "intelephense --stdio", &format!("{NPM} intelephense"), "composer.json .git", 60);
            s.wait_for_index = true;
            s
        },
        spec("phpactor", "Phpactor", "php", "phpactor language-server", "composer global require phpactor/phpactor", "composer.json .git", 60),
        spec("yaml", "YAML Language Server", "yaml", "yaml-language-server --stdio", &format!("{NPM} yaml-language-server"), ".git", 30),
        spec("taplo", "Taplo", "toml", "taplo lsp stdio", "cargo install --locked taplo-cli --features lsp", ".git", 30),
        spec("bash", "Bash Language Server", "shellscript", "bash-language-server start", &format!("{NPM} bash-language-server"), ".git", 30),
        spec("docker", "Dockerfile Language Server", "dockerfile", "docker-langserver --stdio",
            &format!("{NPM} dockerfile-language-server-nodejs"), ".git", 30),
        spec("jdtls", "Eclipse JDT Language Server", "java",
            "jdtls -data \"$HOME/.cache/codetab/jdtls/$(pwd | cksum | cut -d' ' -f1)\"",
            "Install Eclipse JDT LS (for example with conda install -c conda-forge jdtls) so that jdtls is on PATH",
            "pom.xml build.gradle build.gradle.kts settings.gradle settings.gradle.kts .git", 180),
        spec("kotlin", "Kotlin Language Server", "kotlin", "kotlin-language-server",
            "Download kotlin-language-server from github.com/fwcd/kotlin-language-server/releases and add its bin folder to PATH",
            "settings.gradle.kts settings.gradle build.gradle.kts build.gradle pom.xml .git", 180),
        spec("csharp-ls", "csharp-ls", "csharp", "csharp-ls", "dotnet tool install --global csharp-ls", "*.sln *.csproj .git", 120),
        spec("omnisharp", "OmniSharp", "csharp", "OmniSharp -lsp", "Install OmniSharp from github.com/OmniSharp/omnisharp-roslyn/releases", "*.sln *.csproj .git", 120),
        spec("fsautocomplete", "FsAutoComplete", "fsharp", "fsautocomplete", "dotnet tool install --global fsautocomplete", "*.sln *.fsproj .git", 120),
        spec("ruby-lsp", "Ruby LSP", "ruby", "ruby-lsp", "gem install --user-install ruby-lsp", "Gemfile .git", 60),
        spec("solargraph", "Solargraph", "ruby", "solargraph stdio", "gem install --user-install solargraph", "Gemfile .git", 60),
        spec("lua", "Lua Language Server", "lua", "lua-language-server",
            "Download lua-language-server from github.com/LuaLS/lua-language-server/releases and add its bin folder to PATH", ".luarc.json .git", 60),
        spec("r", "R languageserver", "r", "R --no-echo -e 'languageserver::run()'",
            "R -e 'install.packages(\"languageserver\", repos = \"https://cloud.r-project.org\")'", "DESCRIPTION .Rproj.user .git", 60),
        spec("julia", "Julia LanguageServer", "julia",
            "julia --startup-file=no --history-file=no -e 'using LanguageServer; runserver()'",
            "julia -e 'using Pkg; Pkg.add(\"LanguageServer\")'", "Project.toml .git", 300),
        spec("perl", "Perl Navigator", "perl", "perlnavigator --stdio", &format!("{NPM} perlnavigator-server"), ".git", 30),
        spec("texlab", "TexLab", "latex tex bibtex", "texlab", "cargo install --locked texlab", ".latexmkrc latexmkrc .git", 30),
        spec("marksman", "Marksman", "markdown", "marksman server",
            "Download marksman from github.com/artempyanykh/marksman/releases into ~/.local/bin", ".marksman.toml .git", 30),
        spec("esbonio", "Esbonio", "restructuredtext", "esbonio", "pip install --user esbonio", "conf.py .git", 60),
        spec("lemminx", "LemMinX", "xml xsl", "lemminx",
            "Download the lemminx binary from github.com/redhat-developer/vscode-xml/releases into ~/.local/bin", ".git", 30),
        spec("sqls", "sqls", "sql", "sqls", "go install github.com/sqls-server/sqls@latest", ".git", 30),
        spec("dart", "Dart Analysis Server", "dart", "dart language-server --protocol=lsp", "Install the Dart or Flutter SDK", "pubspec.yaml .git", 60),
        spec("sourcekit", "SourceKit-LSP", "swift", "sourcekit-lsp", "Install the Swift toolchain from swift.org", "Package.swift .git", 60),
        spec("clojure-lsp", "clojure-lsp", "clojure", "clojure-lsp", "Install clojure-lsp from clojure-lsp.io", "deps.edn project.clj bb.edn .git", 120),
        spec("groovy", "Groovy Language Server", "groovy", "groovy-language-server",
            "Build groovy-language-server from github.com/GroovyLanguageServer/groovy-language-server and add a groovy-language-server wrapper to PATH",
            "build.gradle settings.gradle .git", 120),
        spec("powershell", "PowerShell Editor Services", "powershell", "powershell-editor-services",
            "Install PowerShellEditorServices and add a powershell-editor-services wrapper that starts it with -Stdio", ".git", 120),
        spec("makefile", "autotools-language-server", "makefile", "autotools-language-server", "pip install --user autotools-language-server", "Makefile .git", 30),
    ]
}

/// Lists which of the binaries exist on the server and, when a Python command is given,
/// the interpreter it resolves to.
#[uniffi::export]
pub fn lsp_probe_command(binaries: Vec<String>, python_command: Option<String>) -> String {
    let mut script = String::new();
    if let Some(py) = python_command.filter(|p| !p.trim().is_empty()) {
        script.push_str(&format!(
            "P=$({py} -c 'import sys; print(sys.executable)' 2>/dev/null </dev/null | tail -n 1); \
             [ -n \"$P\" ] && echo {PY_MARKER} \"$P\" && PATH=\"$(dirname \"$P\"):$PATH\"; "
        ));
    }
    script.push_str(&format!("PATH=\"$PATH:{EXTRA_PATH}\"; for b in"));
    for b in &binaries {
        script.push(' ');
        script.push_str(&quote(b));
    }
    script.push_str(&format!(
        "; do command -v \"$b\" >/dev/null 2>&1 && echo {BIN_MARKER} \"$b\"; done; exit 0"
    ));
    login_shell(&script)
}

#[uniffi::export]
pub fn parse_lsp_probe(output: String) -> LspProbe {
    let mut probe = LspProbe {
        binaries: Vec::new(),
        python: None,
    };
    for line in output.lines() {
        if let Some(b) = line.strip_prefix(BIN_MARKER) {
            probe.binaries.push(b.trim().to_string());
        } else if let Some(p) = line.strip_prefix(PY_MARKER) {
            probe.python = Some(p.trim().to_string()).filter(|p| !p.is_empty());
        }
    }
    probe
}

/// Starts a language server in `root` from a login shell, with common user install
/// folders and the active Python environment on PATH.
#[uniffi::export]
pub fn lsp_launch_command(command: String, root: String, python: Option<String>) -> String {
    let mut path = String::from("PATH=\"");
    if let Some(p) = &python {
        path.push_str(&format!("$(dirname {}):", quote(p)));
    }
    path.push_str(&format!("$PATH:{EXTRA_PATH}\"; export PATH; "));
    login_shell(&format!(
        "{path}cd {} 2>/dev/null || cd; {command}",
        quote(&root)
    ))
}

/// Prints the nearest folder above `dir` that contains one of `markers`, stopping at
/// `stop`. Falls back to `stop` when `dir` is inside it, otherwise to `dir`.
#[uniffi::export]
pub fn lsp_root_command(dir: String, markers: Vec<String>, stop: String) -> String {
    let globs: Vec<String> = markers
        .iter()
        .map(|m| {
            if m.contains('*') {
                m.chars()
                    .filter(|c| c.is_ascii_alphanumeric() || "*._-".contains(*c))
                    .collect()
            } else {
                quote(m)
            }
        })
        .collect();
    format!(
        "d={dir}; s={stop}; while :; do for m in {globs}; do for f in \"$d\"/$m; do \
         [ -e \"$f\" ] && {{ echo \"$d\"; exit 0; }}; done; done; \
         [ \"$d\" = \"$s\" ] || [ \"$d\" = / ] || [ -z \"$d\" ] && break; d=$(dirname \"$d\"); done; \
         case {dir}/ in {stop_glob}/*) echo \"$s\" ;; *) echo {dir} ;; esac",
        dir = quote(&dir),
        stop = quote(&stop),
        globs = globs.join(" "),
        stop_glob = quote(stop.trim_end_matches('/')),
    )
}

/// Settings and initialization options for a server, given the resolved Python
/// interpreter of the host profile.
#[uniffi::export]
pub fn lsp_server_config(server_id: String, python: Option<String>) -> LspServerConfig {
    let analysis = json!({
        "autoSearchPaths": true,
        "useLibraryCodeForTypes": true,
        "diagnosticMode": "openFilesOnly",
    });
    let (settings, init): (Value, Value) = match server_id.as_str() {
        "pyright" | "basedpyright" => {
            let mut s = json!({
                "python": {"analysis": analysis},
                "basedpyright": {"analysis": analysis},
            });
            if let Some(p) = &python {
                s["python"]["pythonPath"] = json!(p);
            }
            (s, Value::Null)
        }
        "pylsp" => (
            json!({"pylsp": {"plugins": {"jedi": {"environment": python}}}}),
            Value::Null,
        ),
        "jedi" => (
            Value::Null,
            json!({"workspace": {"environmentPath": python}}),
        ),
        "html" => (
            json!({"html": {"suggest": {"html5": true}}, "css": {}, "javascript": {}}),
            json!({"provideFormatter": false, "embeddedLanguages": {"css": true, "javascript": true}}),
        ),
        "css" => (
            json!({"css": {"validate": true}, "scss": {"validate": true}, "less": {"validate": true}}),
            json!({"provideFormatter": false}),
        ),
        "json" => (
            json!({"json": {"validate": {"enable": true}, "schemaDownload": {"enable": true}, "schemas": json_schemas()}}),
            json!({"provideFormatter": false, "handledSchemaProtocols": ["file", "http", "https"]}),
        ),
        "yaml" => (
            json!({"yaml": {"validate": true, "completion": true, "hover": true, "schemaStore": {"enable": true}}}),
            Value::Null,
        ),
        "typescript" => (Value::Null, json!({"hostInfo": "codetab"})),
        "intelephense" => (
            Value::Null,
            json!({"storagePath": "/tmp/codetab-intelephense"}),
        ),
        "lua" => (
            json!({"Lua": {"telemetry": {"enable": false}}}),
            Value::Null,
        ),
        _ => (Value::Null, Value::Null),
    };
    LspServerConfig {
        settings_json: settings.to_string(),
        init_options_json: init.to_string(),
    }
}

fn json_schemas() -> Value {
    let store = |files: &[&str], name: &str| json!({"fileMatch": files, "url": format!("https://json.schemastore.org/{name}.json")});
    json!([
        store(&["package.json"], "package"),
        store(&["tsconfig.json", "tsconfig.*.json"], "tsconfig"),
        store(&["jsconfig.json"], "jsconfig"),
        store(&["composer.json"], "composer"),
        store(&[".eslintrc", ".eslintrc.json"], "eslintrc"),
        store(&[".prettierrc", ".prettierrc.json"], "prettierrc"),
        store(&[".babelrc", "babel.config.json"], "babelrc"),
        store(&["devcontainer.json", ".devcontainer.json"], "devcontainer"),
        store(&["*.code-workspace"], "code-workspace"),
    ])
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Command;

    fn sh(script: &str) -> String {
        let out = Command::new("sh").arg("-c").arg(script).output().unwrap();
        String::from_utf8_lossy(&out.stdout).into_owned()
    }

    #[test]
    fn registry_is_consistent() {
        let servers = language_servers();
        let mut ids: Vec<&str> = servers.iter().map(|s| s.id.as_str()).collect();
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), servers.len());
        for s in &servers {
            assert!(
                !s.languages.is_empty() && !s.binary.is_empty() && !s.install.is_empty(),
                "{}",
                s.id
            );
            assert!(s.command.contains(&s.binary));
        }
        let python: Vec<&str> = servers
            .iter()
            .filter(|s| s.languages.iter().any(|l| l == "python"))
            .map(|s| s.id.as_str())
            .collect();
        assert_eq!(python[0], "pyright");
    }

    #[test]
    fn probe_finds_binaries_and_python() {
        let cmd = lsp_probe_command(
            vec!["sh".into(), "no-such-server-xyz".into()],
            Some("python3".into()),
        );
        assert!(cmd.starts_with("exec \"${SHELL:-/bin/sh}\" -lc '"));
        let probe = parse_lsp_probe(sh(&cmd));
        assert_eq!(probe.binaries, vec!["sh"]);
        if sh("command -v python3").trim().is_empty() {
            return;
        }
        assert!(probe.python.unwrap().contains("python"));
        let none = parse_lsp_probe("hello\n__codetab_py__ \n".into());
        assert!(none.python.is_none() && none.binaries.is_empty());
    }

    #[test]
    fn launch_runs_in_root() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("my project");
        std::fs::create_dir(&root).unwrap();
        let out = sh(&lsp_launch_command(
            "pwd; echo \"$PATH\"".into(),
            root.display().to_string(),
            Some("/opt/env/bin/python".into()),
        ));
        let mut lines = out.lines();
        assert_eq!(lines.next().unwrap(), root.display().to_string());
        let path = lines.next().unwrap();
        assert!(path.starts_with("/opt/env/bin:"));
        assert!(path.contains("/.local/bin"));
    }

    #[test]
    fn root_is_found_by_marker() {
        let dir = tempfile::tempdir().unwrap();
        let base = dir.path().display().to_string();
        let crate_dir = format!("{base}/ws/app");
        std::fs::create_dir_all(format!("{crate_dir}/src/deep")).unwrap();
        std::fs::write(format!("{crate_dir}/Cargo.toml"), "").unwrap();
        std::fs::write(format!("{base}/ws/App.csproj"), "").unwrap();
        let ws = format!("{base}/ws");
        let run = |d: &str, m: &[&str], stop: &str| {
            sh(&lsp_root_command(
                d.into(),
                m.iter().map(|s| s.to_string()).collect(),
                stop.into(),
            ))
            .trim()
            .to_string()
        };
        assert_eq!(
            run(&format!("{crate_dir}/src/deep"), &["Cargo.toml"], &ws),
            crate_dir
        );
        assert_eq!(run(&format!("{crate_dir}/src"), &["*.csproj"], &ws), ws);
        assert_eq!(run(&format!("{crate_dir}/src"), &["go.mod"], &ws), ws);
        assert_eq!(
            run(&format!("{base}/elsewhere"), &["go.mod"], &ws),
            format!("{base}/elsewhere")
        );
    }

    #[test]
    fn python_servers_get_the_interpreter() {
        let c = lsp_server_config("pyright".into(), Some("/env/bin/python".into()));
        let s: Value = serde_json::from_str(&c.settings_json).unwrap();
        assert_eq!(s["python"]["pythonPath"], "/env/bin/python");
        let j = lsp_server_config("jedi".into(), Some("/env/bin/python".into()));
        let i: Value = serde_json::from_str(&j.init_options_json).unwrap();
        assert_eq!(i["workspace"]["environmentPath"], "/env/bin/python");
        assert_eq!(
            lsp_server_config("gopls".into(), None).settings_json,
            "null"
        );
    }
}
