package org.jetbrains.kmp.resolver

import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class CredentialHelperTest {
    @Test
    fun `parses the pattern of a helper on the left-most equals sign`() {
        assertEquals(
            CredentialHelper(CredentialHelperScope.AnyHost, "/usr/bin/helper"),
            parseCredentialHelper("/usr/bin/helper"),
        )
        assertEquals(
            CredentialHelper(CredentialHelperScope.Exact("example.com"), "/usr/bin/helper"),
            parseCredentialHelper("example.com=/usr/bin/helper"),
        )
        assertEquals(
            CredentialHelper(CredentialHelperScope.Subdomains("example.com"), "%workspace%/tools/helper.sh"),
            parseCredentialHelper("*.example.com=%workspace%/tools/helper.sh"),
        )
        // only the left-most `=` separates, the rest belongs to the path
        assertEquals(
            CredentialHelper(CredentialHelperScope.Exact("example.com"), "/usr/bin/helper=x"),
            parseCredentialHelper("example.com=/usr/bin/helper=x"),
        )
        assertFailsWith<IllegalArgumentException> { parseCredentialHelper("example.com=") }
    }

    private fun provider(vararg helpers: String) = CredentialHelperProvider(
        helpers = helpers.map { parseCredentialHelper(it) },
        workspaceDirectory = createTempDirectory("workspace"),
        timeout = 10.seconds,
    )

    @Test
    fun `the most specific helper wins, as in the bazel specification`() {
        // the worked example of the design doc:
        //   --credential_helper=foo --credential_helper=*.example.com=bar --credential_helper=example.com=baz
        val provider = provider("foo", "*.example.com=bar", "example.com=baz")

        assertEquals("baz", provider.helperFor("example.com"))
        assertEquals("bar", provider.helperFor("a.example.com"))
        assertEquals("bar", provider.helperFor("x.y.z.example.com"))
        assertEquals("foo", provider.helperFor("other.com"))
        assertEquals("foo", provider.helperFor("notexample.com"))
    }

    @Test
    fun `the longest wildcard wins and there is no implicit default helper`() {
        val nested = provider("*.example.com=outer", "*.eu.example.com=inner")
        assertEquals("inner", nested.helperFor("a.eu.example.com"))
        assertEquals("outer", nested.helperFor("a.us.example.com"))

        assertNull(provider("example.com=baz").helperFor("other.com"))
    }

    @Test
    fun `a later helper overrides an earlier one declaring the same pattern`() {
        assertEquals("second", provider("example.com=first", "example.com=second").helperFor("example.com"))
    }

    /**
     * Writes a credential helper implemented with the hermetic Node.js distribution the tests are provisioned
     * with, so that the fixtures never depend on a shell being available.
     */
    private fun helperScript(workspace: java.nio.file.Path, body: String): String {
        val script = workspace / "helper.js"
        script.writeText(body)
        val launcher = workspace / "helper.sh"
        launcher.writeText("#!/bin/sh\nexec '${NodeDistribution.node}' '$script' \"$@\"\n")
        launcher.toFile().setExecutable(true)
        return launcher.toString()
    }

    private fun runHelper(body: String, uri: String = "https://repo.example.com/maven2"): RepositoryCredentials? {
        val workspace = createTempDirectory("workspace")
        val provider = CredentialHelperProvider(
            helpers = listOf(parseCredentialHelper(helperScript(workspace, body))),
            workspaceDirectory = workspace,
            timeout = 30.seconds,
        )
        return runBlocking { provider.credentialsFor(listOf(uri)) }[uri]
    }

    @Test
    fun `speaks the protocol on the standard input and output of the helper`() {
        val credentials = runHelper(
            """
            const chunks = [];
            process.stdin.on("data", (c) => chunks.push(c));
            process.stdin.on("end", () => {
              if (process.argv[2] !== "get") { process.exit(2); }
              const request = JSON.parse(chunks.join(""));
              process.stdout.write(JSON.stringify({
                headers: { Authorization: ["Bearer token-for-" + request.uri] },
                expires: "2026-12-01T00:00:00Z",
              }));
            });
            """.trimIndent(),
        )

        assertEquals(
            mapOf("Authorization" to listOf("Bearer token-for-https://repo.example.com/maven2")),
            credentials?.headers,
        )
    }

    @Test
    fun `an empty set of headers means no credentials are needed`() {
        assertNull(runHelper("""process.stdin.resume(); process.stdin.on("end", () => process.stdout.write("{}"));"""))
    }

    @Test
    fun `a failing helper surfaces its own message`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            // a helper that fails before reading its stdin is the documented way of asking the user to log in
            runHelper("""process.stderr.write("run `example login` first\n"); process.exit(7);""")
        }

        assertEquals(true, failure.message.orEmpty().contains("exit code 7"), failure.message)
        assertEquals(true, failure.message.orEmpty().contains("run `example login` first"), failure.message)
    }

    @Test
    fun `a helper returning something else than the protocol fails`() {
        assertFailsWith<IllegalStateException> {
            runHelper("""process.stdin.resume(); process.stdin.on("end", () => process.stdout.write("not json"));""")
        }
    }

    @Test
    fun `resolves helper paths the way bazel does`() {
        val workspace = createTempDirectory("workspace")
        assertEquals(
            workspace / "tools" / "helper.sh",
            resolveCredentialHelperPath("%workspace%/tools/helper.sh", workspace),
        )
        assertEquals(
            workspace / "tools" / "helper.sh",
            resolveCredentialHelperPath("tools/helper.sh", workspace),
        )
        assertEquals(
            java.nio.file.Path.of("/opt/helper"),
            resolveCredentialHelperPath("/opt/helper", workspace),
        )
        // a bare name is looked up on PATH, and reported clearly when it is not there
        assertFailsWith<IllegalArgumentException> {
            resolveCredentialHelperPath("definitely-not-on-path-kmp", workspace)
        }
    }
}
