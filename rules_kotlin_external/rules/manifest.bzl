"""Decoding and accessors for the `bazel-kmp-resolver` resolution manifest.

See the resolver repository's `testResources/manifest-*.json` for real examples: `libraries` keyed
by KMP umbrella coordinate, each with per-platform `variants` carrying `dependencies` and polymorphic
`artifacts` (`ClasspathJar`, `SourceJar`, `Klib`, `CInteropKlib`, `Aar`, `Npm`). A variant with no
artifact only redirects to its dependencies (e.g. JetBrains Compose's Android variants → AndroidX).
"""

load(":naming.bzl", "artifact_classifier", "basename_from_url")

# The resolver's `--platform` CLI names, locked by its `CliNameTest`, in the order their hub
# repositories are generated: one `<install>_<platform>` repository per platform, with the same
# target names across platforms.
PLATFORMS = [
    "wasmjs",
    "jvm",
    "android",
    "ios_arm64",
    "ios_simulator_arm64",
    "ios_x64",
    "linux_arm64",
    "linux_x64",
    "macos_arm64",
    "macos_x64",
    "mingw_x64",
    "tvos_arm64",
    "tvos_simulator_arm64",
    "tvos_x64",
    "watchos_arm32",
    "watchos_arm64",
    "watchos_device_arm64",
    "watchos_simulator_arm64",
    "android_arm32",
    "android_arm64",
    "android_x64",
    "android_x86",
]

# Manifest platforms are written like their CLI names, except `wasmjs`, written `wasm_js`.
_MANIFEST_PLATFORM_BY_CLI = {"wasmjs": "wasm_js"}

_CLI_PLATFORM_BY_MANIFEST = {manifest: cli for cli, manifest in _MANIFEST_PLATFORM_BY_CLI.items()}

def manifest_platform(cli_platform):
    """The platform name as the manifest writes it (only `wasmjs` differs, as `wasm_js`)."""
    return _MANIFEST_PLATFORM_BY_CLI.get(cli_platform, cli_platform)

def cli_platform(manifest_platform_name):
    """The CLI platform name of a manifest platform name (`wasm_js` → `wasmjs`)."""
    return _CLI_PLATFORM_BY_MANIFEST.get(manifest_platform_name, manifest_platform_name)

_MAVEN_ARTIFACT_TYPES = ["ClasspathJar", "SourceJar", "Klib", "CInteropKlib", "Aar"]

_NPM_ARTIFACT_TYPE = "Npm"

def decode(resolution_json):
    """Parses and structurally validates a resolution manifest JSON."""
    resolution = json.decode(resolution_json)
    for field in ["askedCoordinates", "askedRepositories", "libraries"]:
        if field not in resolution:
            fail("The resolver manifest lacks its '%s' field; was it produced by an incompatible resolver?" % field)
    for library_id, library in resolution["libraries"].items():
        for variant in library["variants"]:
            for artifact in variant["artifacts"]:
                _validate_artifact(library_id, artifact)
    return resolution

def _validate_artifact(library_id, artifact):
    artifact_type = artifact.get("type")
    if artifact_type == _NPM_ARTIFACT_TYPE:
        for field in ["name", "version", "url", "integrity"]:
            if field not in artifact:
                fail("Library %s has an npm artifact lacking '%s': %s" % (library_id, field, artifact))
    elif artifact_type in _MAVEN_ARTIFACT_TYPES:
        for field in ["integrity", "groupId", "artifactId", "version", "urls"]:
            if field not in artifact:
                fail("Library %s has a %s artifact lacking '%s': %s" % (library_id, artifact_type, field, artifact))
    else:
        fail("Library %s has an artifact of unknown type# %r, known types: %s and %s" % (
            library_id,
            artifact_type,
            _MAVEN_ARTIFACT_TYPES,
            _NPM_ARTIFACT_TYPE,
        ))

def libraries(resolution):
    return resolution["libraries"]

def platform_variant(library, cli_platform_name):
    """The variant of [library] for the platform, or None when the library is not resolved there."""
    wanted = manifest_platform(cli_platform_name)
    for variant in library["variants"]:
        if variant["platform"] == wanted:
            return variant
    return None

def maven_artifacts(variant):
    """The Maven artifacts (anything but npm packages) of a variant, which become `http_file` repositories."""
    return [artifact for artifact in variant["artifacts"] if artifact["type"] != _NPM_ARTIFACT_TYPE]

def npm_packages(variant):
    """The NPM package artifacts of a variant, which become `http_archive` repositories."""
    return [artifact for artifact in variant["artifacts"] if artifact["type"] == _NPM_ARTIFACT_TYPE]

def artifacts_of_type(variant, artifact_type):
    return [artifact for artifact in variant["artifacts"] if artifact["type"] == artifact_type]

def artifact_of_type(variant, artifact_type):
    """The single artifact of a variant of the given type, or None; fails when there are several."""
    artifacts = artifacts_of_type(variant, artifact_type)
    if len(artifacts) > 1:
        fail("Library %s has %s artifacts of type %s for platform %s, expected at most one" % (
            variant["id"],
            len(artifacts),
            artifact_type,
            variant["platform"],
        ))
    return artifacts[0] if artifacts else None

def class_path_jar(variant):
    return artifact_of_type(variant, "ClasspathJar")

def aar(variant):
    return artifact_of_type(variant, "Aar")

def klib(variant):
    return artifact_of_type(variant, "Klib")

def cinterop_klibs(variant):
    """The C-interop klibs of a native variant, in the manifest's order."""
    return artifacts_of_type(variant, "CInteropKlib")

def source_jar(variant):
    """The `sources`-classifier source jar of a variant, or None when it publishes none.

    A variant may publish several source-like jars (e.g. `foo-1.0-sources.jar` and
    `foo-1.0-samples-sources.jar`): only the plain `sources` classifier is the source jar.
    """
    candidates = [
        artifact
        for artifact in artifacts_of_type(variant, "SourceJar")
        if artifact_classifier(basename_from_url(artifact["urls"][0]), artifact["artifactId"], artifact["version"]) == "sources"
    ]
    if len(candidates) > 1:
        fail("Library %s has %s source jars for platform %s, expected at most one" % (
            variant["id"],
            len(candidates),
            variant["platform"],
        ))
    return candidates[0] if candidates else None

def redirects(variant):
    """Whether the variant carries no artifact, only dependencies (a redirect, e.g. JetBrains Compose → AndroidX)."""
    return not variant["artifacts"]
