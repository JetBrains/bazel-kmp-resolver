# Resolution caching through module extension facts

Resolutions run inside the `kmp` extension's evaluation and are persisted through Bazel's module
extension facts: a later evaluation with the same inputs reuses the stored manifest JSON and never
runs the resolver.

## What is stored, and where

Each fresh resolution is stored in `MODULE.bazel.lock` (top-level `facts` section) under a key
carrying everything that determines the stored value — the fact key, built in `rules/facts.bzl`:

- the facts schema and manifest schema versions,
- the install's name, artifacts, platforms, repositories, substitutions, npm overrides, npm registry,
- the sha256 of the resolver binary used (release pin, or computed from a local binary),
- the hermetic Node.js version, for installs resolving `wasmjs`.

The value is the resolver's manifest JSON, rendered into the per-platform hub repositories.

## When the resolver runs again

Exactly when the key changes: a dependency definition changes, the resolver binary changes (a
release upgrade, or a rebuilt `resolver-local`), the Node.js version changes (for `wasmjs`), or the
key's schema constants change. Materialization-only settings — `fetch_sources`, the import-rule
labels — and credentials are not key inputs: changing them re-renders the repositories without
re-resolving.

## What survives what

- `bazel clean`, `bazel clean --expunge`: facts survive, they live in the lockfile. (`--expunge`
  additionally deletes the resolver's download cache in `<output_base>/kmp-resolver-cache`.)
- Extension code changes: facts are *not* invalidated; the key's schema constants cover value-shape
  changes, and a hard break is available through the `facts_version` parameter of the
  `module_extension` declaration.
- Deleting `MODULE.bazel.lock`: everything re-resolves.

## Hosts

Each host pins (or builds) its own resolver binary, so its sha256 — and thus its fact key — differs
per host: hosts resolve independently. The resolutions themselves are host-independent, so a
lockfile produced on one host can be shared with another.
