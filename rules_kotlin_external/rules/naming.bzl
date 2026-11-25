"""Maven coordinate and Bazel name utilities, ported from intellij-community's `fleet/build/rules/kmp.bzl`.

Target names are lowercased with every character that is not a letter, digit, or `_` replaced by
one `_` (`io.ktor:ktor-client-core-jvm:3.6.0` → `io_ktor_ktor_client_core_jvm_3_6_0`); repository
name parts keep the case and replace dots and dashes by `_`.
"""

def sanitize_name_part(value, allowed, lowercase = False, force_separator_chars = ""):
    """Replaces every character of [value] that is not [allowed] by a single `_`.

    Consecutive replaced characters collapse into one `_`, and leading/trailing ones are stripped.
    Characters in [force_separator_chars] are replaced even when the previous character already was
    a separator. With [lowercase], allowed characters are lowercased.
    """
    chars = []
    previous_was_separator = False
    for i in range(len(value)):
        c = value[i]
        if c in allowed:
            chars.append(c.lower() if lowercase else c)
            previous_was_separator = False
        elif c in force_separator_chars or not previous_was_separator:
            chars.append("_")
            previous_was_separator = True
    return "".join(chars).strip("_")

_TARGET_NAME_ALLOWED_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"

_REPOSITORY_NAME_ALLOWED_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"

def build_target_name(value):
    """The Bazel target name of a Maven coordinate or module id: lowercase, version included."""
    return sanitize_name_part(
        value,
        allowed = _TARGET_NAME_ALLOWED_CHARS,
        lowercase = True,
    )

def repository_name_part(value):
    """The Bazel repository name part of a Maven coordinate part or an NPM package name: case kept,
    dashes and dots replaced by `_` so that repository names read with underscores throughout."""
    return sanitize_name_part(
        value,
        allowed = _REPOSITORY_NAME_ALLOWED_CHARS,
        force_separator_chars = ".-",
    )

def maven_coordinate_parts(coordinate):
    """Splits a `group:artifact:version` coordinate, failing on anything else."""
    parts = coordinate.split(":")
    if len(parts) != 3 or not parts[0] or not parts[1] or not parts[2]:
        fail("Expected Maven coordinate group:artifact:version, got: %s" % coordinate)
    return parts

def maven_module_id(maven_id):
    """The `group:artifact` module id of a `group:artifact[:version]` id."""
    parts = maven_id.split(":")
    if len(parts) != 2 and len(parts) != 3:
        fail("Expected Maven module group:artifact or coordinate group:artifact:version, got: %s" % maven_id)
    if not parts[0] or not parts[1]:
        fail("Expected Maven module group:artifact or coordinate group:artifact:version, got: %s" % maven_id)
    if len(parts) == 3 and not parts[2]:
        fail("Expected Maven coordinate group:artifact:version, got: %s" % maven_id)
    return "%s:%s" % (parts[0], parts[1])

def validate_maven_module_id(module_id):
    parts = module_id.split(":")
    if len(parts) != 2 or not parts[0] or not parts[1]:
        fail("Expected Maven module group:artifact, got: %s" % module_id)

def versionless_target_name(coordinate):
    """The versionless target name of a Maven coordinate or module id (its module id's target name)."""
    return build_target_name(maven_module_id(coordinate))

def basename_from_url(url):
    """The file name of a URL, ignoring its query and fragment, if any."""
    stripped = url.split("?", 1)[0].split("#", 1)[0]
    return stripped.rsplit("/", 1)[-1]

def strip_file_extension(basename):
    if "." not in basename:
        return basename
    return basename.rsplit(".", 1)[0]

def artifact_classifier(basename, artifact_id, version):
    """The Maven classifier of an artifact file name, `""` when it has none.

    Only the well-known classifier is kept: `foo-jvm-1.0-sources.jar` has the classifier `sources`,
    other suffixes (e.g. `-samples-sources`) are returned as-is for the caller to disambiguate.
    """
    stem = strip_file_extension(basename)
    prefix = "%s-%s" % (artifact_id, version)
    if stem == prefix:
        return ""
    if stem.startswith(prefix + "-"):
        return stem[len(prefix) + 1:]
    return stem
