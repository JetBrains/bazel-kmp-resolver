"""Acquisition and invocation of the `bazel-kmp-resolver` binary, ported from intellij-community's
`fleet/build/rules/kmp.bzl`.

Pinned through the `resolver` tag: either a released `version` with its per-host `sha256` checksums,
or a `local_<host>` label of a binary built by `./kotlin do buildNativeImage`. The binary's sha256 is
part of the resolution facts key, so any resolver change re-resolves the dependencies.
"""

load("@bazel_tools//tools/build_defs/repo:utils.bzl", "get_auth")

_RESOLVER_BINARY_URL_PREFIX = "https://cache-redirector.jetbrains.com/github.com/JetBrains/bazel-kmp-resolver/releases/download/%s"

# Release asset file names per host key; `windows_arm64` reuses the x64 asset, as GraalVM does not
# support ARM64 Windows and the publish workflow ships a single x64 binary.
_RESOLVER_ASSET_BY_HOST = {
    "linux_arm64": "bazel-kmp-resolver-linux-arm64",
    "linux_x64": "bazel-kmp-resolver-linux-x64",
    "macos_arm64": "bazel-kmp-resolver-macos-arm64",
    "macos_x64": "bazel-kmp-resolver-macos-x64",
    "windows_x64": "bazel-kmp-resolver-windows-x64.exe",
    "windows_arm64": "bazel-kmp-resolver-windows-x64.exe",
}

# `sha256` dictionary keys of the resolver tag, per host.
_SHA256_KEY_BY_HOST = {
    "linux_arm64": "linux-arm64",
    "linux_x64": "linux-x64",
    "macos_arm64": "macos-arm64",
    "macos_x64": "macos-x64",
    "windows_x64": "windows-x64",
    "windows_arm64": "windows-arm64",
}

# Local binary label attributes of the resolver tag, per host; `windows_arm64` reuses the x64 label.
_LOCAL_LABEL_ATTR_BY_HOST = {
    "linux_arm64": "local_linux_arm64",
    "linux_x64": "local_linux_x64",
    "macos_arm64": "local_macos_arm64",
    "macos_x64": "local_macos_x64",
    "windows_x64": "local_windows_x64",
    "windows_arm64": "local_windows_x64",
}

# Hermetic node/npm for the NPM dependencies declared by the `package.json` embedded in klibs, from
# the `node` extension of `rules_nodejs` (see `use_repo` in MODULE.bazel). npm is always run as
# `node npm-cli.js ...`: the `npm`/`npm.cmd` wrapper scripts are not portably executable.
_NODEJS_REPOSITORIES = {
    "linux_arm64": struct(
        repository = "nodejs_linux_arm64",
        node = "bin/nodejs/bin/node",
        npm_cli_js = "bin/nodejs/lib/node_modules/npm/bin/npm-cli.js",
    ),
    "linux_x64": struct(
        repository = "nodejs_linux_amd64",
        node = "bin/nodejs/bin/node",
        npm_cli_js = "bin/nodejs/lib/node_modules/npm/bin/npm-cli.js",
    ),
    "macos_arm64": struct(
        repository = "nodejs_darwin_arm64",
        node = "bin/nodejs/bin/node",
        npm_cli_js = "bin/nodejs/lib/node_modules/npm/bin/npm-cli.js",
    ),
    "macos_x64": struct(
        repository = "nodejs_darwin_amd64",
        node = "bin/nodejs/bin/node",
        npm_cli_js = "bin/nodejs/lib/node_modules/npm/bin/npm-cli.js",
    ),
    "windows_x64": struct(
        repository = "nodejs_windows_amd64",
        node = "bin/nodejs/node.exe",
        npm_cli_js = "bin/nodejs/node_modules/npm/bin/npm-cli.js",
    ),
    "windows_arm64": struct(
        repository = "nodejs_windows_arm64",
        node = "bin/nodejs/node.exe",
        npm_cli_js = "bin/nodejs/node_modules/npm/bin/npm-cli.js",
    ),
}

