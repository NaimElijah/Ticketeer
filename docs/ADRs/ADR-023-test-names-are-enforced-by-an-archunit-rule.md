# ADR-023: Test names are enforced by an ArchUnit rule

- **Status:** Accepted
- **Date:** 2026-11-04
- **Issue:** #189 (test-suite scan)

## Context

The project's test names read `Given<scenario>_When<action>_Then<outcome>`, a rule that lives only in written
instructions. At #189, 944 of the 3,140 test methods broke it: some in lowercase, some named after the method
under test, many with no stated scenario at all.

Most of that was inherited, not new. On 2026-07-08, when this repo's own work began, 937 of 1,409 tests (67%) already broke
it, and the count stayed near 940 as 1,726 tests were added. Of those added, 74 (4%) broke it. So the
instruction mostly held, and it still leaked.

A test name is what a reader sees when the test fails, and `requirements.md` cites test names as evidence that
a requirement holds, so a name that omits its scenario costs something every time it is read.

## Decision

- `architecture/TestConventionsTest` fails the build on a test method whose name does not match
  `Given[A-Z0-9][A-Za-z0-9]*_When[A-Z0-9][A-Za-z0-9]*_Then[A-Z0-9][A-Za-z0-9]*`: exactly three sections.
- The rule selects methods meta-annotated with JUnit's `@Testable`, which covers `@Test` and, through
  `@TestTemplate`, `@ParameterizedTest` and `@RepeatedTest`.
- It scans all test classes, abstract contract tests and `@Disabled` stubs included. The only exclusion is the
  deliberate offenders nested in `TestConventionsTest` itself.
- Self-tests prove it fails on a lowercase name, a lowercase letter after a keyword, a two-part parameterized
  name, a missing `When`, a missing `Then` and an extra section, and that it ignores a badly named helper.
  Another proves the import really holds an abstract contract test, because a rule that matches nothing checks
  nothing.
- The 944 names were renamed from a reviewed map, one commit per test folder, with a byte-level proof that only
  names and their citations changed.

[ADR-011](ADR-011-archunit-module-map-as-the-boundary-gate.md) cites the rule `bounded_contexts_are_acyclic`.
ADRs are append-only, so it stays as written. That test is now
`GivenProductionCode_WhenScanned_ThenBoundedContextsAreAcyclic`.

## Consequences

**What this makes easy:**

- A new test with a bad name fails in CI, on the required check, with the offending method in the message.
- No baseline file to maintain: the only exclusion is the rule's own offenders.

**What this makes hard:**

- Every test needs a scenario, even one with little to set up. Some `Given` sections read thinly as a result,
  such as `GivenTheTestClasspath`. That cost is accepted, since a thin scenario still says what the test assumes.
- The rule checks the form and not the truth: `GivenX_WhenY_ThenZ` can still misdescribe its body. Review and
  the test-suite scan catch that, and the rule does not.
- The rule imports every test class on each run. `TestConventionsTest` took about 5 seconds on the maintainer's
  machine. It imports them locally, not in a static, so the classes are not held for the rest of the shared
  test JVM.
- Any linter must accept this form in test sources. #46 plans one, and its acceptance is a clean baseline, so a
  default method-name rule that expects a lowercase first letter would have to be configured for tests.

## Alternatives considered

- **Rename only, with no rule.** Rejected: the instruction let 4% of added tests through, which rebuilds the
  backlog slowly, and a rule costs a few seconds with no new dependency.
- **Checkstyle's `MethodName` check, scoped to test methods.** Rejected: a new build plugin and configuration
  for one rule, in a repo with no lint step, whereas ArchUnit is already a test dependency and runs in the
  required check. Checkstyle also matches on source, so it would need its own idea of which methods are tests.
- **Put the rule in `HexagonalRulesTest`.** Rejected: that class imports the opposite set, production classes
  only, and holds them in a static, and ADR-011 scopes it as the boundary gate. A test-name rule is no boundary
  rule.
- **`FreezingArchRule`, keeping the old names as a baseline.** Rejected: it would leave the 944 names in place
  and add a stored violations file to maintain.

**Revisit when** #46 adopts a linter that can tell a test method from a helper as reliably, when the import
pushes this class past 30 seconds in CI, or when a low-memory kill of a test run is traced to it.
