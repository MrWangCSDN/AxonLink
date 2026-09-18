# Rename DaoIndex Target Package Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Rename the Java source package directory named `target` so generic source ZIP exclusion rules cannot omit `TargetDataSourceRegistry`.

**Architecture:** Move only `TargetDataSourceRegistry` from `com.axonlink.ai.daoindex.target` to `com.axonlink.ai.daoindex.datasource`. Keep the class name and Spring wiring unchanged, update all imports, and make the repository packaging script verify the source file is present in every generated ZIP.

**Tech Stack:** Java 17, Spring Boot, Maven, Bash, ZIP.

**Spec:** User-approved inline design in the current task; this configuration/packaging bug fix does not require an Obsidian architecture update.

## Global Constraints

- Preserve the public class name `TargetDataSourceRegistry`.
- Do not change datasource behavior or configuration keys.
- Do not include the repository root Maven `target/` directory in source ZIP files.
- Do not commit unrelated working-tree changes.

---

### Task 1: Rename the source package and protect source packaging

**Files:**
- Move: `src/main/java/com/axonlink/ai/daoindex/target/TargetDataSourceRegistry.java`
- Create: `src/main/java/com/axonlink/ai/daoindex/datasource/TargetDataSourceRegistry.java`
- Modify: Java files importing `com.axonlink.ai.daoindex.target.TargetDataSourceRegistry`
- Modify: `.gitignore`
- Modify: `scripts/package-source.sh`

**Interfaces:**
- Consumes: Existing `TargetDataSourceRegistry` constructors and methods.
- Produces: The same class under package `com.axonlink.ai.daoindex.datasource` and a source ZIP containing its source file.

- [ ] **Step 1: Verify the desired package path is absent from the current source ZIP**

Run `scripts/package-source.sh /tmp/axon-link-source-before.zip` and assert `src/main/java/com/axonlink/ai/daoindex/datasource/TargetDataSourceRegistry.java` is absent.

- [ ] **Step 2: Move the source file and update package/import declarations**

Change the package declaration and every production/test import from `com.axonlink.ai.daoindex.target` to `com.axonlink.ai.daoindex.datasource`.

- [ ] **Step 3: Add a packaging invariant**

After ZIP integrity validation, make `scripts/package-source.sh` fail unless the ZIP contains `src/main/java/com/axonlink/ai/daoindex/datasource/TargetDataSourceRegistry.java`.

- [ ] **Step 4: Run focused tests**

Run `mvn -Dtest=ReplayBaseDataSourceRegistryTest,ReplayBaseMetadataServiceTest test` and expect zero failures/errors.

- [ ] **Step 5: Verify the generated source ZIP**

Run `scripts/package-source.sh /tmp/axon-link-source-after.zip`, verify the new path exists, and verify no entry starts with root-level `target/`.

- [ ] **Step 6: Run full validation**

Run `mvn test` and `git diff --check`; expect zero failures/errors and no whitespace errors.
