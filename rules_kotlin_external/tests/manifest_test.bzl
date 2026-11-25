"""Unit tests of `rules/manifest.bzl` against an inline manifest covering every artifact kind."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(
    "//rules:manifest.bzl",
    "PLATFORMS",
    "aar",
    "class_path_jar",
    "cli_platform",
    "cinterop_klibs",
    "decode",
    "klib",
    "maven_artifacts",
    "manifest_platform",
    "npm_packages",
    "platform_variant",
    "redirects",
    "source_jar",
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
              "integrity": "sha256-samples",
              "groupId": "org.example",
              "artifactId": "lib-jvm",
              "version": "1.0",
              "urls": ["https://example.com/maven/org/example/lib-jvm/1.0/lib-jvm-1.0-samples-sources.jar"]
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
          "platform": "android",
          "id": "org.example:lib:1.0",
          "variantId": "org.example:lib-android:1.0",
          "dependencies": [],
          "exportedDependencies": ["org.example:dep"],
          "artifacts": [
            {
              "type": "Aar",
              "integrity": "sha256-aar",
              "groupId": "org.example",
              "artifactId": "lib-android",
              "version": "1.0",
              "urls": ["https://example.com/maven/org/example/lib-android/1.0/lib-android-1.0.aar"]
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
          "platform": "ios_arm64",
          "id": "org.example:lib:1.0",
          "variantId": "org.example:lib-ios:1.0",
          "dependencies": ["org.example:dep"],
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
          "platform": "linux_x64",
          "id": "org.example:lib:1.0",
          "variantId": "org.example:lib-linux:1.0",
          "dependencies": ["org.example:dep"],
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

def _decoded_manifest():
    return decode(_MANIFEST_JSON)

def _platform_names_test_impl(ctx):
    env = unittest.begin(ctx)

    asserts.equals(env, "wasm_js", manifest_platform("wasmjs"))
    asserts.equals(env, "wasmjs", cli_platform("wasm_js"))
    asserts.equals(env, "jvm", manifest_platform("jvm"))
    asserts.equals(env, "ios_arm64", manifest_platform("ios_arm64"))

    asserts.true(env, "wasmjs" in PLATFORMS, "wasmjs is a platform")
    asserts.true(env, "jvm" in PLATFORMS, "jvm is a platform")
    asserts.true(env, "android" in PLATFORMS, "android is a platform")
    asserts.true(env, "ios_simulator_arm64" in PLATFORMS, "ios_simulator_arm64 is a platform")
    asserts.equals(env, 22, len(PLATFORMS))

    return unittest.end(env)

def _platform_variant_test_impl(ctx):
    env = unittest.begin(ctx)

    library = _decoded_manifest()["libraries"]["org.example:lib:1.0"]

    # The `wasmjs` platform is written `wasm_js` in the manifest.
    asserts.equals(env, "org.example:lib-wasm-js:1.0", platform_variant(library, "wasmjs")["variantId"])
    asserts.equals(env, "org.example:lib-jvm:1.0", platform_variant(library, "jvm")["variantId"])
    asserts.equals(env, "org.example:lib-ios:1.0", platform_variant(library, "ios_arm64")["variantId"])

    # A library absent from the platform resolves to no variant.
    asserts.equals(env, None, platform_variant(library, "macos_arm64"))

    return unittest.end(env)

def _artifacts_test_impl(ctx):
    env = unittest.begin(ctx)

    library = _decoded_manifest()["libraries"]["org.example:lib:1.0"]
    jvm = platform_variant(library, "jvm")
    android = platform_variant(library, "android")
    wasmjs = platform_variant(library, "wasmjs")
    ios = platform_variant(library, "ios_arm64")
    linux = platform_variant(library, "linux_x64")

    # JVM: a jar and its sources; of two source-like jars, the plain `sources` classifier is the source jar.
    asserts.equals(env, "sha256-jar", class_path_jar(jvm)["integrity"])
    asserts.equals(env, "lib-jvm-1.0-sources.jar", source_jar(jvm)["urls"][0].rsplit("/", 1)[-1])
    asserts.equals(env, 3, len(maven_artifacts(jvm)))

    # Android: an `.aar`, exposed by the variant of a library that publishes no jar.
    asserts.equals(env, "sha256-aar", aar(android)["integrity"])
    asserts.equals(env, None, class_path_jar(android))

    # WasmJS: the klib plus its NPM packages; the NPM package is not a Maven artifact.
    asserts.equals(env, "sha256-klib", klib(wasmjs)["integrity"])
    asserts.equals(env, 1, len(npm_packages(wasmjs)))
    asserts.equals(env, "js-joda-time", npm_packages(wasmjs)[0]["name"])
    asserts.equals(env, 1, len(maven_artifacts(wasmjs)))

    # A konan target: the klib and its C-interop klib.
    asserts.equals(env, "sha256-klib", klib(ios)["integrity"])
    asserts.equals(env, 1, len(cinterop_klibs(ios)))

    # A variant with no artifact at all redirects to its dependencies.
    asserts.true(env, redirects(linux), "the linux_x64 variant has no artifact")
    asserts.true(env, not redirects(jvm), "the jvm variant has artifacts")

    return unittest.end(env)

platform_names_test = unittest.make(_platform_names_test_impl)
platform_variant_test = unittest.make(_platform_variant_test_impl)
artifacts_test = unittest.make(_artifacts_test_impl)

def manifest_test_suite(name):
    unittest.suite(
        name,
        platform_names_test,
        platform_variant_test,
        artifacts_test,
    )
