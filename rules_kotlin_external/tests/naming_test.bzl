"""Unit tests of `rules/naming.bzl`."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(
    "//rules:naming.bzl",
    "artifact_classifier",
    "basename_from_url",
    "build_target_name",
    "maven_coordinate_parts",
    "maven_module_id",
    "repository_name_part",
    "versionless_target_name",
)

def _build_target_name_test_impl(ctx):
    env = unittest.begin(ctx)

    # Lowercase, every non-alphanumeric character replaced by one `_`.
    asserts.equals(
        env,
        "io_ktor_ktor_client_core_jvm_3_6_0",
        build_target_name("io.ktor:ktor-client-core-jvm:3.6.0"),
    )
    asserts.equals(
        env,
        "org_jetbrains_kotlin_kotlin_stdlib",
        build_target_name("org.jetbrains.kotlin:kotlin-stdlib"),
    )

    # Consecutive separators collapse into one `_`, leading and trailing ones are stripped.
    asserts.equals(env, "a_b_c", build_target_name("a..b--c"))

    return unittest.end(env)

def _versionless_target_name_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.equals(
        env,
        "io_ktor_ktor_client_core",
        versionless_target_name("io.ktor:ktor-client-core:3.6.0"),
    )
    asserts.equals(
        env,
        "io_ktor_ktor_client_core",
        versionless_target_name("io.ktor:ktor-client-core"),
    )

    return unittest.end(env)

def _maven_module_id_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.equals(env, "io.ktor:ktor-client-core", maven_module_id("io.ktor:ktor-client-core:3.6.0"))
    asserts.equals(env, "io.ktor:ktor-client-core", maven_module_id("io.ktor:ktor-client-core"))

    return unittest.end(env)

def _maven_coordinate_parts_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.equals(
        env,
        ["io.ktor", "ktor-client-core", "3.6.0"],
        maven_coordinate_parts("io.ktor:ktor-client-core:3.6.0"),
    )

    return unittest.end(env)

def _repository_name_part_test_impl(ctx):
    env = unittest.begin(ctx)

    # Case kept, dots and dashes replaced by `_`.
    asserts.equals(env, "io_ktor", repository_name_part("io.ktor"))
    asserts.equals(env, "AndroidX_annotation", repository_name_part("AndroidX.annotation"))
    asserts.equals(env, "js_joda_time", repository_name_part("js-joda-time"))

    return unittest.end(env)

def _basename_from_url_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.equals(env, "lib-jvm-1.0.jar", basename_from_url("https://example.com/maven/lib-jvm-1.0.jar"))
    asserts.equals(env, "lib-jvm-1.0.jar", basename_from_url("https://example.com/maven/lib-jvm-1.0.jar?query=1#fragment"))

    return unittest.end(env)

def _artifact_classifier_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.equals(
        env,
        "",
        artifact_classifier("ktor-client-core-jvm-3.6.0.jar", "ktor-client-core-jvm", "3.6.0"),
    )
    asserts.equals(
        env,
        "sources",
        artifact_classifier("ktor-client-core-jvm-3.6.0-sources.jar", "ktor-client-core-jvm", "3.6.0"),
    )
    asserts.equals(
        env,
        "samples-sources",
        artifact_classifier("ktor-client-core-jvm-3.6.0-samples-sources.jar", "ktor-client-core-jvm", "3.6.0"),
    )

    return unittest.end(env)

build_target_name_test = unittest.make(_build_target_name_test_impl)
versionless_target_name_test = unittest.make(_versionless_target_name_test_impl)
maven_module_id_test = unittest.make(_maven_module_id_test_impl)
maven_coordinate_parts_test = unittest.make(_maven_coordinate_parts_test_impl)
repository_name_part_test = unittest.make(_repository_name_part_test_impl)
basename_from_url_test = unittest.make(_basename_from_url_test_impl)
artifact_classifier_test = unittest.make(_artifact_classifier_test_impl)

def naming_test_suite(name):
    unittest.suite(
        name,
        build_target_name_test,
        versionless_target_name_test,
        maven_module_id_test,
        maven_coordinate_parts_test,
        repository_name_part_test,
        basename_from_url_test,
        artifact_classifier_test,
    )
