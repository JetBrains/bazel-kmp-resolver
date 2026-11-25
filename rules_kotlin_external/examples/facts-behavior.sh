#!/usr/bin/env bash
#
# Asserts the facts behavior of the `kmp` extension in the `jvm` example: a resolution is cached in
# MODULE.bazel.lock, survives `bazel clean --expunge` (which wipes the whole output base but not the
# lockfile), and is re-run when a dependency definition changes. The resolver's cache,
# `<output_base>/kmp-resolver-cache`, is created only when the resolver runs: its absence after an
# expunge proves a facts hit, its presence proves a re-resolution.
#
#     rules_kotlin_external/examples/facts-behavior.sh

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
example="$script_dir/jvm"

cd "$example"
cp MODULE.bazel MODULE.bazel.facts-test-backup
trap 'mv MODULE.bazel.facts-test-backup MODULE.bazel' EXIT

coroutines="@kmp_deps_jvm//:org_jetbrains_kotlinx_kotlinx_coroutines_core"
datetime="@kmp_deps_jvm//:org_jetbrains_kotlinx_kotlinx_datetime"

echo "== Fresh resolution: the resolver runs and caches its downloads."
rm -f MODULE.bazel.lock
bazel clean --expunge >/dev/null
bazel build "$coroutines" >/dev/null
cache_dir="$(bazel info output_base)/kmp-resolver-cache"
[ -d "$cache_dir" ] || { echo "FAIL: expected the resolver's cache after a fresh resolution."; exit 1; }

echo "== bazel clean --expunge wipes the whole output base; facts live in MODULE.bazel.lock."
bazel clean --expunge >/dev/null

echo "== Rebuild: the resolution comes from facts, the resolver must not run."
bazel build "$coroutines" >/dev/null
if [ -d "$cache_dir" ]; then
  echo "FAIL: the resolver ran again although facts should have carried the resolution."
  exit 1
fi
echo "   Facts cache hit confirmed: no resolver run after bazel clean."

echo "== A dependency definition change re-resolves."
sed -i.bak 's|kotlinx-coroutines-core:1.10.2"|kotlinx-coroutines-core:1.10.2", "org.jetbrains.kotlinx:kotlinx-datetime:0.8.0"|' MODULE.bazel
rm MODULE.bazel.bak
bazel build "$datetime" >/dev/null
[ -d "$cache_dir" ] || { echo "FAIL: expected a fresh resolution after a definition change."; exit 1; }
echo "   Re-resolution confirmed after the definition change."

echo
echo "All facts behavior assertions passed."
