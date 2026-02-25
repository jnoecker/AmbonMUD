---
description: Deep review of codebase for duplicated logic, missing abstractions, and refactoring opportunities
allowed-tools: Read, Glob, Grep, Bash(find:*), Bash(wc:*), Bash(sort:*)
---

You are performing a thorough architectural review of this codebase focused on identifying duplicated logic, missing abstractions, and opportunities to improve code reuse. Be specific and actionable — cite exact file paths and line ranges for every finding.

## Phase 1: Discovery

Start by understanding the project structure:
1. Map the directory layout and identify major modules/packages
2. Identify the primary language(s) and frameworks in use
3. Read build files (build.gradle, package.json, pom.xml, Cargo.toml, etc.) to understand module boundaries
4. Skim CLAUDE.md, README, or architectural docs if they exist

## Phase 2: Duplication Analysis

Search systematically for duplicated patterns. For each category below, scan the entire codebase:

### Exact and near-exact duplication
- Functions or methods with identical or near-identical bodies (>5 lines) in different files
- Copy-pasted blocks of logic with only variable names changed
- Repeated sequences of API/service calls that follow the same pattern
- Duplicated error handling, retry logic, or fallback patterns

### Structural duplication
- Classes or structs that share most of their fields/properties
- Multiple implementations of the same algorithm with slight variations
- Repeated patterns of "fetch, validate, transform, persist" or similar pipelines
- Test files that repeat the same setup/teardown/assertion patterns

### Configuration and boilerplate duplication
- Repeated dependency injection or wiring code
- Duplicated model mapping / DTO conversion logic
- Copy-pasted SQL queries or ORM query patterns with minor differences
- Repeated serialization/deserialization logic

## Phase 3: Abstraction Opportunities

Look beyond raw duplication for structural improvements:

### Missing interfaces or protocols
- Concrete classes used in multiple places that should share an interface
- Switch/when statements on type that suggest a missing polymorphic abstraction
- Functions that accept broad types but only use a narrow subset of their API

### Missing helper/utility methods
- Repeated inline expressions that could be named functions (e.g., date formatting, string manipulation, null-coalescing chains)
- Validation logic that appears in multiple places with the same rules
- Collection transformations (filter/map/reduce chains) that encode domain concepts but aren't named

### Missing base classes or traits
- Classes with overlapping method signatures and similar implementations
- Repeated lifecycle patterns (init/start/stop, open/close, connect/disconnect)

### Missing domain abstractions
- Primitive obsession — raw strings, ints, or maps used where a value object or enum would add clarity and safety
- Shotgun surgery indicators — a single conceptual change requires edits across many files in the same pattern

## Phase 4: Report

Organize your findings into a structured report with these sections:

### 🔴 High-Impact Duplication
Items where extracting a shared abstraction would eliminate significant repeated code (>20 lines duplicated or >3 occurrences). For each:
- **What**: Describe the duplicated pattern
- **Where**: List every file and line range involved
- **Suggested refactor**: Propose a specific extraction — name the new function, interface, class, or module and describe its contract
- **Estimated impact**: Lines eliminated or files simplified

### 🟡 Medium-Impact Opportunities
Moderate duplication (10-20 lines or 2-3 occurrences) or missing abstractions that would improve clarity. Same format as above.

### 🟢 Minor / Style Improvements
Small extractions, utility methods, or naming improvements. Brief descriptions are fine here.

### 📐 Architectural Suggestions
Higher-level observations about module boundaries, dependency direction, or patterns that could prevent future duplication. These don't need line-level citations.

## Guidelines

- **Be specific.** Every finding must reference real files and line numbers. Do not make generic suggestions.
- **Be conservative.** Only suggest extractions where the shared abstraction is genuinely simpler than the duplication. Don't abstract for abstraction's sake.
- **Respect existing patterns.** If the codebase already uses a particular style or framework convention, align your suggestions with it.
- **Consider the blast radius.** Note if a suggested refactor would touch many files or require coordinated changes.
- **Prioritize by ROI.** Rank findings by (effort to refactor) vs. (ongoing maintenance burden of the duplication).
- If the codebase is large, focus on the $ARGUMENTS directories/modules if specified. Otherwise review the entire codebase.
