"""Caching of resolutions through Bazel module extension facts.

Fresh resolutions are stored in `module_ctx.facts` keyed by [resolution_fact_key]; a later
evaluation with the same key reuses the stored manifest JSON without running the resolver. Facts
live in `MODULE.bazel.lock`, survive `bazel clean`, and are not invalidated by extension code
changes, so the key itself carries everything that determines the stored value: the schema
constants, the install's name and inputs, the resolver binary's sha256, and — for `wasmjs`
installs — the hermetic Node.js version. `fetch_sources`, the import-rule labels, and credentials
are deliberately not key inputs: they never change what the resolver computes.
"""

RESOLUTION_FACTS_SCHEMA = "rules_kotlin_external.resolution.v1"

MANIFEST_SCHEMA_VERSION = "manifest.v1"

# A resolution the resolver does not need to compute: an install without artifacts still gets its
# per-platform repositories so that no `use_repo` dangles.
EMPTY_RESOLUTION_JSON = json.encode({
    "askedCoordinates": [],
    "askedRepositories": [],
    "libraries": {},
})

def resolution_fact_key(install, resolver_sha256, node_version = None):
    """The facts key of an install's resolution: [install] is the validated tag struct, whose
    `artifacts` must not be empty. [node_version] is the hermetic Node.js version of the klibs' NPM
    closure, for installs resolving the `wasmjs` platform."""
    return json.encode([
        RESOLUTION_FACTS_SCHEMA,
        MANIFEST_SCHEMA_VERSION,
        install.name,
        resolver_sha256,
        sorted(install.platforms),
        install.artifacts,
        install.repositories,
        sorted_dict_items(install.substitutions),
        sorted_dict_items(install.npm_package_version_overrides),
        install.npm_registry,
        node_version,
    ])

def sorted_dict_items(values):
    return [[key, values[key]] for key in sorted(values.keys())]
