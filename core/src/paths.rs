#[uniffi::export]
pub fn path_normalize(path: String) -> String {
    normalize(&path)
}

#[uniffi::export]
pub fn path_join(dir: String, name: String) -> String {
    join(&dir, &name)
}

#[uniffi::export]
pub fn path_parent(path: String) -> String {
    parent(&path)
}

#[uniffi::export]
pub fn path_name(path: String) -> String {
    name(&path)
}

#[uniffi::export]
pub fn path_extension(path: String) -> String {
    let n = name(&path);
    match n.rfind('.') {
        Some(i) if i > 0 => n[i + 1..].to_lowercase(),
        _ => String::new(),
    }
}

#[uniffi::export]
pub fn path_relative_to(path: String, base: String) -> Option<String> {
    let p = normalize(&path);
    let b = normalize(&base);
    if p == b {
        return Some(".".into());
    }
    let prefix = if b == "/" {
        "/".to_string()
    } else {
        format!("{b}/")
    };
    p.strip_prefix(&prefix).map(str::to_string)
}

#[uniffi::export]
pub fn path_expand_home(path: String, home: String) -> String {
    if path == "~" {
        normalize(&home)
    } else if let Some(rest) = path.strip_prefix("~/") {
        join(&home, rest)
    } else {
        normalize(&path)
    }
}

#[uniffi::export]
pub fn is_valid_file_name(name: String) -> bool {
    !name.trim().is_empty()
        && name != "."
        && name != ".."
        && !name.contains('/')
        && !name.contains('\0')
}

#[uniffi::export]
pub fn sh_quote(arg: String) -> String {
    quote(&arg)
}

pub(crate) fn quote(arg: &str) -> String {
    let safe = !arg.is_empty()
        && arg
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "@%+=:,./_-".contains(c));
    if safe {
        arg.to_string()
    } else {
        format!("'{}'", arg.replace('\'', "'\\''"))
    }
}

pub(crate) fn normalize(path: &str) -> String {
    if path.is_empty() {
        return ".".into();
    }
    let absolute = path.starts_with('/');
    let mut out: Vec<&str> = Vec::new();
    for part in path.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                if matches!(out.last(), Some(l) if *l != "..") {
                    out.pop();
                } else if !absolute {
                    out.push("..");
                }
            }
            p => out.push(p),
        }
    }
    let body = out.join("/");
    if absolute {
        format!("/{body}")
    } else if body.is_empty() {
        ".".into()
    } else {
        body
    }
}

pub(crate) fn join(dir: &str, name: &str) -> String {
    if name.starts_with('/') {
        normalize(name)
    } else {
        normalize(&format!("{dir}/{name}"))
    }
}

pub(crate) fn parent(path: &str) -> String {
    let p = normalize(path);
    if p == "/" {
        return p;
    }
    match p.rfind('/') {
        None => ".".into(),
        Some(0) => "/".into(),
        Some(i) => p[..i].to_string(),
    }
}

pub(crate) fn name(path: &str) -> String {
    let p = normalize(path);
    if p == "/" {
        return p;
    }
    p.rsplit('/').next().unwrap_or("").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_handles_dots_and_slashes() {
        assert_eq!(normalize("/home//user/./proj/../data/"), "/home/user/data");
        assert_eq!(normalize("/.."), "/");
        assert_eq!(normalize("a/../../b"), "../b");
        assert_eq!(normalize(""), ".");
        assert_eq!(normalize("./"), ".");
    }

    #[test]
    fn join_parent_name() {
        assert_eq!(join("/home/user", "run.py"), "/home/user/run.py");
        assert_eq!(join("/home/user/", "../x"), "/home/x");
        assert_eq!(join("/home/user", "/etc/hosts"), "/etc/hosts");
        assert_eq!(parent("/home/user/run.py"), "/home/user");
        assert_eq!(parent("/home"), "/");
        assert_eq!(parent("/"), "/");
        assert_eq!(parent("file"), ".");
        assert_eq!(name("/home/user/run.py"), "run.py");
        assert_eq!(name("/"), "/");
    }

    #[test]
    fn extension_relative_home() {
        assert_eq!(path_extension("/a/B.PY".into()), "py");
        assert_eq!(path_extension("/a/.bashrc".into()), "");
        assert_eq!(path_extension("/a/Makefile".into()), "");
        assert_eq!(
            path_relative_to("/home/user/p/x.py".into(), "/home/user".into()).as_deref(),
            Some("p/x.py")
        );
        assert_eq!(
            path_relative_to("/home/userx".into(), "/home/user".into()),
            None
        );
        assert_eq!(
            path_relative_to("/etc".into(), "/".into()).as_deref(),
            Some("etc")
        );
        assert_eq!(
            path_expand_home("~/proj".into(), "/home/user".into()),
            "/home/user/proj"
        );
        assert_eq!(
            path_expand_home("~".into(), "/home/user".into()),
            "/home/user"
        );
        assert_eq!(
            path_expand_home("/scratch/x/".into(), "/home/user".into()),
            "/scratch/x"
        );
    }

    #[test]
    fn file_names() {
        assert!(is_valid_file_name("train.py".into()));
        assert!(!is_valid_file_name("a/b".into()));
        assert!(!is_valid_file_name("..".into()));
        assert!(!is_valid_file_name("  ".into()));
    }

    #[test]
    fn quoting_is_shell_safe() {
        assert_eq!(quote("run.py"), "run.py");
        assert_eq!(quote("/home/user/data_v2.csv"), "/home/user/data_v2.csv");
        assert_eq!(quote("my file.py"), "'my file.py'");
        assert_eq!(quote("it's"), "'it'\\''s'");
        assert_eq!(quote(""), "''");
        assert_eq!(quote("$(rm -rf ~)"), "'$(rm -rf ~)'");
        assert_eq!(quote("café"), "'café'");
    }
}
