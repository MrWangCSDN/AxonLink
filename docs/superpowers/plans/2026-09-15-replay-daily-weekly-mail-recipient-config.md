# Replay Daily/Weekly Mail Recipient Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give replay daily-report and weekly-report email dialogs independent deployment defaults for To and CC recipients while retaining the shared SMTP sender.

**Architecture:** Keep `ReplayDailyReportMailProperties` bound to `axon-link.replay.daily-report-mail` and introduce a focused `ReplayWeeklyReportMailProperties` bound to `axon-link.replay.weekly-report-mail`. Inject the weekly properties only into `ReplayWeeklyReportMailService`; public mail APIs, request DTOs, persistence, attachments, token validation, and frontend behavior remain unchanged.

**Tech Stack:** Java 17, Spring Boot `@ConfigurationProperties`, JUnit 5, Mockito, AssertJ/Spring Boot `ApplicationContextRunner`, Maven.

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-日报周报邮件独立收件配置-系统设计.md`

## Global Constraints

- Daily defaults remain under `axon-link.replay.daily-report-mail` and `REPLAY_DAILY_REPORT_MAIL_*`.
- Weekly defaults move to `axon-link.replay.weekly-report-mail` and `REPLAY_WEEKLY_REPORT_MAIL_*`.
- `axon-link.mail.from` / `MAIL_FROM` remains the single shared SMTP sender configuration.
- Weekly recipients must not fall back to daily recipients when weekly configuration is empty or invalid.
- Existing REST paths, request/response JSON, persistence tables, attachment behavior, and frontend dialog behavior must not change.

---

### Task 1: Bind Daily and Weekly Mail Properties Independently

**Files:**
- Create: `src/test/java/com/axonlink/ai/replay/config/ReplayReportMailPropertiesTest.java`
- Create: `src/main/java/com/axonlink/ai/replay/config/ReplayWeeklyReportMailProperties.java`
- Modify: `src/main/java/com/axonlink/ai/replay/config/ReplayDailyReportMailProperties.java`

**Interfaces:**
- Consumes: Spring Boot relaxed binding for `List<String>` and scalar configuration properties.
- Produces: `ReplayWeeklyReportMailProperties#getTo()`, `getCc()`, `getSubjectPrefix()`, and `getBody()` with prefix `axon-link.replay.weekly-report-mail`.

- [ ] **Step 1: Write the failing configuration binding test**

```java
class ReplayReportMailPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsDailyAndWeeklyRecipientsFromIndependentPrefixes() {
        contextRunner.withPropertyValues(
                "axon-link.replay.daily-report-mail.to[0]=daily-to@example.com",
                "axon-link.replay.daily-report-mail.cc[0]=daily-cc@example.com",
                "axon-link.replay.weekly-report-mail.to[0]=weekly-to@example.com",
                "axon-link.replay.weekly-report-mail.cc[0]=weekly-cc@example.com")
                .run(context -> {
                    assertThat(context.getBean(ReplayDailyReportMailProperties.class).getTo())
                            .containsExactly("daily-to@example.com");
                    assertThat(context.getBean(ReplayWeeklyReportMailProperties.class).getTo())
                            .containsExactly("weekly-to@example.com");
                    assertThat(context.getBean(ReplayWeeklyReportMailProperties.class).getCc())
                            .containsExactly("weekly-cc@example.com");
                });
    }

    @EnableConfigurationProperties({ReplayDailyReportMailProperties.class,
            ReplayWeeklyReportMailProperties.class})
    static class TestConfig {
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -Dtest=ReplayReportMailPropertiesTest test`

Expected: test compilation fails because `ReplayWeeklyReportMailProperties` does not exist.

- [ ] **Step 3: Add the weekly properties class and narrow the daily class**

Implement `ReplayWeeklyReportMailProperties` with mutable defensive-copy `to` and `cc` lists plus defaults:

```java
@Component
@ConfigurationProperties(prefix = "axon-link.replay.weekly-report-mail")
public class ReplayWeeklyReportMailProperties {
    private List<String> to = new ArrayList<>();
    private List<String> cc = new ArrayList<>();
    private String subjectPrefix = "对公分布式核心回放问题周报-";
    private String body = "各位好，附件为本周期回放问题周报，请查收。";
    // standard getters and setters; list setters copy input and turn null into an empty list
}
```

Remove `weeklySubjectPrefix`, `weeklyBody`, and their accessors from `ReplayDailyReportMailProperties`; retain its existing daily fields and defaults unchanged.

- [ ] **Step 4: Run the binding test and verify GREEN**

Run: `mvn -Dtest=ReplayReportMailPropertiesTest test`

Expected: PASS, proving daily and weekly values bind to different beans.

- [ ] **Step 5: Commit the focused properties change**

```bash
git add src/main/java/com/axonlink/ai/replay/config/ReplayDailyReportMailProperties.java \
  src/main/java/com/axonlink/ai/replay/config/ReplayWeeklyReportMailProperties.java \
  src/test/java/com/axonlink/ai/replay/config/ReplayReportMailPropertiesTest.java
git commit -m "feat(replay): split report mail recipient properties"
```

