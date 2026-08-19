package app.ptrip.tracktrip.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the Thai translation covers the app.
 *
 * Android falls back per *string*, not per file, so a key nobody translated
 * shows English on a Thai phone with no warning anywhere — no crash, no lint,
 * nothing in the build. That is how the language switch came to look like a
 * stub that did nothing: it always worked, and most of what it switched had
 * no Thai to switch to.
 *
 * A test rather than a promise to remember. Adding an English string without
 * its Thai now fails here, on a laptop, in the same change that added it.
 */
class StringCoverageTest {

    private val english = parse(resFile("values/strings.xml"))
    private val thai = parse(resFile("values-th/strings.xml"))

    @Test
    fun `every translatable string has a Thai translation`() {
        val missing = (english.translatable - thai.all).sorted()

        assertEquals("no Thai for: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `the Thai file has nothing English does not`() {
        // A key that exists only here is dead weight: nothing looks it up, and
        // it usually means a rename landed on one side only.
        val orphans = (thai.all - english.all).sorted()

        assertEquals("only in Thai: $orphans", emptyList<String>(), orphans)
    }

    @Test
    fun `the app's own name is not translated`() {
        // Marked translatable="false" on purpose: a launcher icon whose
        // caption changes with the phone's language is, to the person looking
        // at it, a different app.
        assertTrue("app_name" in english.all)
        assertTrue("app_name" !in english.translatable)
        assertTrue("app_name" !in thai.all)
    }

    @Test
    fun `format strings keep their arguments in both languages`() {
        // A translation that drops a %1$s throws IllegalFormatException at the
        // moment the screen draws — on the phone, in the other language, which
        // is the hardest place to see it.
        english.arguments.forEach { (name, expected) ->
            val translated = thai.arguments[name] ?: return@forEach
            assertEquals("$name has different arguments in Thai", expected, translated)
        }
    }

    private data class Strings(
        val all: Set<String>,
        val translatable: Set<String>,
        val arguments: Map<String, Set<String>>,
    )

    private companion object {
        /** e.g. `<string name="x" translatable="false">…</string>` up to its close tag. */
        val ENTRY = Regex("""<string\s+name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        /** `%1$s`, `%2$d` — the placeholders a `getString` call has to fill. */
        val ARGUMENT = Regex("""%\d+\$[a-zA-Z]""")

        fun parse(file: File): Strings {
            val entries = ENTRY.findAll(file.readText()).map { match ->
                val (name, attributes, body) = match.destructured
                Triple(name, !attributes.contains("translatable=\"false\""), body)
            }.toList()

            return Strings(
                all = entries.map { it.first }.toSet(),
                translatable = entries.filter { it.second }.map { it.first }.toSet(),
                arguments = entries.associate { (name, _, body) ->
                    name to ARGUMENT.findAll(body).map { it.value }.toSet()
                },
            )
        }

        /**
         * Resources are found by walking up from wherever the test runner
         * started, rather than from a path relative to it: Gradle and an IDE
         * disagree about that, and a test that only passes under one of them
         * is a test people learn to ignore.
         */
        fun resFile(path: String): File {
            var dir: File? = File(System.getProperty("user.dir"))
            while (dir != null) {
                val candidate = File(dir, "src/main/res/$path")
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            throw IllegalStateException("could not find src/main/res/$path from ${System.getProperty("user.dir")}")
        }
    }
}