def host_key(module_ctx):
    """The `<os>_<arch>` host key, among the resolver's and Node.js's supported hosts."""
    os_name = module_ctx.os.name.lower()
    arch = module_ctx.os.arch.lower()

    if "linux" in os_name:
        os_key = "linux"
    elif "mac" in os_name or "darwin" in os_name:
        os_key = "macos"
    elif "windows" in os_name:
        os_key = "windows"
    else:
        fail("Unsupported rules_kotlin_external host OS: %s" % module_ctx.os.name)

    if arch in ["amd64", "x86_64", "x64"]:
        arch_key = "x64"
    elif arch in ["arm64", "aarch64"]:
        arch_key = "arm64"
    else:
        fail("Unsupported rules_kotlin_external host architecture: %s" % module_ctx.os.arch)

    return "%s_%s" % (os_key, arch_key)

def node_tooling(module_ctx):
    """The hermetic Node.js executable and `npm-cli.js` of the host, for the `wasmjs` platform."""
    host = host_key(module_ctx)
    nodejs = _NODEJS_REPOSITORIES.get(host)
    if nodejs == None:
        fail("No node/npm repository available for host %s (%s/%s)" % (
            host,
            module_ctx.os.name,
            module_ctx.os.arch,
        ))
    return struct(
        node = module_ctx.path(Label("@%s//:%s" % (nodejs.repository, nodejs.node))),
        npm_cli_js = module_ctx.path(Label("@%s//:%s" % (nodejs.repository, nodejs.npm_cli_js))),
    )

def node_version(module_ctx, node):
    """The version of the hermetic Node.js, part of the facts key of `wasmjs` resolutions: a change
    of the npm that resolves the klibs' NPM closure may change the resolution."""
    result = module_ctx.execute([node, "--version"])
    if result.return_code:
        fail("Could not run the hermetic Node.js %s to resolve the `wasmjs` platform:\n%s" % (
            node,
            result.stderr,
        ))
    return result.stdout.strip()

def resolver_binary(module_ctx, resolver_tag):
    """The resolver binary of the host as (path, sha256): a local `local_<host>` label if the tag
    provides one for the host, the released binary pinned by `version` and `sha256` otherwise.

    The returned sha256 is part of the resolution facts key: a release upgrade or a rebuilt local
    binary invalidates every cached resolution.
    """
    host = host_key(module_ctx)

    local_label = getattr(resolver_tag, _LOCAL_LABEL_ATTR_BY_HOST[host], None)
    if local_label != None:
        binary_path = module_ctx.path(local_label)
        if not binary_path.exists:
            fail("The resolver binary %s of the kmp.resolver tag does not exist; build it in the resolver checkout with './kotlin do buildNativeImage' and copy it to the label." % local_label)
        return struct(
            path = binary_path,
            sha256 = _sha256_of_file(module_ctx, binary_path),
            version = "local",
        )

    if not resolver_tag.version:
        fail("The kmp.resolver tag pins neither a release (version) nor a local binary for host %s; configure kmp.resolver(version = ..., sha256 = {...}) or the local_%s label." % (
            host,
            host,
        ))

    asset = _RESOLVER_ASSET_BY_HOST[host]
    sha256_key = _SHA256_KEY_BY_HOST[host]
    sha256 = resolver_tag.sha256.get(sha256_key)

    # ARM64 Windows reuses the x64 release asset, so its checksum is optional.
    if not sha256 and host == "windows_arm64":
        sha256 = resolver_tag.sha256.get(_SHA256_KEY_BY_HOST["windows_x64"])

    if not sha256:
        fail("The kmp.resolver tag lacks the '%s' sha256 of the resolver %s binary; pin the checksums of the release assets." % (sha256_key, resolver_tag.version))

    # The extension's working directory persists across evaluations. Overwriting an executable that
    # already ran in place makes macOS kill the new one with "Code Signature Invalid" (the kernel
    # caches the signature per file), so the binary is named by version: a new version lands in a
    # new file.
    download_path = "%s-%s" % (resolver_tag.version, asset)
    module_ctx.download(
        url = [_RESOLVER_BINARY_URL_PREFIX % resolver_tag.version + "/" + asset],
        output = download_path,
        sha256 = sha256,
        executable = True,
    )
    return struct(
        path = module_ctx.path(download_path),
        sha256 = sha256,
        version = resolver_tag.version,
    )

