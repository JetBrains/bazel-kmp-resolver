package org.jetbrains.kmp.resolver.auth

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CredentialHelperOptionTest {
    @Test
    fun `parses an unscoped helper`() {
        assertEquals(
            CredentialHelperOption(scope = null, path = "/usr/bin/helper"),
            CredentialHelperOption.parse("/usr/bin/helper"),
        )
        assertEquals(
            CredentialHelperOption(scope = null, path = "helper"),
            CredentialHelperOption.parse("helper"),
        )
        assertEquals(
            CredentialHelperOption(scope = null, path = "C:\\tools\\helper.exe"),
            CredentialHelperOption.parse("C:\\tools\\helper.exe"),
        )
    }

    @Test
    fun `parses a scoped helper`() {
        assertEquals(
            CredentialHelperOption(
                scope = CredentialHelperScope(host = "example.com", includesSubdomains = false),
                path = "/bin/h",
            ),
            CredentialHelperOption.parse("Example.COM=/bin/h"),
        )
        assertEquals(
            CredentialHelperOption(
                scope = CredentialHelperScope(host = "example.com", includesSubdomains = true),
                path = "/bin/h=x",
            ),
            CredentialHelperOption.parse("*.example.com=/bin/h=x"),
        )
    }

    @Test
    fun `rejects empty parts and malformed scopes`() {
        assertFailsWith<IllegalArgumentException> { CredentialHelperOption.parse("") }
        assertFailsWith<IllegalArgumentException> { CredentialHelperOption.parse("=/bin/h") }
        assertFailsWith<IllegalArgumentException> { CredentialHelperOption.parse("example.com=") }
        assertFailsWith<IllegalArgumentException> { CredentialHelperOption.parse("*.=/bin/h") }
        assertFailsWith<IllegalArgumentException> {
            CredentialHelperOption.parse("foo.*.com=/bin/h")
        }
    }

    private fun provider(vararg options: String) =
        CredentialHelperProvider(options.map { CredentialHelperOption.parse(it) })

    @Test
    fun `picks the most specific scope`() {
        val provider =
            provider("any", "*.example.com=wildcard", "example.com=exact", "*.sub.example.com=sub")

        assertEquals("exact", provider.find(URI("https://example.com/a"))?.path)
        assertEquals("exact", provider.find(URI("https://EXAMPLE.com/a"))?.path)
        assertEquals("wildcard", provider.find(URI("https://foo.example.com/a"))?.path)
        assertEquals("sub", provider.find(URI("https://sub.example.com/a"))?.path)
        assertEquals("sub", provider.find(URI("https://deep.sub.example.com/a"))?.path)
        assertEquals("any", provider.find(URI("https://other.org/a"))?.path)
        assertEquals("any", provider.find(URI("https://notexample.com/a"))?.path)
    }

    @Test
    fun `the last helper of a scope wins`() {
        assertEquals(
            "second",
            provider("example.com=first", "example.com=second")
                .find(URI("https://example.com/"))
                ?.path,
        )
        assertEquals("second", provider("first", "second").find(URI("https://example.com/"))?.path)
    }

    @Test
    fun `no helper applies without a match`() {
        assertNull(provider("example.com=exact").find(URI("https://other.org/")))
        assertNull(provider("any").find(URI("file:///tmp/a")))
        assertNull(CredentialHelperProvider(emptyList()).find(URI("https://example.com/")))
    }
}
