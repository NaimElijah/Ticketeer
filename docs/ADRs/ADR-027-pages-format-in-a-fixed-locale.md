# ADR-027: Pages format dates, numbers and case in one fixed locale

- **Status:** Accepted
- **Date:** 2026-11-08
- **Issue:** #279 (rest of #189, test-suite scan)

## Context

Every page is written in English and declares it (`lang="en"`, `PageLanguageTest`). The text the `ui` module
builds around that copy, though, followed the server JVM's default locale: date formatters built with
`DateTimeFormatter.ofPattern(String)`, money and counts built with `String.format(String, …)`, and case
changes with `toLowerCase()`/`toUpperCase()` all take it. On a server set to German the receipt read
"9 Okt. 2026" and a price "$1.234,50"; under a locale with digits of its own even `%02d` changed; on a Turkish
one "Manage Inventory" lower-cased to "manage ınventory", and a `switch` on a lowered category missed "music".
Which locale a host runs in is a deployment accident, so the pages changed with the machine they ran on.

No requirement asks for a second language, and nothing in the product chooses a language per visitor.

## Decision

- `ui/support/DisplayLocale.PAGE` (`Locale.ENGLISH`, the language the pages declare) is the one locale the pages
  format in. Text a page shows (dates, money, counts, a case change) is built with it.
- Machine text (a switch key, a CSS value, a JSON escape) names `Locale.ROOT`.
- Every UI starts in `PAGE` (`ui/support/PageLocaleBootstrap`). With no `I18NProvider`, Vaadin gives each UI the JVM's
  default, and its own components, the date pickers among them, format and parse in the UI's locale, a path the
  rule below cannot see. `PageLocaleBootstrapTest` pins it.
- `HexagonalRulesTest` refuses, in `ui` production code, the calls that fall back on the JVM's default locale:
  the `java.time` and `java.text` formatters given none, `String.format(String, …)` and `formatted`,
  `Locale.getDefault`, and case changes given none. The full list is in the rule and in `.claude/CLAUDE.md`'s
  Enforcement paragraph. Its fixtures prove it fails on each listed call, method references included.
  `SimpleDateFormat`, `DateFormat` and `java.util.Formatter` are not on the list: `ui` uses none of them.
- `PageLocaleDatesTest`, `PageLocaleNumbersTest` and `PageLocaleCaseTest` state the behaviour under a German,
  an Arabic and a Turkish default. The rule, not those tests, is the regression guard: the date formatters
  are static, so a test catches a regression only in a view no earlier test in the same JVM has loaded.
- The backend is left out of the rule. It formats `%s`, and an ungrouped `%d` in the seed report, a boot log
  no page shows, and changes the case of two machine keys no page shows either.

## Consequences

**What this makes easy:**

- The pages read the same on any host, whatever its locale, and a build fails on a new call that would
  bring the host's locale back.

**What this makes hard:**

- Every format call and case change in `ui` names a locale, which is more to type and to read.
- A `java.text` or `java.util` formatter the rule does not list would pass it unseen; adding one to `ui`
  means adding it to the rule.
- A second page language needs more than a new constant: the pages' copy is English text in the views, so it
  needs a translation layer as well as a per-visitor locale in place of `PAGE`.

## Alternatives considered

- **Leave the JVM's default, and set `-Duser.language=en` on every host.** Rejected: a deployment setting that
  nothing checks; the first host that omits it shows German or Arabic figures on an English page.
- **Format in the visitor's browser locale.** Rejected for now: the copy around the figures is English, so a
  page would mix an English sentence with German numbers; and no requirement asks for it.
- **Fix the call sites and add no rule.** Rejected: the static formatters make a behaviour test order-dependent,
  so a regression could pass CI.

**Revisit when** a requirement asks for a second page language: the per-visitor locale and a translation layer
replace `PAGE` together, and this rule then requires that locale instead.
