# ADR-028: Public contracts are documented; self-explanatory accessors are not

- **Status:** Accepted
- **Date:** 2026-11-09
- **Issue:** #190 (code-comment scan)

## Context

The project's standard asks for a doc comment on every public type, function and module, stating its purpose
and contract. The same standard says comments explain why, and that restating what the code plainly says is
noise.

For most of the public surface those two rules agree. For an accessor they do not: the only thing a Javadoc on
`getName()` can say is that it returns the name. At `2ac1dd5a`, a regex count found 201 of 537 public top-level
types and 891 public non-`@Override` methods and constructors without a doc comment. 195 of those 891 were `get` or
`is` accessors.

The acceptance line of #190 asks only for the top-level types, which would leave about 700 public methods with no
stated contract.

## Decision

- Every public top-level type, public constructor, public non-`@Override` method, interface method and
  `package-info.java` has a doc comment stating its purpose and contract.
- A plain `getX`, `isX` or `setX` with nothing to add is exempt. An accessor that has a contract its name does not
  carry (it may return null, it is in a stated unit, it computes its value) is not exempt.
- A record documents its components through `@param`, and only those whose meaning, unit, format or nullability is
  not obvious from their name and type.
- An `@Override` inherits the doc comment of the method it overrides unless it narrows the contract.
- Nothing in the build enforces this yet. Whether CI runs doclint belongs to the linter work in #46.

## Consequences

- A reader can call any public service, port or domain method without reading its body, and accessors stay free of
  comments that only repeat their names.
- "Nothing to add" is a judgement a reviewer has to make; a counting tool cannot.
- With no gate, new public code can arrive undocumented until #46 ships.

**Revisit when** #46 turns on `-Xdoclint:missing`. That check reports every undocumented public member, accessors
included, so at that point either the exemption is expressed in the linter's configuration or the accessors get
comments.

## Alternatives considered

- **Document every public function literally, accessors included.** Rejected: about 195 comments whose only content
  is the method's own name, against the same standard's rule on noise.
- **Document only the top-level types, as #190's acceptance line says.** Rejected: the contract a caller needs, such
  as which exceptions a service method throws, sits on the methods.
- **Add a doclint gate in the same change.** Rejected for now: the gate needs a decision on how to exempt accessors,
  and #46 already owns the choice of linter and its rules.
