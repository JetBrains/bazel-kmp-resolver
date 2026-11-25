# rules_kotlin_external

Bazel rules to declare and resolve Kotlin Multiplatform Maven dependencies, built on the
`bazel-kmp-resolver`. Consolidates intellij-community's vendored `fleet/build/rules/kmp.bzl`.

Requires Bazel ≥ 9.0.0 (module extension facts).

## Declare and resolve dependencies

```starlark
# MODULE.bazel, root module only.
bazel_dep(name = "rules_kotlin_external", version = "0.5.0")

kmp = use_extension("@rules_kotlin_external//rules:extensions.bzl", "kmp")
kmp.install(
    name = "kmp_deps",
    artifacts = ["org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"],
    platforms = ["jvm", "wasmjs"],
    repositories = ["https://repo1.maven.org/maven2"],
)
kmp.resolver(
    version = "0.5.0",
    sha256 = {"linux-x64": "...", "macos-arm64": "...", ...},
)
use_repo(kmp, "kmp_deps_jvm", "kmp_deps_wasmjs")
```

Then depend on a library by its versionless, sanitized Maven module name from any platform repo:

```python
deps = ["@kmp_deps_jvm//:org_jetbrains_kotlinx_kotlinx_coroutines_core"]
```

## Tags

### `kmp.install(...)` — one per dependency set, root module only

| attr | type | default | |
|---|---|---|---|
| `name` | string | `"kmp_deps"` | Prefixes the generated repositories: `<name>_<platform>` hubs (e.g. `kmp_deps_jvm`), artifact repositories behind them. Installs with distinct names resolve independently. |
| `artifacts` | string list | required | `group:artifact:version` coordinates, optional `bom:` prefix. An empty list generates empty repositories, without resolving. |
| `platforms` | string list | required | `jvm`, `android`, `wasmjs`, or a konan target (`ios_arm64`, `macos_arm64`, ...). One hub repository per platform, same target names in each. |
| `repositories` | string list | required | Maven repository URLs, in lookup order. |
| `substitutions` | dict | `{}` | `group:artifact` → `group:artifact:version`: the substitute is resolved in place of the substituted library, with its own artifacts and dependencies. A library cannot be substituted by another version of itself. |
| `npm_package_version_overrides` | dict | `{}` | npm `name=version` overrides (`wasmjs` only). |
| `npm_registry` | string | npmjs.org | npm registry URL (`wasmjs` only). |
| `fetch_sources` | bool | `True` | Fetch and wire source jars. Materialization-only: changing it does not re-resolve. |
| `jvm_import_bzl` | label | `//rules/imports:jvm.bzl` | bzl exporting `jvm_import`/`jvm_library`. |
| `android_import_bzl` | label | `//rules/imports:android.bzl` | bzl exporting `aar_import`/`android_library`. |
| `klib_import_bzl` | label | `//rules/imports:klib.bzl` | bzl exporting `klib_import`/`klib_library`. |
| `wasmjs_import_bzl` | label | `//rules/imports:wasmjs.bzl` | bzl exporting `wasmjs_import`/`wasmjs_library`. |

### `kmp.resolver(...)` — exactly one, root module only

Either a released resolver, or — for development — a locally built binary:

```starlark
kmp.resolver(version = "0.5.0", sha256 = {"linux-x64": "...", "linux-arm64": "...", ...})
# or, for development:
kmp.resolver(local_macos_arm64 = "//:resolver-local")
```

| attr | |
|---|---|
| `version` | Released bazel-kmp-resolver version; assets are `bazel-kmp-resolver-<os>-<arch>` binaries. |
| `sha256` | Asset checksums keyed `linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`, `windows-x64`, optionally `windows-arm64` (reuses the x64 asset). |
| `local_linux_x64` ... `local_windows_x64` | Label of a locally built binary (`./kotlin do buildNativeImage` in the resolver checkout); its sha256 is computed with `sha256sum`/`shasum`/`certutil`. |

Local labels win over the release for the host. See `examples/stage-local-resolver.sh` for the
intended flow.

## Generated targets

Per `<name>_<platform>` hub, per resolved library:

- a target named after the platform-specific variant (`io_ktor_ktor_client_core_jvm_3_6_0`),
  instantiating the platform's import rule (`jvm_import` + `jvm_library` wrapper on the JVM, so that
  `exportedDependencies` reach the dependents' compile classpath; `aar_import` on Android, or the
  JVM pair when a library publishes no `.aar`; `klib_import` per konan target, with one target per
  C-interop klib; `wasmjs_import` carrying the klib's NPM packages in `npm_packages`),
- `alias` targets under the versionless umbrella and variant names
  (`io_ktor_ktor_client_core`, `io_ktor_ktor_client_core_jvm`); ambiguous names are dropped,
  collisions with real targets fail,
- libraries without a platform artifact (e.g. JetBrains Compose's Android variants) redirect to
  their dependencies through a sourceless group target.

## Caching

Resolutions are cached in `MODULE.bazel.lock` through module extension facts: see [facts.md](facts.md).
The resolver's download cache lives in `<output_base>/kmp-resolver-cache`; `bazel clean --expunge`
deletes it. Neither changes the resolution.

## Credentials

Private repositories authenticate through `NETRC` (basic auth) or a Bazel credential helper, forwarded
through the `RULES_KOTLIN_EXTERNAL_CREDENTIAL_HELPER` (or `KMP_RESOLVER_CREDENTIAL_HELPER`)
environment variable:

```starlark
# bazelrc
common --repo_env=RULES_KOTLIN_EXTERNAL_CREDENTIAL_HELPER=%workspace%/tools/credential-helper
```

`[scope=]path` entries separated by `;`, like Bazel's `--credential_helper`; `..._TIMEOUT` and
`..._CACHE_DURATION` counterparts follow Bazel's duration syntax. Credentials are not resolution
inputs: changing them does not re-resolve.

## Pluggable import rules

The default import rules of `//rules/imports` are minimal: enough for classpath-style consumption
and IDE indexing, no compilation. A consumer with real toolchains swaps them by re-exporting its
rules under the expected symbol names:

```starlark
# kmp_imports.bzl — swap rules_kotlin's kt_klib_import in
load("@rules_kotlin//kotlin/native.bzl", _impl = "kt_klib_import")
klib_import = _impl
klib_library = native.alias
```

```starlark
kmp.install(..., klib_import_bzl = "//tools:kmp_imports.bzl")
```

## Tests

- `cd rules_kotlin_external && bazel test //tests/...` — Starlark unit tests.
- `rules_kotlin_external/examples/` — integration workspaces, run against the locally built
  resolver: `./kotlin do buildNativeImage`, `examples/stage-local-resolver.sh`, then `bazel build`
  the targets named in each example's `BUILD.bazel`. `examples/facts-behavior.sh` asserts the facts
  caching cycle.
