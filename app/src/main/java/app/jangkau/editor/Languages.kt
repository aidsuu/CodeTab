package app.jangkau.editor

object Languages {
    data class Lang(val scope: String?, val label: String)

    private val shellNames = setOf(".bashrc", ".bash_profile", ".profile", ".zshrc", ".bash_aliases", "Makefile.sh")

    fun forName(fileName: String): Lang {
        val lower = fileName.lowercase()
        if (lower in shellNames) return Lang("source.shell", "Shell")
        val dot = lower.lastIndexOf('.')
        val ext = if (dot > 0) lower.substring(dot + 1) else ""
        return when (ext) {
            "py", "pyw", "pyi" -> Lang("source.python", "Python")
            "sh", "bash", "zsh", "slurm", "sbatch", "pbs", "job" -> Lang("source.shell", "Shell")
            "json", "ipynb" -> Lang("source.json", "JSON")
            "yml", "yaml" -> Lang("source.yaml", "YAML")
            "c", "h" -> Lang("source.c", "C")
            "md", "markdown" -> Lang("text.html.markdown", "Markdown")
            "diff", "patch" -> Lang("source.diff", "Diff")
            "txt", "log", "out", "err" -> Lang(null, "Plain Text")
            else -> Lang(null, "Plain Text")
        }
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
