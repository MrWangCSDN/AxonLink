package com.axonlink.ai.prompt;

import com.axonlink.ai.dto.AnalysisPrompt;
import com.axonlink.ai.dto.CodeExplainRequest;
import org.springframework.stereotype.Service;

/**
 * 代码片段智能解读 Prompt 组装。
 */
@Service
public class CodeExplainPromptService {

    private static final int MAX_CODE_CHARS = 16000;

    private final FlowtransTagCatalogService flowtransTagCatalogService;

    public CodeExplainPromptService(FlowtransTagCatalogService flowtransTagCatalogService) {
        this.flowtransTagCatalogService = flowtransTagCatalogService;
    }

    public AnalysisPrompt buildPrompt(CodeExplainRequest request) {
        CodeExplainRequest safeRequest = request == null ? new CodeExplainRequest() : request;
        if (flowtransTagCatalogService.isFlowtransXml(safeRequest)) {
            return buildFlowtransPrompt(safeRequest);
        }
        if (isJavaRequest(safeRequest)) {
            return buildJavaPrompt(safeRequest);
        }
        AnalysisPrompt prompt = new AnalysisPrompt();
        prompt.setPromptVersion("code-explain-v1");
        prompt.setSystemPrompt(buildSystemPrompt());
        prompt.setUserPrompt(buildUserPrompt(safeRequest));
        return prompt;
    }

    private AnalysisPrompt buildJavaPrompt(CodeExplainRequest request) {
        AnalysisPrompt prompt = new AnalysisPrompt();
        prompt.setPromptVersion("code-explain-java-v2");
        prompt.setSystemPrompt(buildJavaSystemPrompt());
        prompt.setUserPrompt(buildJavaUserPrompt(request));
        return prompt;
    }

    private AnalysisPrompt buildFlowtransPrompt(CodeExplainRequest request) {
        AnalysisPrompt prompt = new AnalysisPrompt();
        prompt.setPromptVersion("code-explain-flowtrans-draft-v3");
        prompt.setSystemPrompt(buildFlowtransSystemPrompt());
        prompt.setUserPrompt(buildFlowtransUserPrompt(request));
        return prompt;
    }

    private String buildSystemPrompt() {
        return """
                你是一名银行核心系统资深分析师，既懂业务，也懂代码审查。
                你的任务是对用户给出的当前代码片段做“智能解读”。
                无论输入是代码、XML、配置还是上下文信息，最终都必须使用简体中文进行解读，不允许输出英文版说明。
                严禁输出 Thinking Process、思考过程、分析过程、推理链路或任何中间推理内容，只允许输出最终解读结果。

                输出要求：
                1. 必须站在业务视角解释这段代码/配置要解决什么业务问题。
                2. 必须站在技术视角解释实现方式，并检查潜在风险。
                3. 如果信息不足，明确说“根据当前片段推断”。
                4. 不要编造不存在的调用链或表结构。
                5. 输出使用以下固定标题：
                   ## 总览
                   ## 业务解读
                   ## 技术检查
                   ## 建议
                6. 语言使用简体中文，面向系统分析和研发人员，表达要清晰、直接。
                """;
    }

