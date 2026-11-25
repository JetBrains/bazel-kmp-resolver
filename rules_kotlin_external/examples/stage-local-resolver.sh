#!/usr/bin/env bash
#
# Stages the locally built resolver binary into every example workspace, as the `resolver-local`
# file their `kmp.resolver(local_* = "//:resolver-local")` tags point to. The examples run against
# the local build so that they track unreleased resolver changes; releases pin published binaries
# through `kmp.resolver(version = ..., sha256 = {...})` instead.
#
#     ./kotlin do buildNativeImage                                   # repository root
#     rules_kotlin_external/examples/stage-local-resolver.sh

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

case "$(uname -s)/$(uname -m)" in
  Darwin/arm64) host="macos-arm64" ;;
  Darwin/x86_64) host="macos-x64" ;;
  Linux/x86_64) host="linux-x64" ;;
  Linux/aarch64) host="linux-arm64" ;;
  MINGW*/AMD64) host="windows-x64" ;;
  *)
    echo "Unsupported host $(uname -s)/$(uname -m): stage the binary manually." >&2
    exit 1
    ;;
esac

binary="$repo_root/build/native-image/bazel-kmp-resolver-$host"
if [ ! -f "$binary" ]; then
  echo "No resolver binary at $binary" >&2
  echo "Build it first from the repository root: ./kotlin do buildNativeImage" >&2
  exit 1
fi

for example in "$script_dir"/*/; do
  [ -d "$example" ] || continue
  cp "$binary" "$example/resolver-local"
  chmod +x "$example/resolver-local"
  echo "Staged $binary -> ${example}resolver-local"
done
