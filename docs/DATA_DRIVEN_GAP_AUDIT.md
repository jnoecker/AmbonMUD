# Data-Driven Gap Audit

> **Status:** Technical improvement backlog. These are code-level constraints that could be data-driven but currently require Kotlin changes. None are bugs or blockers — they represent future extensibility opportunities.
>
> Items marked ✅ have been resolved by PRs #614–619 or subsequent work.

This consolidated audit captures remaining code-level constraints that still require Kotlin changes, even after major systems were moved to configuration.

---

## 1) Command language is still hardcoded ✅ Resolved (PR #617)
- ~~The parser hardcodes verbs, aliases, and syntactic patterns.~~
- ~~Help text is also hardcoded as a static multiline string.~~

**Resolution (PR #617):** Command metadata (~80 entries) externalized to config; help output auto-generated from metadata; verb aliases and usage strings are data-driven.

## 2) Behavior-tree templates are code-defined ✅ Resolved (PR #618)
- ~~Mob behavior templates are enumerated in Kotlin, then mapped by `when` branches.~~

**Resolution (PR #618):** Behavior trees are now composable via YAML DSL (14 node types). Action/condition primitives remain in Kotlin; content authors compose full trees in YAML without code changes.

## 3) Quest objective/completion logic is partially hardcoded ✅ Resolved (PR #616)
- ~~World loading accepts objective/completion strings, but runtime quest progression only implements specific objective types in code branches.~~

**Resolution (PR #616):** Registry-driven objective/completion handlers keyed by type ID; handler selection bound via config; startup validation rejects unknown type IDs.

## 4) Recall rules are fixed in handler constants ✅ Resolved (PR #614)
- ~~Recall cooldown is a hardcoded constant in `NavigationHandler`.~~

**Resolution (PR #614):** Recall cooldown and all 7 message strings moved to `engine.navigation.recall` config section.

## 5) Stat identity still assumes classic six stats in player model ✅ Resolved (PRs in data-driven stats series)
- ~~`PlayerState`/`PlayerRecord` store STR/DEX/CON/INT/WIS/CHA as fixed fields.~~

**Resolution:** `StatMap` replaces `StatBlock`; `PlayerRecord.stats` is a `Map<String, Int>`; `StatRegistry` drives all stat definitions from config. See `DATA_DRIVEN_STATS_PLAN.md` for full history.

## 6) Guild role semantics are key-string coupled in runtime logic ✅ Resolved (PR #615)
- ~~Runtime authorization checks compare literal rank IDs.~~

**Resolution (PR #615):** Guild ranks now use permission-based authorization from rank config (`hasPermission`, `outranks`, `level`). No special-cased rank IDs remain in `GuildSystem` business logic.

## 7) Effect/ability type handling still requires code branches ✅ Resolved (PR #619)
- ~~Validation whitelists effect/target type IDs.~~
- ~~`StatusEffectSystem` behavior selected through explicit code branches.~~

**Resolution (PR #619):** Effect type trait flags (`ticksDamage`, `ticksHealing`, `modifiesStats`, `absorbsDamage`) moved to data. Type allowlists replaced with registered operation sets. Startup validation rejects unknown type IDs.

## 8) System/player-facing text is mostly embedded in Kotlin
- Many gameplay/system strings are inline across command handlers and systems.

**Opportunity**
- Externalize message templates to locale/content packs with parameter substitution.
- This enables no-code tuning of tone, accessibility, and localization.

**Current anchors in code**
- Inline `OutboundEvent.SendText/SendInfo/SendError` strings across handlers/systems.

---

## 8) System/player-facing text is mostly embedded in Kotlin
- Many gameplay/system strings are inline across command handlers and systems.

**Opportunity**
- Externalize message templates to locale/content packs with parameter substitution.
- This enables no-code tuning of tone, accessibility, and localization.

**Current anchors in code**
- Inline `OutboundEvent.SendText/SendInfo/SendError` strings across handlers/systems.

---

## Status Summary (April 2026)

Items 1–7 are resolved. Item 8 (externalized gameplay text) remains as a future opportunity.

All previously suggested rollout steps are complete. The remaining cross-cutting enhancement is message pack externalization for full localization support.