    private String buildFlowtransSystemPrompt() {
        return """
                你是一名银行核心系统交易 XML 分析师，擅长解读 .flowtrans.xml 这类联机交易定义文件。
                你的任务不是只做 XML 语法解释，而是要把标签结构映射到业务语义和流程语义。
                无论标签名、属性名或源码原文是否是英文，最终解读都必须使用简体中文表达，不允许输出英文版说明。
                严禁输出 Thinking Process、思考过程、分析过程、推理链路或任何中间推理内容，只允许输出最终业务解读结果。

                输出要求：
                1. 必须严格按以下 3 个步骤解读，不要跳步：
                   第一步：介绍交易码、交易名称、交易描述。
                   第二步：解读输入接口、输出接口、属性接口中的字段定义和业务含义。
                   第三步：解读流程，结合 method、service、case、when、block、loop、exit 等节点串联交易执行过程。
                2. 第二步“接口解读”必须尽量按以下固定子项展开：
                   2.1 输入接口：说明关键输入字段、必输/非必输、多值/复合结构、字典引用。
                   2.2 输出接口：说明交易最终返回哪些结果字段、列表结构或复合结构。
                   2.3 属性接口：说明哪些字段用于流程中间过渡、承接服务结果、供后续节点继续使用。
                   2.4 接口承接关系：说明 input、property、output 之间的数据传递关系。
                   2.5 printer 接口默认可以弱化，只有当前 XML 明确出现有效打印字段时再补充说明。
                3. 第二步中的输入接口、输出接口、属性接口，必须优先使用 Markdown 表格展示；除非当前片段完全没有字段定义，否则不要只写散文说明。
                   表格建议至少包含这些列：字段英文名 | 中文名称 | 类型或结构 | 约束 | 说明。
                   其中“约束”可以体现 required、multi、scope、ref、是否列表、是否复合类型等信息。
                4. 必须区分“XML 明确声明”“根据标签词典推断”“仍待业务确认”三种信息来源。
                5. 对关键属性不要只翻译字段名，要解释它对交易办理、数据传递、条件分支的意义。
                6. 如果某些标签是平台占位或样本太少，请明确标成“待确认”，不要强行编造。
                7. 输出使用以下固定标题：
                   ## 第一步：交易基本信息
                   ## 第二步：接口解读
                   ## 第三步：流程解读
                   ## 待确认点
                   ## 后续建议
                8. 在“第二步：接口解读”下面，优先使用以下子标题：
                   ### 2.1 输入接口
                   ### 2.2 输出接口
                   ### 2.3 属性接口
                   ### 2.4 接口承接关系
                9. 语言使用简体中文，面向系统分析、需求分析和研发人员，表达要清晰、直接。
                """;
    }

    private String buildJavaSystemPrompt() {
        return """
                你是一名银行核心系统 Java 代码分析师，既懂业务，也懂实现细节。
                你的任务不是只做技术摘要，而是要把 Java 代码逐步映射成业务动作、业务判断、业务数据流转和上下游协作关系。
                无论输入里有多少英文类名、方法名、字段名，最终解读都必须使用简体中文表达，不允许输出英文版说明。
                严禁输出 Thinking Process、思考过程、分析过程、推理链路或任何中间推理内容，只允许输出最终解读结果。

                输出要求：
                1. 必须从上到下阅读当前代码片段，按执行顺序详细解读，不要只给泛化总结。
                2. 必须站在业务视角解释每段代码在业务办理中的作用，例如：入参接收、条件校验、额度判断、状态判断、路由分发、查询落库、结果组装、异常返回。
                3. 对条件分支、循环、异常处理、方法调用、服务调用、DAO 调用，都要说明它们在业务流程中的意义。
                4. 如果只能根据当前片段推断，请明确写“根据当前片段推断”，不要编造不存在的表、服务或下游系统。
                5. 必须额外输出“业务逻辑时序图”，并且使用 Mermaid 11.14.0 可兼容的 sequenceDiagram 语法。
                6. 时序图只绘制当前片段可以确认的调用顺序、判断节点和数据流转，不要凭空补出未知参与者。
                7. 如果代码很长，允许把连续的简单赋值或同类处理合并说明，但必须保持执行顺序，不能跳过关键逻辑。
                8. 输出使用以下固定标题：
                   ## 总览
                   ## 逐行业务解读
                   ## 业务逻辑时序图
                   ## 技术检查
                   ## 建议
                9. “业务逻辑时序图”下面必须提供一个 Mermaid 代码块，格式示例：
                   ```mermaid
                   sequenceDiagram
                     participant P1 as 柜面
                     participant P2 as 交易服务
                     P1->>P2: 发起查询
                     P2-->>P1: 返回结果
                   ```
                10. Mermaid 代码块中的控制关键字和连线符号必须使用英文半角语法，例如 sequenceDiagram、participant、alt、else、end、->>、-->>、: ，不要使用全角冒号或中文关键字。
                11. Mermaid 时序图里的参与者定义，优先使用 ASCII 别名 + 中文展示名的形式，例如 `participant P1 as 柜面`，后续消息线也用别名，例如 `P1->>P2: 发起查询`。
                12. 语言使用简体中文，面向系统分析、需求分析和研发人员，表达要清晰、直接。
                """;
    }