### Task 2: Make Weekly Mail Read Only Weekly Defaults

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailServiceTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailService.java`

**Interfaces:**
- Consumes: `ReplayWeeklyReportMailProperties` from Task 1.
- Produces: unchanged `ReplayWeeklyReportMailService#configuration(String,String)` and `send(...)` behavior, now sourced only from weekly properties.

- [ ] **Step 1: Change the weekly service fixture to demand the independent bean**

Replace the fixture field and setup with:

```java
private ReplayWeeklyReportMailProperties properties;

properties = new ReplayWeeklyReportMailProperties();
properties.setTo(List.of(" weekly@example.com ", "WEEKLY@example.com"));
properties.setCc(List.of("weekly-cc@example.com"));
properties.setBody("默认周报正文");
```

Update assertions and mocked SMTP calls to expect only `weekly@example.com` and `weekly-cc@example.com`.

- [ ] **Step 2: Run the weekly test and verify RED**

Run: `mvn -Dtest=ReplayWeeklyReportMailServiceTest test`

Expected: test compilation fails because the service constructor still requires `ReplayDailyReportMailProperties`.

- [ ] **Step 3: Inject and consume weekly properties**

Change the service field and constructor parameter to `ReplayWeeklyReportMailProperties`. In `context(...)`, read:

```java
List<String> toEmails = normalizeEmails(properties.getTo());
List<String> ccEmails = normalizeEmails(properties.getCc());
String prefix = properties.getSubjectPrefix() == null ? "" : properties.getSubjectPrefix();
String body = properties.getBody() == null ? "" : properties.getBody();
```

Do not add any fallback to `ReplayDailyReportMailProperties`.

- [ ] **Step 4: Run daily and weekly service tests and verify GREEN**

Run: `mvn -Dtest=ReplayDailyReportMailServiceTest,ReplayWeeklyReportMailServiceTest test`

Expected: both test classes pass; daily expectations remain unchanged and weekly expectations use the independent values.

- [ ] **Step 5: Commit the service isolation**

```bash
git add src/main/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailService.java \
  src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailServiceTest.java
git commit -m "feat(replay): use independent weekly mail recipients"
```

### Task 3: Split Deployment Configuration and Run Regression Verification

**Files:**
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/axonlink/ai/replay/config/ReplayReportMailPropertiesTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayDailyReportMailServiceTest.java`
- Test: `src/test/java/com/axonlink/ai/replay/service/ReplayWeeklyReportMailServiceTest.java`

**Interfaces:**
- Consumes: the two configuration-property prefixes from Tasks 1 and 2.
- Produces: deployable environment variables `REPLAY_DAILY_REPORT_MAIL_TO`, `REPLAY_DAILY_REPORT_MAIL_CC`, `REPLAY_WEEKLY_REPORT_MAIL_TO`, and `REPLAY_WEEKLY_REPORT_MAIL_CC`.

- [ ] **Step 1: Extend the binding test with scalar isolation assertions**

Add daily and weekly subject/body values to the property list and assert each bean receives only its own values:

```java
"axon-link.replay.daily-report-mail.subject-prefix=日报-",
"axon-link.replay.daily-report-mail.body=日报正文",
"axon-link.replay.weekly-report-mail.subject-prefix=周报-",
"axon-link.replay.weekly-report-mail.body=周报正文"
```

- [ ] **Step 2: Run the expanded test before YAML modification**

Run: `mvn -Dtest=ReplayReportMailPropertiesTest test`

Expected: PASS for bean isolation; this establishes that the remaining change is deployment wiring only.

- [ ] **Step 3: Split `application.yml` into two peer nodes**

Keep the daily node with only daily values and add:

```yaml
weekly-report-mail:
  subject-prefix: ${REPLAY_WEEKLY_REPORT_MAIL_SUBJECT_PREFIX:对公分布式核心回放问题周报-}
  to:
    - ${REPLAY_WEEKLY_REPORT_MAIL_TO:}
  cc:
    - ${REPLAY_WEEKLY_REPORT_MAIL_CC:}
  body: ${REPLAY_WEEKLY_REPORT_MAIL_BODY:各位好，附件为本周期回放问题周报，请查收。}
```

Remove `weekly-subject-prefix` and `weekly-body` from the daily node. Update comments so deployers can see that both recipient lists are independent.

- [ ] **Step 4: Run focused and full backend verification**

Run focused tests:

```bash
mvn -Dtest=ReplayReportMailPropertiesTest,ReplayDailyReportMailServiceTest,ReplayWeeklyReportMailServiceTest test
```

Run the full suite:

```bash
mvn test
```

Expected: all tests pass, with only the repository's existing explicitly skipped tests remaining skipped.

- [ ] **Step 5: Verify configuration names and absence of legacy weekly fields**

Run:

```bash
rg -n "weekly-report-mail|REPLAY_WEEKLY_REPORT_MAIL_(TO|CC)" src/main src/test
! rg -n "weekly-subject-prefix|weekly-body|getWeeklySubjectPrefix|getWeeklyBody" src/main src/test
```

Expected: the new weekly node and environment variables are present; no legacy weekly fields remain in the daily properties class or configuration.

- [ ] **Step 6: Commit deployment wiring**

```bash
git add src/main/resources/application.yml \
  src/test/java/com/axonlink/ai/replay/config/ReplayReportMailPropertiesTest.java
git commit -m "config(replay): separate daily and weekly mail recipients"
```
