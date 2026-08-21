package app.ptrip.tracktrip.ui

import android.os.PatternMatcher
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That a tapped invite link reaches this app, on both builds.
 *
 * Three files have to agree and none of them can see the others: the manifest
 * claims a path shape, [joinWebLinkFor] writes links, and the assetlinks.json
 * served from ptrip.app is what turns the claim into a *verified* App Link
 * rather than a chooser. A change to any one of them is silent — the link still
 * opens a browser, which looks like the site simply not being finished.
 *
 * Nothing here talks to the network. The pattern is matched with the same
 * [PatternMatcher] Android uses to route the intent, and the served file is
 * checked as a file in the repository, since that is what gets deployed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLinkTest {

    private val manifest = repoFile("android/app/src/main/AndroidManifest.xml").readText()
    private val gradle = repoFile("android/app/build.gradle.kts").readText()

    /** The `<data>` element of the https filter, as written. */
    private val webData = Regex("""<data android:scheme="https"[^>]*>""")
        .find(manifest)
        ?.value
        .orEmpty()

    @Test
    fun `the https filter claims the invite path and asks to be verified`() {
        val filter = Regex(
            """<intent-filter android:autoVerify="true">.*?</intent-filter>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifest)?.value

        assertTrue("no autoVerify intent-filter in the manifest", filter != null)
        requireNotNull(filter)

        // BROWSABLE is what lets a link in another app start this one; DEFAULT
        // is what lets an implicit VIEW resolve here at all. Missing either
        // makes the filter dead without making the build fail.
        assertTrue(filter, filter.contains("android.intent.category.BROWSABLE"))
        assertTrue(filter, filter.contains("android.intent.category.DEFAULT"))
        assertTrue(filter, filter.contains("android.intent.action.VIEW"))
        assertTrue(filter, filter.contains("""android:scheme="https""""))
        assertTrue(filter, filter.contains("""android:host="ptrip.app""""))
        assertTrue(filter, filter.contains("""android:pathPattern="/join/.*""""))
    }

    @Test
    fun `a prefix is not used, because it would claim more of the site than this`() {
        // pathPrefix="/join" also claims /joining, /joinus and the bare /join.
        // The domain is the product's own, so anything the site wants that word
        // for later would open the app instead of the page.
        assertFalse(webData, webData.contains("pathPrefix"))
    }

    @Test
    fun `the pattern matches the links the app writes`() {
        val pattern = Regex("""android:pathPattern="([^"]+)"""").find(webData)?.groupValues?.get(1)
        assertTrue("no pathPattern in: $webData", pattern != null)
        val matcher = PatternMatcher(requireNotNull(pattern), PatternMatcher.PATTERN_SIMPLE_GLOB)

        // The path of what joinWebLinkFor produces, for a code of the shape the
        // server issues.
        val link = joinWebLinkFor("K7M2QRX9")
        assertEquals("https://ptrip.app/join/K7M2QRX9", link)
        assertTrue(link, matcher.match(link.removePrefix("https://ptrip.app")))
    }

    @Test
    fun `the pattern does not claim the rest of the site`() {
        val pattern = requireNotNull(
            Regex("""android:pathPattern="([^"]+)"""").find(webData)?.groupValues?.get(1)
        )
        val matcher = PatternMatcher(pattern, PatternMatcher.PATTERN_SIMPLE_GLOB)

        listOf("/", "/about", "/join", "/joining", "/joinus/K7M2QRX9").forEach {
            assertFalse("$it should not be claimed", matcher.match(it))
        }
    }

    @Test
    fun `the served assetlinks names both builds`() {
        // Verification is per package name and per signing certificate. The
        // filter lives in the shared manifest, so the debug build claims the
        // same links under its own id — and a file that lists only the release
        // id leaves debug falling back to a chooser, on the very builds used
        // for testing this.
        val declared = JSONArray(repoFile("deploy/assetlinks.json").readText())
        val packages = (0 until declared.length()).map {
            declared.getJSONObject(it).getJSONObject("target").getString("package_name")
        }

        val applicationId = requireNotNull(
            Regex("""applicationId = "([^"]+)"""").find(gradle)?.groupValues?.get(1)
        )
        val debugSuffix = requireNotNull(
            Regex("""applicationIdSuffix = "([^"]+)"""").find(gradle)?.groupValues?.get(1)
        )

        assertEquals(
            listOf(applicationId, applicationId + debugSuffix).sorted(),
            packages.sorted(),
        )
    }

    @Test
    fun `every assetlinks entry delegates url handling and carries a fingerprint`() {
        val declared = JSONArray(repoFile("deploy/assetlinks.json").readText())
        assertTrue("assetlinks.json is empty", declared.length() > 0)

        (0 until declared.length()).forEach { i ->
            val entry = declared.getJSONObject(i)
            val relations = entry.getJSONArray("relation")
            assertEquals(
                "entry $i has the wrong relation",
                "delegate_permission/common.handle_all_urls",
                relations.getString(0),
            )
            val target = entry.getJSONObject("target")
            assertEquals("android_app", target.getString("namespace"))

            val fingerprints = target.getJSONArray("sha256_cert_fingerprints")
            assertTrue("entry $i has no fingerprint", fingerprints.length() > 0)

            (0 until fingerprints.length()).forEach { j ->
                val fingerprint = fingerprints.getString(j)
                // The statement-list spec asks for the *uppercase* SHA-256
                // fingerprint written as colon-separated pairs of hex — 32
                // bytes, so 32 pairs. A SHA-1 is 20 and looks close enough to
                // be pasted in by mistake, since keystore-sha1.sh prints both
                // one line apart.
                assertTrue(
                    "${target.getString("package_name")} has a fingerprint that is not " +
                        "32 uppercase colon-separated hex pairs: $fingerprint",
                    FINGERPRINT.matches(fingerprint),
                )
            }
        }
    }

    @Test
    fun `no placeholder fingerprint survives`() {
        // The file carried REPLACE_WITH_* until the real ones arrived. A
        // placeholder deployed by accident is not dangerous — verification
        // fails and Android falls back to a chooser — but it fails quietly,
        // which is the failure mode this whole test class exists for.
        val raw = repoFile("deploy/assetlinks.json").readText()

        assertFalse(raw, Regex("REPLACE|PLACEHOLDER|TODO", RegexOption.IGNORE_CASE).containsMatchIn(raw))
    }

    private companion object {
        /** 32 bytes as uppercase hex pairs, colon separated. */
        val FINGERPRINT = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){31}")

        /**
         * Found by walking up to the repository root rather than from a path
         * relative to the runner, which Gradle and an IDE disagree about. The
         * files being read span the Android module and the deploy directory,
         * so the root is the only place both are visible from.
         */
        fun repoFile(path: String): File {
            var dir: File? = File(System.getProperty("user.dir"))
            while (dir != null) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            throw IllegalStateException("could not find $path from ${System.getProperty("user.dir")}")
        }
    }
}
