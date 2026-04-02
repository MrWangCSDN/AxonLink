package com.axonlink.ai.rule;

import com.axonlink.ai.dto.AnalysisContext;
import com.axonlink.ai.dto.RuleFinding;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 确定性技术规则引擎。
 */
@Service
public class RuleEngineService {

    public List<RuleFinding> run(AnalysisContext context) {
        List<RuleFinding> findings = new ArrayList<>();
        Map<String, Object> chain = context.getChain();

        int serviceCount = intValue(chain.get("serviceCount"));
        int componentCount = intValue(chain.get("componentCount"));
        int tableCount = intValue(chain.get("tableCount"));

        if (serviceCount >= 8) {
            findings.add(finding("deep-service-chain", "medium",
                    "服务链较长",
                    "当前交易涉及的服务节点较多，建议结合业务域边界检查是否存在职责下沉或链路过长问题。",
                    "serviceCount=" + serviceCount));
        }
        if (componentCount >= 10) {
            findings.add(finding("wide-component-fanout", "medium",
                    "构件调用扇出偏大",
                    "构件层扇出较大，后续可重点检查是否存在过多横向依赖或编排职责下沉到构件层的情况。",
                    "componentCount=" + componentCount));
        }
        if (tableCount >= 8) {
            findings.add(finding("multi-table-access", "medium",
                    "表访问范围较大",
                    "交易涉及的数据表较多，建议在性能和事务一致性维度做进一步排查。",
                    "tableCount=" + tableCount));
        }
        if (context.getCodeSnippets().isEmpty()) {
            findings.add(finding("missing-snippets", "info",
                    "代码片段尚未抽取",
                    "当前分析已打通上下文骨架，但尚未按交易路径精确抽取实现方法代码片段。",
                    "codeSnippets=0"));
        }
        return findings;
    }

    private RuleFinding finding(String key, String severity, String title, String detail, String evidence) {
        RuleFinding finding = new RuleFinding();
        finding.setKey(key);
        finding.setSeverity(severity);
        finding.setTitle(title);
        finding.setDetail(detail);
        finding.setEvidence(evidence);
        return finding;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
