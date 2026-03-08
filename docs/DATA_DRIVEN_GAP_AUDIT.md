# Data-Driven Gap Audit

This consolidated audit captures remaining code-level constraints that still require Kotlin changes, even after major systems were moved to configuration.

---

## 1) Command language is still hardcoded
- The parser hardcodes verbs, aliases, and syntactic patterns (e.g., `say`, `tell`, `look`, movement shorthands, `guild` subcommands).
- Help text is also hardcoded as a static multiline string.

**Opportunity**
- Move command metadata to config: verb aliases, usage strings, permission tags, and optional argument patterns.
- Generate help output from this same config to keep docs and parser behavior in sync.

**Current anchors in code**
- `CommandParser` alias/grammar logic.
- `UiHandler.handleHelp()` static command list.

## 2) Behavior-tree templates are code-defined
- Mob behavior templates are enumerated in Kotlin (`aggro_guard`, `patrol`, `wander`, etc.), then mapped by `when` branches.

**Opportunity**
- Define behavior trees declaratively in data (YAML/JSON DSL) using composable nodes and parameters.
- Keep action/condition primitives in code, but let content authors compose them without changing Kotlin.

**Current anchors in code**
- `BehaviorTemplates.templateNames` and `BehaviorTemplates.resolve()`.

## 3) Quest objective/completion logic is partially hardcoded
- World loading accepts objective/completion strings, but runtime quest progression only implements specific objective types (`kill`, `collect`) and completion semantics (`auto`, `npc_turn_in`) in code branches.

**Opportunity**
- Introduce registry-driven objective/completion handlers keyed by type ID.
- Bind handler selection via config and validate quest types against registered handlers at startup.

**Current anchors in code**
- Runtime checks in `QuestSystem.onMobKilled()`, `QuestSystem.onItemCollected()`, and auto-completion path.
- String normalization in `WorldLoader` without full runtime handler registration.

## 4) Recall rules are fixed in handler constants
- Recall cooldown is a hardcoded constant in `NavigationHandler`.

**Opportunity**
- Move recall cooldown and messaging templates to config (`engine.navigation.recall.*`).
- Optionally support zone/race/class modifiers as data.

**Current anchors in code**
- `NavigationHandler.RECALL_COOLDOWN_MS` and hardcoded recall text.

## 5) Stat identity still assumes classic six stats in player model
- `PlayerState`/`PlayerRecord` store STR/DEX/CON/INT/WIS/CHA as fixed fields/defaults.
- Login formatting and persistence mappings still rely on those concrete keys.

**Opportunity**
- Migrate persisted player stats to map-based storage (`Map<String, Int>`) end-to-end.
- Keep compatibility migration for legacy fixed-field saves.

**Current anchors in code**
- Fixed stat fields/defaults in `PlayerState` and `PlayerRecord`.
- Manual field mappings in player conversion and persistence layers.

## 6) Guild role semantics are key-string coupled in runtime logic
- Runtime authorization checks compare literal rank IDs (`leader`, `officer`, `member`), even though rank config exists.

**Opportunity**
- Make authorization permission-based only (from rank config), with no special-cased rank IDs in business logic.
- Add configurable rank transition constraints (who can promote/demote whom) as policy data.

**Current anchors in code**
- String-based rank checks in `GuildSystem` command operations.

## 7) Effect/ability type handling still requires code branches
- Validation whitelists effect/target type IDs.
- `StatusEffectSystem` behavior for DOT/HOT/stat/shield/root/stun is selected through explicit code branches.

**Opportunity**
- Keep a primitive-effect engine in code but interpret effect pipelines from data (e.g., on-apply/on-tick/on-expire operations).
- Replace hardcoded type allowlists with registered operation sets and schema validation.

**Current anchors in code**
- Type allowlists in `AppConfig.validated()`.
- Branching execution in `StatusEffectSystem` tick/apply paths.

## 8) System/player-facing text is mostly embedded in Kotlin
- Many gameplay/system strings are inline across command handlers and systems.

**Opportunity**
- Externalize message templates to locale/content packs with parameter substitution.
- This enables no-code tuning of tone, accessibility, and localization.

**Current anchors in code**
- Inline `OutboundEvent.SendText/SendInfo/SendError` strings across handlers/systems.

---

## Suggested rollout order
1. Externalize command/help metadata.
2. Parameterize recall + other handler constants.
3. Decouple guild auth from fixed rank IDs.
4. Add registry-backed quest objective/completion handlers.
5. Move behavior templates to declarative trees.
6. Migrate stats persistence to dynamic map model.
7. Introduce data-driven effect operation pipelines.
8. Externalize gameplay text to message packs.

## Cross-cutting safeguards for rollout
- Add startup validation that rejects unknown command/quest/effect type IDs.
- Preserve backward compatibility with migration adapters for player save formats.
- Gate each subsystem migration behind config flags for staged rollout.
- Add integration fixtures for content-only changes to prevent runtime regressions.
