---
description: Audit all documentation, update to reflect current state, consolidate redundant docs, and refresh README and onboarding guide
allowed-tools: Read, Edit, Write, Glob, Grep, Bash(find:*), Bash(wc:*), Bash(cat:*), Bash(git log:*), Bash(git diff:*), Bash(ls:*), Bash(tree:*)
---

You are performing a comprehensive documentation audit and consolidation for this repository. The goal is to ensure all documentation accurately reflects the current state of the codebase, eliminate redundancy by merging related documents, and produce a polished README and developer onboarding guide.

## Phase 1: Inventory

Start by building a complete picture of what exists:

1. **Find every markdown file** in the repository:
   ```
   find . -name '*.md' -not -path '*/node_modules/*' -not -path '*/.git/*' -not -path '*/build/*' -not -path '*/target/*'
   ```

2. **Catalog each file** with:
   - File path
   - Title / apparent purpose (from first heading or filename)
   - Approximate length (line count)
   - Last meaningful edit: `git log -1 --format='%ad (%ar)' -- <file>`
   - Category: README, onboarding/setup, architecture, planning/roadmap, API docs, ADR/decision record, runbook/operations, changelog, contributing, or other

3. **Read every markdown file** fully. Do not skim. You need the complete content of each to do the consolidation work in later phases.

4. **Present the inventory** as a table sorted by category, noting which files appear stale, redundant, or overlapping.

## Phase 2: Codebase Reality Check

Before rewriting anything, establish ground truth about the current state:

1. **Project structure**: Run `tree` or `ls -R` on the main source directories to understand the current module layout
2. **Build system**: Read build files (build.gradle, settings.gradle, package.json, pom.xml, Makefile, etc.) to identify current dependencies, build commands, and project configuration
3. **Entry points and scripts**: Check for scripts/, Makefile targets, npm/gradle tasks — these are what developers actually run
4. **CI/CD**: Read any CI config files (.github/workflows/, Jenkinsfile, .gitlab-ci.yml, etc.)
5. **Environment setup**: Check for Docker files, .env.example, .tool-versions, .sdkmanrc, etc.
6. **CLAUDE.md / AGENTS.md**: Read these if they exist — they often contain the most up-to-date project conventions

Compile a "source of truth" summary of:
- How to set up the development environment from scratch
- How to build, test, lint, and run the project
- The current module/package structure and what each part does
- Key technologies, frameworks, and versions actually in use
- Any environment variables, secrets, or external services required

## Phase 3: Consolidation Plan

Based on Phase 1 and 2, create a consolidation plan. Present it to me before executing. The plan should:

### Identify documents to merge
- Multiple planning/roadmap documents → consolidate into a single document reflecting **current** plans and status only (drop completed items and outdated plans)
- Multiple architecture or design docs covering overlapping topics → merge into one coherent architecture document
- Scattered setup instructions that appear in multiple files → unify into the onboarding guide
- ADRs/decision records should generally stay separate (they're a log) unless they contradict each other

### Identify documents to update in place
- Files that are mostly accurate but have stale sections (wrong commands, removed features, renamed modules)
- Files that reference old directory structures, dependencies, or tools

### Identify documents to delete or archive
- Docs that are entirely superseded by a merged document
- Docs that describe features or components that no longer exist

### Propose the final documentation structure
- List every markdown file that will exist after consolidation
- For merged documents, list which source files were combined
- For the README and onboarding guide, outline the proposed sections

**Stop here and present the plan. Wait for my approval before proceeding.**

## Phase 4: Execute — README

Rewrite the README to reflect the current state of the project. Follow these principles:

- **Lead with what the project does** — a clear, concise description a new team member or external reader can understand in 30 seconds
- **Keep it scannable** — use sections with clear headings but don't overdo nesting
- **Every command must be verified** — only include build/run/test commands you confirmed work in Phase 2
- **Remove aspirational content** — the README describes what IS, not what's planned
- **Include**:
  - Project description and purpose
  - Tech stack and key dependencies (with versions if relevant)
  - Quick start (minimal steps to get running)
  - Link to the full onboarding guide for detailed setup
  - Project structure overview (brief — just the top-level modules and what they do)
  - How to run tests
  - How to contribute (or link to CONTRIBUTING.md)
  - Links to other documentation that still exists after consolidation

## Phase 5: Execute — Developer Onboarding Guide

Create or rewrite the developer onboarding guide as a comprehensive, step-by-step document for someone joining the project for the first time. Include:

- **Prerequisites**: exact tool versions, system requirements, accounts/access needed
- **Environment setup**: step-by-step from a clean machine to running the project, including:
  - Installing tools and dependencies
  - Cloning and initial build
  - Environment variables and configuration
  - Database setup, seed data, external services (if applicable)
  - Verifying everything works (what does a successful setup look like?)
- **Development workflow**: how to make changes, run tests, lint, format, and submit PRs
- **Architecture overview**: enough context to navigate the codebase — what are the main modules, how do they relate, where does a new feature typically go?
- **Common tasks**: how to add a new endpoint, write a test, update a dependency, etc. (only if this info existed in the source docs)
- **Troubleshooting**: known gotchas, common setup failures, and their fixes (pull from existing docs and CLAUDE.md)
- **Cloud development**: if there are cloud/remote dev environment notes in existing docs, include a section on working in Claude Code cloud environments

## Phase 6: Execute — Remaining Documents

For each remaining document in the consolidation plan:

### Merged documents
- Combine the source documents into a single coherent file
- Eliminate duplication — don't just concatenate
- Rewrite for consistency in tone and structure
- Remove completed/outdated items from planning docs — only keep current and future items
- Add a brief note at the top if the document replaces previously separate files
- Use clear section headings

### Updated documents
- Fix stale references (commands, paths, module names, dependencies)
- Remove sections about features or components that no longer exist
- Ensure consistency with what you learned in Phase 2

### Deleted documents
- Do not delete files. Instead, for each file that's been fully superseded, replace its content with a single line redirecting to the new location:
  `# This document has been consolidated into [Document Name](./path-to-new-doc.md)`

## Phase 7: Summary

After all changes are complete, present a summary:
- List of every file created, modified, or redirected
- Brief description of what changed in each
- Any documentation gaps you noticed that I should consider writing separately
- Any questions or ambiguities you found that I should clarify

## Guidelines

- **Accuracy over completeness**: if you aren't sure something is still true, flag it with a `<!-- TODO: verify -->` comment rather than guessing
- **Preserve valuable content**: don't discard useful information during consolidation — integrate it
- **Keep commit history in mind**: if a file has important git history, prefer updating it over deleting and recreating
- **Match the project's voice**: if existing docs are casual, stay casual. If they're formal, stay formal.
- **Don't touch**: CLAUDE.md, AGENTS.md, LICENSE, CHANGELOG.md, or anything in .github/ unless I explicitly ask
- Focus on $ARGUMENTS directories or files if specified, otherwise audit the entire repository
