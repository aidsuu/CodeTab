package app.jangkau.editor

object Languages {
    data class Lang(val scope: String?, val label: String, val badge: String, val color: Long, val lspId: String? = null)

    private class Type(val lang: Lang, val extensions: List<String>, val names: List<String>)

    private fun type(scope: String, label: String, badge: String, color: Long, ext: String, names: String = "") =
        Type(Lang(scope, label, badge, color, lspIds[scope]), ext.split(' ').filter { it.isNotEmpty() }, names.split(' ').filter { it.isNotEmpty() })

    val PlainText = Lang(null, "Plain Text", "txt", 0xFF8A8A8A)

    private val lspIds = mapOf(
        "source.python" to "python", "source.shell" to "shellscript", "source.json" to "json",
        "source.json.comments" to "jsonc", "source.yaml" to "yaml", "source.toml" to "toml", "source.ini" to "ini",
        "text.html.markdown" to "markdown", "source.rst" to "restructuredtext", "text.tex.latex" to "latex",
        "text.tex" to "tex", "text.bibtex" to "bibtex", "text.html.derivative" to "html", "source.css" to "css",
        "source.css.scss" to "scss", "source.css.less" to "less", "source.js" to "javascript",
        "source.js.jsx" to "javascriptreact", "source.ts" to "typescript", "source.tsx" to "typescriptreact",
        "text.html.php" to "php", "text.xml" to "xml", "text.xml.xsl" to "xsl", "text.html.handlebars" to "handlebars",
        "text.pug" to "jade", "source.coffee" to "coffeescript", "source.c" to "c", "source.cpp" to "cpp",
        "source.cuda-cpp" to "cuda-cpp", "source.cs" to "csharp", "source.java" to "java", "source.kotlin" to "kotlin",
        "source.groovy" to "groovy", "source.go" to "go", "source.rust" to "rust", "source.swift" to "swift",
        "source.objc" to "objective-c", "source.objcpp" to "objective-cpp", "source.dart" to "dart",
        "source.fsharp" to "fsharp", "source.asp.vb.net" to "vb", "source.ruby" to "ruby", "source.perl" to "perl",
        "source.lua" to "lua", "source.r" to "r", "source.julia" to "julia", "source.clojure" to "clojure",
        "source.powershell" to "powershell", "source.batchfile" to "bat", "source.sql" to "sql",
        "source.makefile" to "makefile", "source.dockerfile" to "dockerfile",
    )

