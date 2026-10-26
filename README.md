# bazel-kmp-resolver

A resolver of Kotlin Multiplatform dependencies that produces a manifest of that resolution, to be used notably in Bazel
repository rules

## Usage

The resolver is not meant to be use standalone, it will be used in a Bazel repository rule.

But you could try it out by running:

```shell
./kotlin run --module bazel-kmp-resolver --main-class org.jetbrains.kmp.resolver.MainKt -- --output-manifest-file=./build/kmp-local-cache/manifest.json --repository=https://repo1.maven.org/maven2 --repository=https://dl.google.com/dl/android/maven2 --repository-credentials-file=./testResources/credentials.json --coordinate=io.ktor:ktor-client-cio:3.5.0 --platform=wasmjs --platform=jvm
```

### Authentication

Private repositories are authenticated in two ways, both optional:

- `--repository-credentials-file`: a JSON list of `{"repositoryUrl", "username", "password"}` entries, sent as basic
  authentication with the requests under each repository URL (Bazel's `kmp.bzl` fills it from `NETRC`).
- `--credential-helper=[scope=]path`: a [Bazel credential helper](https://github.com/EngFlow/credential-helper-spec),
  run as `path get` with `{"uri": "..."}` on its standard input, whose `headers` are added to every request of a matching
  host. The syntax and matching rules are those of Bazel's `--credential_helper` (`%workspace%` is not expanded, the
  caller passes an absolute path or a name found on `PATH`). Answers are cached per URI until their `expires` timestamp,
  or for `--credential-helper-cache-duration-ms` (30 minutes) without one; a helper not answering within
  `--credential-helper-timeout-ms` (10 seconds), exiting with an error, or printing an invalid answer fails the
  resolution.

Both go through the same HTTP client, which authenticates every request of the resolver: a host a helper applies to is
authenticated by that helper alone, even when it returns no header, and the credentials file serves the other hosts,
as in Bazel.

## Formatting

To format, run:

```shell
./kotlin do format
```

## Tests

To run tests, run:

```shell
./kotlin test
```