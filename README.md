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

Every request of the resolver goes through one HTTP client, which authenticates it from the credentials it is given:

- `--repository-credentials-file`: a JSON list of `{"repositoryUrl", "username", "password"}` entries, sent as basic
  authentication with the requests under each repository URL (Bazel's `kmp.bzl` fills it from `NETRC`).

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