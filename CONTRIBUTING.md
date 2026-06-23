# Contributing

## Release instructions

> Tag format is `^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$`

- Trigger https://github.com/JetBrains/bazel-kmp-resolver/actions/workflows/publish.yml manually setting the version in the tag input field
  - OR, push a tag using `git` CLI directly
- That will trigger a CI that will create a new immutable release with the relevant artifacts attached.
