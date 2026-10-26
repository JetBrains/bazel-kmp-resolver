# Instructions for AI agents

- After changing Kotlin sources, run `./kotlin do format` before running the tests, committing, or reporting the work
  done. Do not format or reflow Kotlin by hand instead, and do not configure another formatter.
- Commit the reformatting together with the change it belongs to, never as a separate "format" commit.
- Run the tests with `./kotlin test`.

## Code style

- Branch with `when` expressions rather than early returns (`?: return`, `if (...) return`, `return@label`).
- Build strings with templates, never with `+` concatenation.
- In tests, scope resources with helpers taking the test body as a lambda, such as
  `withLocalHttpServer(handler) { server -> ... }` or `runBlocking { supervisorScope { ... } }`, rather than fields set up
  and torn down with `@BeforeTest`/`@AfterTest`.