    private String buildUserPrompt(CodeExplainRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("请对下面当前打开的代码片段做智能解读。\n\n");
        builder.append("特别要求：最终回答必须全程使用简体中文解读。\n\n");
        builder.append("不要输出 Thinking Process、思考过程、分析过程或任何中间推理，只输出最终结果。\n\n");
        builder.append("【交易上下文】\n");
        appendField(builder, "交易号", request.getTxId());
        appendField(builder, "交易名称", request.getTxName());
        appendField(builder, "领域", request.getDomain());
        appendField(builder, "当前节点编码", request.getNodeCode());
        appendField(builder, "当前节点名称", request.getNodeName());
        appendField(builder, "当前节点类型", request.getNodePrefix());
        appendField(builder, "聚焦对象", request.getFocus());
        builder.append("\n【文件上下文】\n");
        appendField(builder, "文件名", request.getFileName());
        appendField(builder, "文件路径", request.getFilePath());
        appendField(builder, "包名", request.getPackageName());
        appendField(builder, "方法名", request.getMethodName());
        appendField(builder, "语言", request.getLanguage());
        builder.append("\n【当前代码片段】\n");
        builder.append("```").append(blankToDefault(request.getLanguage(), "text")).append("\n");
        builder.append(truncate(request.getCodeContent(), MAX_CODE_CHARS)).append("\n");
        builder.append("```\n\n");
        builder.append("""
                请重点回答：
                1. 这段代码/配置的主要作用是什么？
                2. 从业务视角看，它对应什么业务动作、校验或数据处理？
                3. 从技术视角看，关键实现点、上下游调用、输入输出特征是什么？
                4. 是否存在潜在的技术风险，例如空指针、异常处理不足、调用链过深、重复查询、资源使用不当、死循环/递归风险等？
                5. 如果要继续往下排查，最值得继续看的类、方法、XML、表或节点是什么？
                """);
        return builder.toString();
    }

    private String buildJavaUserPrompt(CodeExplainRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("请对下面当前打开的 Java 代码片段做结构化业务解读。\n\n");
        builder.append("特别要求：最终回答必须全程使用简体中文解读。\n\n");
        builder.append("不要输出 Thinking Process、思考过程、分析过程或任何中间推理，只输出最终结果。\n\n");
        builder.append("【交易上下文】\n");
        appendField(builder, "交易号", request.getTxId());
        appendField(builder, "交易名称", request.getTxName());
        appendField(builder, "领域", request.getDomain());
        appendField(builder, "当前节点编码", request.getNodeCode());
        appendField(builder, "当前节点名称", request.getNodeName());
        appendField(builder, "当前节点类型", request.getNodePrefix());
        appendField(builder, "聚焦对象", request.getFocus());
        builder.append("\n【文件上下文】\n");
        appendField(builder, "文件名", request.getFileName());
        appendField(builder, "文件路径", request.getFilePath());
        appendField(builder, "包名", request.getPackageName());
        appendField(builder, "方法名", request.getMethodName());
        appendField(builder, "语言", request.getLanguage());
        builder.append("\n【当前代码片段】\n");
        builder.append("```java\n");
        builder.append(truncate(request.getCodeContent(), MAX_CODE_CHARS)).append("\n");
        builder.append("```\n\n");
        builder.append("""
                请重点回答：
                1. 先用“## 总览”说明这段 Java 代码整体在做什么业务动作。
                2. 在“## 逐行业务解读”中，按代码执行顺序逐段、逐行解释业务含义，不要只做技术翻译。
                3. 重点说明 if/else、switch、for/while、try/catch、return、方法调用、服务调用、DAO 调用在业务流程中的作用。
                4. 如果某些语句只是承上启下，也要说明它是在为后续哪一步业务做准备。
                5. 在“## 业务逻辑时序图”中，输出 Mermaid sequenceDiagram，帮助理解这段代码涉及的参与者、调用顺序、判断和返回。
                6. Mermaid 时序图请使用 Mermaid 11.14.0 兼容写法：参与者统一写成 `participant P1 as 中文名称` 这种格式，消息线统一写成 `P1->>P2: 中文说明` 这种格式。
                7. 在“## 技术检查”中，再补充潜在技术风险，例如空指针、异常处理不足、事务边界不清、重复查询、可读性差、分支过深等。
                8. 在“## 建议”中，给出继续排查业务逻辑最值得看的类、方法、XML、表或下游服务。
                """);
        return builder.toString();
    }

