package app.jangkau

import androidx.test.core.app.ApplicationProvider
import app.jangkau.editor.EditorSupport
import app.jangkau.editor.Languages
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GrammarTest {
    private val samples = mapOf(
        "source.python" to "def f(x: int) -> str: return f\"{x}\"  # note",
        "source.js" to "const f = async (a) => { return `\${a}` } // note",
        "source.ts" to "export interface A<T> { value: T }",
        "text.html.derivative" to "<div class=\"a\"><script>let x = 1</script></div>",
        "text.html.php" to "<p><?php echo \$name; ?></p>",
        "source.rust" to "fn main() { let v: Vec<u8> = vec![1]; }",
        "source.kotlin" to "fun main() { val x: Int = 1 }",
        "source.toml" to "[package]\nname = \"codetab\"",
        "text.html.markdown" to "```python\nprint(1)\n```",
    )

    @Test fun everyFileTypeHasABundledGrammar() {
        val json = JSONObject(File("src/main/assets/textmate/languages.json").readText()).getJSONArray("languages")
        val scopes = (0 until json.length()).map { json.getJSONObject(it).getString("scopeName") }.toSet()
        val missing = Languages.all.mapNotNull { it.scope }.filter { it !in scopes }
        assertEquals(emptyList<String>(), missing)
        assertTrue(Languages.all.size >= 55)
    }

    @Test fun fileNamesMapToLanguages() {
        assertEquals("TypeScript", Languages.forName("index.d.ts").label)
        assertEquals("JavaScript", Languages.forName("app.test.js").label)
        assertEquals("Dockerfile", Languages.forName("Dockerfile.dev").label)
        assertEquals("Makefile", Languages.forName("Makefile").label)
        assertEquals("Ruby", Languages.forName("Gemfile").label)
        assertEquals("Shell", Languages.forName("job.SLURM").label)
        assertEquals("Ignore", Languages.forName(".gitignore").label)
        assertEquals("Plain Text", Languages.forName("notes.txt").label)
        assertEquals("Plain Text", Languages.forName("README").label)
    }

    @Test fun everyRegexCompilesWithJoni() {
        val failures = mutableListOf<String>()
        fun walk(node: Any?, file: String) {
            when (node) {
                is JSONObject -> node.keys().forEach { key ->
                    val v = node.get(key)
                    if (key in setOf("match", "begin", "end", "while") && v is String) {
                        val pattern = if (key == "end" || key == "while") v.replace(Regex("""\\(\d)"""), "x") else v
                        runCatching { org.eclipse.tm4e.core.internal.oniguruma.OnigRegExp(pattern) }
                            .onFailure { failures += "$file: $v" }
                    } else walk(v, file)
                }
                is org.json.JSONArray -> for (i in 0 until node.length()) walk(node.get(i), file)
            }
        }
        File("src/main/assets/textmate/grammars").listFiles()!!.sorted().forEach { f -> walk(JSONObject(f.readText()), f.name) }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test fun everyGrammarLoadsAndTokenizes() {
        val started = System.nanoTime()
        EditorSupport.init(ApplicationProvider.getApplicationContext())
        val loadMs = (System.nanoTime() - started) / 1_000_000
        println("grammar registry loaded in $loadMs ms")
        for (lang in Languages.all) {
            val scope = lang.scope ?: continue
            val grammar = GrammarRegistry.getInstance().findGrammar(scope)
            assertNotNull("grammar for $scope", grammar)
            val line = samples[scope] ?: "example text 123 \"string\" // comment"
            var state = grammar!!.tokenizeLine(line.lines().first())
            line.lines().drop(1).forEach { state = grammar.tokenizeLine(it, state.ruleStack, null) }
            assertTrue(state.tokens.isNotEmpty())
        }
    }
}
