# Source Package Target Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate complete backend source archives that exclude only the repository build output while preserving Java source packages named `target`.

**Architecture:** Add one repository-local packaging script with an explicit source allowlist. Because the root `target` directory is not an input, no recursive `*/target/*` exclusion is needed and `src/main/java/com/axonlink/ai/daoindex/target/TargetDataSourceRegistry.java` remains in the archive.

**Tech Stack:** Bash, Info-ZIP `zip`/`unzip`, Maven project layout.

## Global Constraints

- Preserve all existing dirty-worktree changes.
- Do not include the repository root `target`, `.git`, editor metadata, logs, or existing ZIP files.
- Preserve every directory below the selected source roots, including directories named `target`.
- Do not commit, merge, or push.

---

### Task 1: Add A Repeatable Source Archive Script

**Files:**
- Create: `scripts/package-source.sh`

**Interfaces:**
- Consumes: optional output ZIP path as argument 1.
- Produces: a verified backend source ZIP containing the selected project source paths.

- [x] **Step 1: Verify the current archive fails the required package-content assertion**

```bash
unzip -Z1 axon-link-server-source-20260811-missing-active-auto-repair.zip \
  | rg '^src/main/java/com/axonlink/ai/daoindex/target/TargetDataSourceRegistry.java$'
```

Expected: exit 1 because the required tracked source file is absent.

- [x] **Step 2: Create the minimal packaging script**

The script must resolve the repository root from its own location, accept an optional output path, remove only that exact output file before regeneration, and archive this allowlist:

```text
pom.xml src scripts docs specs build.sh start.sh stop.sh compile-and-index.sh .gitignore
```

It must exclude `.DS_Store` only. It must not use a `target` wildcard.

- [x] **Step 3: Regenerate the requested source archive**

```bash
scripts/package-source.sh axon-link-server-source-20260811-missing-active-auto-repair.zip
```

- [x] **Step 4: Verify the archive contract**

```bash
unzip -tq axon-link-server-source-20260811-missing-active-auto-repair.zip
unzip -Z1 axon-link-server-source-20260811-missing-active-auto-repair.zip \
  | rg '^src/main/java/com/axonlink/ai/daoindex/target/TargetDataSourceRegistry.java$'
test -z "$(unzip -Z1 axon-link-server-source-20260811-missing-active-auto-repair.zip | rg '^target/' || true)"
```

Expected: archive integrity passes, the Java source file is present, and no root build output is present.