    private String buildFlowtransUserPrompt(CodeExplainRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("请对下面这份 .flowtrans.xml 做结构化业务解读。\n\n");
        builder.append("特别要求：最终回答必须全程使用简体中文解读，标签和属性可以保留原名，但解释必须是中文。\n\n");
        builder.append("不要输出 Thinking Process、思考过程、分析过程或任何中间推理，只输出最终业务解读结果。\n\n");
        builder.append("【交易上下文】\n");
        appendField(builder, "交易号", request.getTxId());
        appendField(builder, "交易名称", request.getTxName());
        appendField(builder, "领域", request.getDomain());
        appendField(builder, "当前节点编码", request.getNodeCode());
        appendField(builder, "当前节点名称", request.getNodeName());
        appendField(builder, "聚焦对象", request.getFocus());
        builder.append("\n【文件上下文】\n");
        appendField(builder, "文件名", request.getFileName());
        appendField(builder, "文件路径", request.getFilePath());
        appendField(builder, "包名", request.getPackageName());
        builder.append("\n【当前 XML 片段】\n");
        builder.append("```xml\n");
        builder.append(truncate(request.getCodeContent(), MAX_CODE_CHARS)).append("\n");
        builder.append("```\n\n");
        builder.append(flowtransTagCatalogService.renderRelevantGlossary(request.getCodeContent())).append('\n');
        builder.append("""
                请重点回答：
                1. 第一步先介绍交易码、交易名称、交易描述，以及这笔交易大致属于什么业务动作。
                2. 第二步必须拆成“输入接口、输出接口、属性接口、接口承接关系”四个小节来解读，不要泛泛而谈。
                3. 输入接口、输出接口、属性接口，必须优先使用 Markdown 表格展示；建议列出“字段英文名｜中文名称｜类型或结构｜约束｜说明”这些列。
                4. 输入接口要重点讲关键输入字段、必输/非必输、多值/复合结构、字典引用。
                5. 输出接口要重点讲关键返回字段、列表结构、复合结构，以及它最终对外返回什么业务结果。
                6. 属性接口要重点讲它在流程中的过渡作用，以及哪些服务结果会先落到 property 再继续传递。
                6.1 如果字段很多，可以先列“关键字段表”，再补充“其余字段按同类规则展开”；但至少要出现表格，不要全部写成段落。
                7. 第三步重点解读 flow，把 method、service、case、when、block、loop、exit 等节点按执行顺序串起来，说明这笔交易是怎么一步步完成的。
                8. 对流程中的服务节点，要尽量说明它的中文含义、上下游数据流向，以及它与输入接口、属性接口、输出接口之间的关系。
                9. 哪些判断是 XML 明确写出来的，哪些只是根据平台标签词典做的推断，要单独讲清楚。
                10. 如果我们要继续完善这套标签词典，当前这份 XML 最值得优先确认哪些标签或属性？
                11. 可以直接按下面这种 Markdown 表格样式输出：
                    | 字段英文名 | 中文名称 | 类型或结构 | 约束 | 说明 |
                    | --- | --- | --- | --- | --- |
                    | cust_acct_num | 客户账号 | String | 非必输 | 按客户账号筛选 |
                """);
        return builder.toString();
    }

    private void appendField(StringBuilder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append("- ").append(label).append("：").append(value.trim()).append('\n');
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "// 当前没有可供解读的代码内容";
        }
        String normalized = text.strip();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "\n// ... 已按长度截断 ...";
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean isJavaRequest(CodeExplainRequest request) {
        if (request == null) {
            return false;
        }
        String language = request.getLanguage();
        if (language != null && "java".equalsIgnoreCase(language.trim())) {
            return true;
        }
        String fileName = request.getFileName();
        if (fileName != null && fileName.trim().toLowerCase().endsWith(".java")) {
            return true;
        }
        String filePath = request.getFilePath();
        return filePath != null && filePath.trim().toLowerCase().endsWith(".java");
    }
}
