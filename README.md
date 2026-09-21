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

### Calling a Bazel credential helper from a repository rule

Bazel exposes **no Starlark API for credential helpers**, and `repository_ctx.execute` has no `stdin` parameter,
so a repository rule cannot speak the helper protocol directly. Run the helper through a small Node shim instead
(this resolver already requires a Node.js executable, so no extra toolchain is needed):

```python
result = module_ctx.execute([node, shim_js, helper_path, repository_url])
headers = json.decode(result.stdout).get("headers", {})
```

Two things worth getting right on the Bazel side:

- Resolve the credentials **inside** the rule with `execute`, never as a rule attribute. Attribute values go into
  the repository's reproducibility marker, so a rotating token would trigger a refetch on every rotation.
- The resolved credentials file holds a bearer token. Keep it out of anything cached or shared, and note that
  `repository_ctx.execute` passes a restricted environment: a helper usually needs at least `HOME` and `PATH`.

The same hosts still need `--credential_helper` configured in `.bazelrc`, so that Bazel's own download of the
artifact URLs listed in the manifest is authenticated too.

## Tests

To run tests, run:

```shell
./kotlin test
```