def resolve_fresh(module_ctx, install, binary, node):
    """Runs the resolver for one validated install; returns the resolution manifest JSON.

    [node] is the hermetic node/npm tooling, required when the install resolves the `wasmjs`
    platform, None otherwise: fetching Node.js for a JVM-only resolution would be wasteful.
    """
    resolution_path = "resolution-%s.json" % install.name
    module_ctx.file(resolution_path, "")
    args = [
        binary.path,
        "--output-manifest-file",
        module_ctx.path(resolution_path),
    ]
    if node != None:
        args.extend(["--node-executable", node.node, "--npm-cli-js", node.npm_cli_js])
    for platform in sorted(install.platforms):
        args.extend(["--platform", platform])
    for coordinate in install.artifacts:
        args.extend(["--coordinate", coordinate])
    for repository in install.repositories:
        args.extend(["--repository", repository])
    for source_module_id in sorted(install.substitutions.keys()):
        args.extend(["--substitution", "%s=%s" % (source_module_id, install.substitutions[source_module_id])])
    for package_name in sorted(install.npm_package_version_overrides.keys()):
        args.extend(["--npm-package-version", "%s=%s" % (package_name, install.npm_package_version_overrides[package_name])])
    if install.npm_registry:
        args.extend(["--npm-registry", install.npm_registry])

    # Read NETRC without taking it into account as an input of the resolution: authentication does
    # not matter in the reproducibility of the resolution. Only basic authentication is supported.
    netrc = module_ctx.os.environ.get("NETRC", "")
    repository_credentials = _repository_credentials(module_ctx, install.repositories, netrc)
    if repository_credentials:
        credentials_path = "repository-credentials-%s.json" % install.name
        module_ctx.file(credentials_path, json.encode(repository_credentials), executable = False)
        args.extend(["--repository-credentials-file", module_ctx.path(credentials_path)])

    # Bazel credential helpers for the resolver, `[scope=]path` entries separated by `;` with
    # `%workspace%` standing for the workspace root, as Bazel's own `--credential_helper`. Read like
    # NETRC above, without being an input of the resolution. `KMP_RESOLVER_CREDENTIAL_HELPER` is kept
    # for bazelrc compatibility with the intellij-community setup; the new spelling wins when set.
    for env_name in ["RULES_KOTLIN_EXTERNAL_CREDENTIAL_HELPER", "KMP_RESOLVER_CREDENTIAL_HELPER"]:
        helpers = module_ctx.os.environ.get(env_name, "")
        if helpers:
            for helper in [h for h in helpers.split(";") if h]:
                args.extend(["--credential-helper", helper.replace("%workspace%", str(_workspace_root(module_ctx)))])
            break

    # Counterparts of Bazel's `--credential_helper_timeout` (10s) and
    # `--credential_helper_cache_duration` (30m), same duration syntax; the resolver applies the
    # same defaults when they are unset.
    for env_name, flag in [
        ("RULES_KOTLIN_EXTERNAL_CREDENTIAL_HELPER_TIMEOUT", "--credential-helper-timeout-ms"),
        ("RULES_KOTLIN_EXTERNAL_CREDENTIAL_HELPER_CACHE_DURATION", "--credential-helper-cache-duration-ms"),
        ("KMP_RESOLVER_CREDENTIAL_HELPER_TIMEOUT", "--credential-helper-timeout-ms"),
        ("KMP_RESOLVER_CREDENTIAL_HELPER_CACHE_DURATION", "--credential-helper-cache-duration-ms"),
    ]:
        duration = module_ctx.os.environ.get(env_name, "")
        if duration:
            args.extend([flag, str(duration_to_millis(duration, env_name))])

    args.extend(["--cache-dir", resolver_cache_dir(module_ctx)])

    result = module_ctx.execute(
        args,
        quiet = True,
        timeout = 3600,
    )
    if result.return_code:
        fail("KMP resolver failed with exit code %s.\nstdout:\n%s\nstderr:\n%s" % (
            result.return_code,
            result.stdout,
            result.stderr,
        ))

    return module_ctx.read(resolution_path)

