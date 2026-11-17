# Gradle Test Projects

This directory contains test 'Gradle Projects' that can be used to compare the resolver against the
Gradle resolution.

Update the projects with `./kotlin do updateGradleGoldenData` from the repository root (runs the projects'
Gradle wrapper, which requires a local Java installation).

## Golden Data

The golden data is located in 'golden-data/{project name}/{targetName}.txt'

```
{ for each artifact in the project }
  {{GAV}}
  {{SHA 512 hash of the artifact}}
```

## Projects

### kmp-coroutines

Defines a Kotlin Multiplatform project that uses coroutines.

### kmp-popular

Defines a Kotlin Multiplatform project that uses a selection of popular Kotlin Multiplatform
libraries (ktor-client, koin, okio, kotlinx-collections-immutable, kotlinx-datetime,
kotlinx-serialization-json, kermit, multiplatform-settings).

### kmp-sqliter

Defines a Kotlin Multiplatform project that uses sqliter.