    private val types = listOf(
        type("source.python", "Python", "py", 0xFF4B8BBE, "py pyw pyi pyx rpy gyp gypi ipy", "SConstruct SConscript"),
        type("source.shell", "Shell", "sh", 0xFF89E051,
            "sh bash zsh ksh fish slurm sbatch pbs job ebuild",
            ".bashrc .bash_profile .bash_aliases .bash_login .bash_logout .profile .zshrc .zprofile .zshenv .envrc APKBUILD PKGBUILD"),
        type("source.json", "JSON", "{}", 0xFFCBCB41, "json ipynb webmanifest har geojson", ".watchmanconfig composer.lock"),
        type("source.json.comments", "JSON with Comments", "{}", 0xFFCBCB41, "jsonc json5 code-workspace",
            "tsconfig.json jsconfig.json .eslintrc .babelrc .swcrc devcontainer.json settings.json"),
        type("source.json.lines", "JSON Lines", "{}", 0xFFCBCB41, "jsonl ndjson"),
        type("source.yaml", "YAML", "yml", 0xFFE54D4D, "yml yaml cff", ".clang-format .clang-tidy"),
        type("source.toml", "TOML", "tml", 0xFFC0784A, "toml", "Cargo.lock Pipfile poetry.lock"),
        type("source.ini", "INI", "ini", 0xFF9DA5B4, "ini cfg conf properties desktop editorconfig",
            ".gitconfig .gitattributes .gitmodules .editorconfig .npmrc .env gitconfig"),
        type("text.html.markdown", "Markdown", "md", 0xFF519ABA, "md markdown mdown mkd mdx rmd qmd"),
        type("source.rst", "reStructuredText", "rst", 0xFF8A9BAE, "rst"),
        type("text.tex.latex", "LaTeX", "TeX", 0xFF3FA796, "tex ltx latex"),
        type("text.tex", "TeX", "TeX", 0xFF3FA796, "sty cls bbx cbx"),
        type("text.bibtex", "BibTeX", "bib", 0xFF3FA796, "bib"),
        type("source.diff", "Diff", "±", 0xFF9DA5B4, "diff patch rej"),
        type("text.log", "Log", "log", 0xFF8A8A8A, "log out err"),

        type("text.html.derivative", "HTML", "<>", 0xFFE44D26, "html htm xhtml shtml jsp asp aspx ejs"),
        type("source.css", "CSS", "#", 0xFF42A5F5, "css"),
        type("source.css.scss", "SCSS", "S", 0xFFCD6799, "scss"),
        type("source.css.less", "Less", "le", 0xFF5B8DD6, "less"),
        type("source.js", "JavaScript", "JS", 0xFFF1E05A, "js mjs cjs es6", "jakefile"),
        type("source.js.jsx", "JavaScript JSX", "jsx", 0xFF61DAFB, "jsx"),
        type("source.ts", "TypeScript", "TS", 0xFF3178C6, "ts mts cts"),
        type("source.tsx", "TypeScript JSX", "tsx", 0xFF3178C6, "tsx"),
        type("text.html.php", "PHP", "php", 0xFF8892BF, "php phtml php4 php5 ctp"),
        type("text.xml", "XML", "xml", 0xFFF08A24, "xml xsd svg plist csproj fsproj vbproj props targets wsdl rss atom xaml"),
        type("text.xml.xsl", "XSL", "xsl", 0xFFF08A24, "xsl xslt"),
        type("text.html.handlebars", "Handlebars", "hbs", 0xFFF0772B, "handlebars hbs mustache"),
        type("text.pug", "Pug", "pug", 0xFFA86454, "pug jade"),
        type("source.coffee", "CoffeeScript", "cof", 0xFF8B6F47, "coffee cson iced"),

        type("source.c", "C", "C", 0xFF659AD2, "c h i"),
        type("source.cpp", "C++", "C++", 0xFFF34B7D, "cpp cc cxx c++ cppm ixx hpp hh hxx h++ inl ipp tpp"),
        type("source.cuda-cpp", "CUDA C++", "cu", 0xFF76B900, "cu cuh"),
        type("source.cs", "C#", "C#", 0xFF9B4F96, "cs csx cake"),
        type("source.java", "Java", "jv", 0xFFE76F00, "java jav"),
        type("source.kotlin", "Kotlin", "kt", 0xFFA97BFF, "kt kts"),
        type("source.groovy", "Groovy", "gy", 0xFF4298B8, "groovy gvy gradle nf jenkinsfile", "Jenkinsfile"),
        type("source.go", "Go", "go", 0xFF00ADD8, "go", "go.mod go.sum go.work"),
        type("source.rust", "Rust", "rs", 0xFFDEA584, "rs"),
        type("source.swift", "Swift", "sw", 0xFFF05138, "swift"),
        type("source.objc", "Objective-C", "m", 0xFF438EFF, "m"),
        type("source.objcpp", "Objective-C++", "mm", 0xFF6866FB, "mm"),
        type("source.dart", "Dart", "dt", 0xFF00B4AB, "dart"),
        type("source.fsharp", "F#", "F#", 0xFFB845FC, "fs fsi fsx fsscript"),
        type("source.asp.vb.net", "Visual Basic", "vb", 0xFF945DB7, "vb vbs bas vba brs"),

        type("source.ruby", "Ruby", "rb", 0xFFCC342D, "rb rbx rake gemspec ru erb podspec rbi",
            "Gemfile Rakefile Podfile Guardfile Capfile Vagrantfile Brewfile"),
        type("source.perl", "Perl", "pl", 0xFF39A0C9, "pl pm pod t psgi"),
        type("source.lua", "Lua", "lua", 0xFF5A6AD8, "lua"),
        type("source.r", "R", "R", 0xFF2D7FDB, "r rprofile rhistory"),
        type("source.julia", "Julia", "jl", 0xFFA270BA, "jl"),
        type("source.clojure", "Clojure", "clj", 0xFF63B132, "clj cljs cljc cljx edn"),
        type("source.powershell", "PowerShell", "ps", 0xFF3A7BD5, "ps1 psm1 psd1"),
        type("source.batchfile", "Batch", "bat", 0xFFC1F12E, "bat cmd"),
        type("source.sql", "SQL", "sql", 0xFFE38C00, "sql dsql psql mysql pgsql"),

        type("source.makefile", "Makefile", "mk", 0xFF6D8086, "mk mak make", "Makefile makefile GNUmakefile"),
        type("source.dockerfile", "Dockerfile", "dk", 0xFF2496ED, "dockerfile containerfile", "Dockerfile Containerfile"),
        type("source.ignore", "Ignore", "ign", 0xFF6D8086, "gitignore dockerignore npmignore",
            ".gitignore .dockerignore .npmignore .hgignore .git-blame-ignore-revs"),
        type("text.git-commit", "Git Commit", "git", 0xFFF05032, "", "COMMIT_EDITMSG MERGE_MSG TAG_EDITMSG"),
        type("text.git-rebase", "Git Rebase", "git", 0xFFF05032, "", "git-rebase-todo"),
    )

    private val byName: Map<String, Lang> = types.flatMap { t -> t.names.map { it.lowercase() to t.lang } }.toMap()
    private val byExtension: Map<String, Lang> = types.flatMap { t -> t.extensions.map { it to t.lang } }.toMap()

    val all: List<Lang> get() = types.map { it.lang }

    fun forName(fileName: String): Lang {
        val lower = fileName.lowercase()
        byName[lower]?.let { return it }
        if (lower.startsWith("dockerfile")) return byName.getValue("dockerfile")
        var dot = lower.indexOf('.', 1)
        while (dot in 1 until lower.length - 1) {
            byExtension[lower.substring(dot + 1)]?.let { return it }
            dot = lower.indexOf('.', dot + 1)
        }
        return PlainText
    }

    enum class Viewer { Image, Pdf }

    fun viewer(fileName: String): Viewer? = when (fileName.lowercase().substringAfterLast('.', "")) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp" -> Viewer.Image
        "pdf" -> Viewer.Pdf
        else -> null
    }

    enum class RunKind { Python, Shell, Slurm }

    fun runKind(fileName: String): RunKind? {
        val ext = fileName.lowercase().substringAfterLast('.', "")
        return when (ext) {
            "py" -> RunKind.Python
            "sh", "bash" -> RunKind.Shell
            "slurm", "sbatch", "job" -> RunKind.Slurm
            else -> null
        }
    }
}
