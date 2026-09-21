# bazel-kmp-resolver

A resolver of Kotlin Multiplatform dependencies that produces a manifest of that resolution, to be used notably in Bazel
repository rules

## Usage

The resolver is not meant to be use standalone, it will be used in a Bazel repository rule.

But you could try it out by running:

```shell
./kotlin run --module bazel-kmp-resolver --main-class org.jetbrains.kmp.resolver.MainKt -- --output-manifest-file=./build/kmp-local-cache/manifest.json --repository=https://repo1.maven.org/maven2 --repository=https://dl.google.com/dl/android/maven2 --repository-credentials-file=./testResources/credentials.json --coordinate=io.ktor:ktor-client-cio:3.5.0
```

## Repository credentials

The resolver never resolves credentials itself: the caller resolves them (from a `.netrc` file, from a
[Bazel credential helper](https://github.com/bazelbuild/proposals/blob/main/designs/2022-06-07-bazel-credential-helpers.md),
…) and passes them with `--repository-credentials-file`, as a JSON list keyed by repository URL:

```json
[
  {
    "repositoryUrl": "https://repo.example.com/maven2",
    "headers": { "Authorization": ["Bearer helper-issued-token"] }
  },
  {
    "repositoryUrl": "https://packages.example.org",
    "username": "alice",
    "password": "token-a"
  }
]
```

- `username`/`password` express HTTP Basic auth, as a `.netrc` file provides it.
- `headers` express arbitrary HTTP headers, which is what a credential helper returns and the only way to pass a
  bearer token. When both are given, `headers` wins.
- An entry also applies to the NPM registry when its `repositoryUrl` matches `--npm-registry`. npm cannot send an
  arbitrary header, so only `Bearer` and `Basic` authorization can be forwarded to it; anything else fails the
  resolution rather than being silently dropped.
- Credentials apply to a repository URL and everything below it. When several entries match an artifact URL, the
  longest `repositoryUrl` wins.

## Bazel credential helpers

Bazel authenticates the downloads it performs itself by running a
[credential helper](https://github.com/bazelbuild/proposals/blob/main/designs/2022-06-07-bazel-credential-helpers.md),
but it exposes no credential helper API to Starlark, and `repository_ctx.execute` cannot write to the standard
input of the process it spawns, which the protocol requires. So a repository rule cannot resolve the credentials
on the resolver's behalf: instead it forwards the helper configuration, and the resolver speaks the protocol
itself for the requests it performs while resolving the dependency graph.

```shell
--credential-helper=/usr/bin/helper                  # every host
--credential-helper=example.com=/usr/bin/helper      # exactly example.com
--credential-helper='*.example.com=%workspace%/tools/helper.sh'  # example.com and its subdomains
--workspace-directory=/path/to/workspace             # `%workspace%` expansion, and the helpers' working directory
```

`--credential-helper` takes the syntax and the semantics of Bazel's own `--credential_helper` flag, so the values
of a `.bazelrc` can be forwarded verbatim: the pattern is separated from the path by the left-most `=`, the most
specific match wins (exact name, then longest wildcard, then unscoped), a later entry overrides an earlier one
with the same pattern, and a host no helper matches is queried without credentials. A path carrying no separator
is looked up on `PATH`. Helpers take precedence over `--repository-credentials-file`.

Credentials are resolved **once per repository**, before the resolution, rather than once per artifact URL: a
helper is a subprocess and a resolution issues thousands of requests. This is the caching the protocol explicitly
allows, and it means `expires` is not acted upon — a resolution outliving its token fails rather than renewing it.

Note that the same hosts still need `--credential_helper` configured in `.bazelrc`, so that Bazel's own download
of the artifact URLs listed in the manifest is authenticated too.

## Tests

To run tests, run:

```shell
./kotlin test
```