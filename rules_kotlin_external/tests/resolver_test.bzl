"""Unit tests of the pure parts of `rules/resolver.bzl` and `rules/repositories.bzl`."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(
    "//rules:repositories.bzl",
    "artifact_label",
    "artifact_repository_name",
    "npm_label",
    "npm_repository_name",
)
load(
    "//rules:resolver.bzl",
    "duration_to_millis",
    "host_key",
)

def _host_key_test_impl(ctx):
    env = unittest.begin(ctx)

    def host_of(os_name, arch):
        return host_key(struct(os = struct(name = os_name, arch = arch)))

    asserts.equals(env, "macos_arm64", host_of("mac os x", "aarch64"))
    asserts.equals(env, "macos_x64", host_of("Darwin", "x86_64"))
    asserts.equals(env, "linux_x64", host_of("linux", "amd64"))
    asserts.equals(env, "linux_arm64", host_of("Linux", "arm64"))
    asserts.equals(env, "windows_x64", host_of("windows", "x86_64"))
    asserts.equals(env, "windows_arm64", host_of("Windows Server", "arm64"))

    return unittest.end(env)

def _duration_to_millis_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.equals(env, 500, duration_to_millis("500ms", "test"))
    asserts.equals(env, 10000, duration_to_millis("10s", "test"))
    asserts.equals(env, 1800000, duration_to_millis("30m", "test"))
    asserts.equals(env, 3600000, duration_to_millis("1h", "test"))
    asserts.equals(env, 86400000, duration_to_millis("1d", "test"))
    asserts.equals(env, 0, duration_to_millis("0", "test"))

    return unittest.end(env)

def _artifact_repository_name_test_impl(ctx):
    env = unittest.begin(ctx)

    jar = {"groupId": "io.ktor", "artifactId": "ktor-client-core-jvm", "version": "3.6.0", "urls": ["https://example.com/ktor-client-core-jvm-3.6.0.jar"]}
    sources = {"groupId": "io.ktor", "artifactId": "ktor-client-core-jvm", "version": "3.6.0", "urls": ["https://example.com/ktor-client-core-jvm-3.6.0-sources.jar"]}

    asserts.equals(
        env,
        "kmp_deps_io_ktor_ktor_client_core_jvm_3_6_0_http",
        artifact_repository_name("kmp_deps", jar),
    )
    asserts.equals(
        env,
        "kmp_deps_io_ktor_ktor_client_core_jvm_3_6_0_sources_http",
        artifact_repository_name("kmp_deps", sources),
    )
    asserts.equals(
        env,
        "@kmp_deps_io_ktor_ktor_client_core_jvm_3_6_0_http//file:file",
        artifact_label("kmp_deps", jar),
    )

    return unittest.end(env)

def _npm_repository_name_test_impl(ctx):
    env = unittest.begin(ctx)

    package = {"name": "js-joda-time", "version": "3.2.0"}
    asserts.equals(
        env,
        "kmp_deps_npm_js_joda_time_3_2_0_http",
        npm_repository_name("kmp_deps", package),
    )
    asserts.equals(
        env,
        "@kmp_deps_npm_js_joda_time_3_2_0_http//:package",
        npm_label("kmp_deps", package),
    )

    return unittest.end(env)

host_key_test = unittest.make(_host_key_test_impl)
duration_to_millis_test = unittest.make(_duration_to_millis_test_impl)
artifact_repository_name_test = unittest.make(_artifact_repository_name_test_impl)
npm_repository_name_test = unittest.make(_npm_repository_name_test_impl)

def resolver_test_suite(name):
    unittest.suite(
        name,
        host_key_test,
        duration_to_millis_test,
        artifact_repository_name_test,
        npm_repository_name_test,
    )
