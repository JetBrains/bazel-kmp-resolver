"""Unit tests of `rules/facts.bzl`: the resolution fact key must change with everything that changes
the stored resolution, and nothing else."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(
    "//rules:facts.bzl",
    "EMPTY_RESOLUTION_JSON",
    "MANIFEST_SCHEMA_VERSION",
    "RESOLUTION_FACTS_SCHEMA",
    "resolution_fact_key",
)

def _install(**overrides):
    attributes = dict(
        name = "kmp_deps",
        artifacts = ["org.example:lib:1.0"],
        platforms = ["jvm"],
        repositories = ["https://example.com/maven"],
        substitutions = {},
        npm_package_version_overrides = {},
        npm_registry = "",
    )
    attributes.update(overrides)
    return struct(**attributes)

def _determinism_test_impl(ctx):
    env = unittest.begin(ctx)

    # Same inputs, same key: platforms are normalized sorted, dictionaries keyed sorted.
    first = resolution_fact_key(_install(platforms = ["jvm", "android"]), "sha")
    second = resolution_fact_key(_install(platforms = ["android", "jvm"]), "sha")
    asserts.equals(env, first, second)

    # The key embeds its schema constants.
    asserts.true(env, RESOLUTION_FACTS_SCHEMA in first, "the fact key carries the schema")
    asserts.true(env, MANIFEST_SCHEMA_VERSION in first, "the fact key carries the manifest schema")

    return unittest.end(env)

def _invalidation_test_impl(ctx):
    env = unittest.begin(ctx)

    base = resolution_fact_key(_install(), "sha-1")

    # A resolver binary change re-resolves.
    asserts.true(env, base != resolution_fact_key(_install(), "sha-2"), "the resolver sha256 is in the key")

    # A dependency definition change re-resolves.
    asserts.true(env, base != resolution_fact_key(_install(artifacts = ["org.example:lib:2.0"]), "sha-1"), "the artifacts are in the key")
    asserts.true(env, base != resolution_fact_key(_install(platforms = ["jvm", "wasmjs"]), "sha-1"), "the platforms are in the key")
    asserts.true(env, base != resolution_fact_key(_install(repositories = ["https://example.com/maven", "https://example.com/other"]), "sha-1"), "the repositories are in the key")
    asserts.true(env, base != resolution_fact_key(_install(substitutions = {"org.example:lib": "org.example:lib-shim:1.0"}), "sha-1"), "the substitutions are in the key")
    asserts.true(env, base != resolution_fact_key(_install(npm_package_version_overrides = {"x": "1.0"}), "sha-1"), "the npm overrides are in the key")
    asserts.true(env, base != resolution_fact_key(_install(npm_registry = "https://npm.example.com"), "sha-1"), "the npm registry is in the key")

    # Another install resolves under its own key.
    asserts.true(env, base != resolution_fact_key(_install(name = "other_deps"), "sha-1"), "the install name is in the key")

    # The hermetic Node.js of a `wasmjs` resolution is in the key.
    wasmjs = resolution_fact_key(_install(platforms = ["wasmjs"]), "sha-1", "v22.22.2")
    asserts.true(env, wasmjs != resolution_fact_key(_install(platforms = ["wasmjs"]), "sha-1", "v24.1.0"), "the node version is in the key")

    return unittest.end(env)

def _empty_resolution_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.equals(
        env,
        {"askedCoordinates": [], "askedRepositories": [], "libraries": {}},
        json.decode(EMPTY_RESOLUTION_JSON),
    )

    return unittest.end(env)

determinism_test = unittest.make(_determinism_test_impl)
invalidation_test = unittest.make(_invalidation_test_impl)
empty_resolution_test = unittest.make(_empty_resolution_test_impl)

def facts_test_suite(name):
    unittest.suite(
        name,
        determinism_test,
        invalidation_test,
        empty_resolution_test,
    )