def resolver_cache_dir(module_ctx):
    """The resolver's cache of Maven metadata and artifacts, `<output_base>/kmp-resolver-cache`.

    Bazel wipes the extension's working directory after each evaluation: a directory next to
    `modextwd` keeps the cache across resolutions, until `bazel clean --expunge` deletes the output
    base. The cache never changes the resolution, it only spares the downloads.
    """
    modextwd = module_ctx.path(".").dirname
    if modextwd.basename != "modextwd":
        fail("Expected the working directory of the kmp extension to be in <output_base>/modextwd, got %s" % module_ctx.path("."))
    return str(modextwd.dirname.get_child("kmp-resolver-cache"))

def _workspace_root(module_ctx):
    return module_ctx.path(Label("@@//:MODULE.bazel")).dirname

_MILLIS_PER_UNIT = {"ms": 1, "s": 1000, "m": 60 * 1000, "h": 60 * 60 * 1000, "d": 24 * 60 * 60 * 1000}

def duration_to_millis(duration, what):
    """Converts a duration written as Bazel's duration flags expect it (`500ms`, `10s`, `30m`, `1h`, `1d`, or `0`)."""
    if duration == "0":
        return 0
    digits = duration.rstrip("dhms")
    unit = duration[len(digits):]
    if not digits.isdigit() or unit not in _MILLIS_PER_UNIT:
        fail("%s must be a duration such as 10s, 30m, 1h or 500ms, got %r" % (what, duration))
    return int(digits) * _MILLIS_PER_UNIT[unit]

def _repository_credentials(module_ctx, repositories, netrc):
    auth = get_auth(_auth_context(module_ctx, netrc), repositories)
    credentials = []
    for repository in repositories:
        repository_auth = auth.get(repository)
        if repository_auth == None:
            continue

        auth_type = repository_auth.get("type")
        login = repository_auth.get("login")
        password = repository_auth.get("password")
        if auth_type != "basic" or not login or not password:
            fail("The KMP resolver supports only basic repository auth for %s, but get_auth returned %s auth." % (
                repository,
                auth_type,
            ))

        credentials.append({
            "repositoryUrl": repository,
            "username": login,
            "password": password,
        })
    return credentials

def _auth_context(module_ctx, netrc):
    # `get_auth` expects a `repository_ctx`; `module_ctx` provides the members it reads.
    return struct(
        attr = struct(
            auth_patterns = {},
            netrc = netrc,
        ),
        os = module_ctx.os,
        path = module_ctx.path,
        read = module_ctx.read,
    )

def _sha256_of_file(module_ctx, path):
    """The sha256 of a local file, for the facts key of locally built resolver binaries."""
    probes = [
        (["sha256sum", str(path)], 0),
        (["shasum", "-a", "256", str(path)], 0),
        (["certutil", "-hashfile", str(path), "SHA256"], 1),
    ]
    for args, hash_line in probes:
        result = module_ctx.execute(args, quiet = True)
        if result.return_code == 0:
            lines = [line for line in result.stdout.splitlines() if line.strip()]
            if len(lines) > hash_line:
                return lines[hash_line].strip().split(" ")[0].lower()
    fail("Could not compute the sha256 of the local resolver binary %s: none of sha256sum, shasum or certutil ran successfully." % path)
