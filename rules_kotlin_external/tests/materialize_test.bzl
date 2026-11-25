"""Unit tests of `rules/materialize.bzl`: a manifest is materialized per platform and rendered into
the BUILD file of the hub repository, exercising the whole rendering pipeline without running the
resolver."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load("//rules:manifest.bzl", "decode")
load(
    "//rules:materialize.bzl",
    "deps_repository_name",
    "materialize_resolution",
    "render_build_file",
)

_MANIFEST_JSON = """
{
  "askedCoordinates": ["org.example:lib:1.0"],
  "askedRepositories": ["https://example.com/maven"],
  "libraries": {
    "org.example:lib:1.0": {
      "id": "org.example:lib:1.0",
      "variants": [
        {
          "platform": "jvm",
          "id": "org.example:lib:1.0",
          "variantId": "org.example:lib-jvm:1.0",
          "dependencies": ["org.example:dep"],
          "exportedDependencies": [],
          "artifacts": [
            {
              "type": "ClasspathJar",
              "integrity": "sha256-jar",
              "groupId": "org.example",
              "artifactId": "lib-jvm",
              "version": "1.0",
              "urls": ["https://example.com/maven/org/example/lib-jvm/1.0/lib-jvm-1.0.jar"]
            },
            {
              "type": "SourceJar",
              "integrity": "sha256-sources",
              "groupId": "org.example",
              "artifactId": "lib-jvm",
              "version": "1.0",
              "urls": ["https://example.com/maven/org/example/lib-jvm/1.0/lib-jvm-1.0-sources.jar"]
            }
          ]
        },
        {
          "platform": "ios_arm64",
          "id": "org.example:lib:1.0",
          "variantId": "org.example:lib-ios:1.0",
          "dependencies": [],
          "exportedDependencies": [],
          "artifacts": [
            {
              "type": "Klib",
              "integrity": "sha256-klib",
              "groupId": "org.example",
              "artifactId": "lib-ios",
              "version": "1.0",
              "urls": ["https://example.com/maven/org/example/lib-ios/1.0/lib-ios-1.0.klib"]
            },
            {
              "type": "CInteropKlib",
              "integrity": "sha256-cinterop",
              "groupId": "org.example",
              "artifactId": "lib-ios-cinterop",
              "version": "1.0",
              "urls": ["https://example.com/maven/org/example/lib-ios/1.0/lib-ios-cinterop-1.0.klib"]
            }
          ]
        },
        {
          "platform": "wasm_js",
          "id": "org.example:lib:1.0",
          "variantId": "org.example:lib-wasm-js:1.0",
          "dependencies": [],
          "exportedDependencies": [],
          "artifacts": [
            {
              "type": "Klib",
              "integrity": "sha256-klib",
              "groupId": "org.example",
              "artifactId": "lib-wasm-js",
              "version": "1.0",
              "urls": ["https://example.com/maven/org/example/lib-wasm-js/1.0/lib-wasm-js-1.0.klib"]
            },
            {
              "type": "Npm",
              "name": "js-joda-time",
              "version": "3.2.0",
              "url": "https://registry.npmjs.org/js-joda-time/-/js-joda-time-3.2.0.tgz",
              "integrity": "sha512-npm"
            }
          ]
        },
        {
          "platform": "linux_x64",
          "id": "org.example:lib:1.0",
          "variantId": "org.example:lib-linux:1.0",
          "dependencies": [],
          "exportedDependencies": [],
          "artifacts": []
        }
      ]
    },
    "org.example:dep:1.0": {
      "id": "org.example:dep:1.0",
      "variants": [
        {
          "platform": "jvm",
          "id": "org.example:dep:1.0",
          "variantId": "org.example:dep:1.0",
          "dependencies": [],
          "exportedDependencies": [],
          "artifacts": [
            {
              "type": "ClasspathJar",
              "integrity": "sha256-jar",
              "groupId": "org.example",
              "artifactId": "dep",
              "version": "1.0",
              "urls": ["https://example.com/maven/org/example/dep/1.0/dep-1.0.jar"]
            }
          ]
        }
      ]
    }
  }
}
"""

_IMPORT_RULES = struct(
    jvm = Label("//rules/imports:jvm.bzl"),
    android = Label("//rules/imports:android.bzl"),
    klib = Label("//rules/imports:klib.bzl"),
    wasmjs = Label("//rules/imports:wasmjs.bzl"),
)

def _rendered(platform, fetch_sources = True):
    resolution = decode(_MANIFEST_JSON)
    return render_build_file(
        materialize_resolution("kmp_deps", resolution, platform, fetch_sources),
        platform,
        _IMPORT_RULES,
    )

def _kinds_test_impl(ctx):
    env = unittest.begin(ctx)
    resolution = decode(_MANIFEST_JSON)

    jvm = materialize_resolution("kmp_deps", resolution, "jvm", True)
    ios = materialize_resolution("kmp_deps", resolution, "ios_arm64", True)
    wasmjs = materialize_resolution("kmp_deps", resolution, "wasmjs", True)
    linux = materialize_resolution("kmp_deps", resolution, "linux_x64", True)

    kinds = {target["name"]: target["kind"] for target in jvm.targets}
    asserts.equals(env, "jvm", kinds["org_example_lib_jvm_1_0"], "the JVM variant imports its jar")
    asserts.equals(env, "jvm", kinds["org_example_dep_1_0"])

    kinds = {target["name"]: target["kind"] for target in ios.targets}
    asserts.equals(env, "klib", kinds["org_example_lib_ios_1_0"], "the konan variant imports its klib")

    kinds = {target["name"]: target["kind"] for target in wasmjs.targets}
    asserts.equals(env, "wasmjs", kinds["org_example_lib_wasm_js_1_0"])

    kinds = {target["name"]: target["kind"] for target in linux.targets}
    asserts.equals(env, "klib_group", kinds["org_example_lib_linux_1_0"], "a variant without artifacts redirects to its dependencies")

    # The hub repositories are named after the install and the platform.
    asserts.equals(env, "kmp_deps_jvm", deps_repository_name("kmp_deps", "jvm"))
    asserts.equals(env, "kmp_deps_ios_arm64", deps_repository_name("kmp_deps", "ios_arm64"))

    return unittest.end(env)

def _jvm_render_test_impl(ctx):
    env = unittest.begin(ctx)
    rendered = _rendered("jvm")

    # The import rules are loaded from the canonical labels of the install tag.
    expected_load = "load(\"%s\", \"jvm_import\", \"jvm_library\")" % str(_IMPORT_RULES.jvm)
    asserts.true(env, expected_load in rendered, "loads the import rules from their label")

    # The jar import and its wrapper, the wrapper carrying the dependency.
    asserts.true(env, "name = \"org_example_lib_jvm_1_0_import\"," in rendered)
    asserts.true(env, "name = \"org_example_lib_jvm_1_0\"," in rendered)
    asserts.true(env, "\":org_example_dep\"," in rendered, "dependencies point at the versionless alias")

    # The source jar is wired when fetched.
    asserts.true(env, "source_jar = \"@kmp_deps_org_example_lib_jvm_1_0_sources_http//file:file\"," in rendered)

    # Versionless aliases, per umbrella library and per platform variant.
    asserts.true(env, "name = \"org_example_lib\"," in rendered)
    asserts.true(env, "name = \"org_example_lib_jvm\"," in rendered)
    asserts.true(env, "name = \"org_example_dep\"," in rendered)

    # `fetch_sources = False` leaves the source jars out.
    unsourced = _rendered("jvm", fetch_sources = False)
    asserts.true(env, "source_jar = " not in unsourced)

    return unittest.end(env)

def _konan_render_test_impl(ctx):
    env = unittest.begin(ctx)
    rendered = _rendered("ios_arm64")

    # The klib import carries its konan target; each C-interop klib gets its own target.
    asserts.true(env, "name = \"org_example_lib_ios_1_0\"," in rendered)
    asserts.true(env, "konan_target = \"ios_arm64\"," in rendered)
    asserts.true(env, "name = \"org_example_lib_ios_1_0_cinterop_0\"," in rendered)

    return unittest.end(env)

def _wasmjs_render_test_impl(ctx):
    env = unittest.begin(ctx)
    rendered = _rendered("wasmjs")

    # The klib import carries the NPM packages of its embedded package.json.
    asserts.true(env, "name = \"org_example_lib_wasm_js_1_0\"," in rendered)
    asserts.true(env, "\"js-joda-time\": \"@kmp_deps_npm_js_joda_time_3_2_0_http//:package\"," in rendered)

    return unittest.end(env)

def _redirect_render_test_impl(ctx):
    env = unittest.begin(ctx)
    rendered = _rendered("linux_x64")

    # A variant with no artifact redirects to its dependencies through the platform's group rule.
    asserts.true(env, "klib_library(" in rendered)
    asserts.true(env, "name = \"org_example_lib_linux_1_0\"," in rendered)
    asserts.true(env, "klib_import(" not in rendered, "no klib to import")

    return unittest.end(env)

kinds_test = unittest.make(_kinds_test_impl)
jvm_render_test = unittest.make(_jvm_render_test_impl)
konan_render_test = unittest.make(_konan_render_test_impl)
wasmjs_render_test = unittest.make(_wasmjs_render_test_impl)
redirect_render_test = unittest.make(_redirect_render_test_impl)

def materialize_test_suite(name):
    unittest.suite(
        name,
        kinds_test,
        jvm_render_test,
        konan_render_test,
        wasmjs_render_test,
        redirect_render_test,
    )
