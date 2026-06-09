package com.axonlink.notification.service;

import java.util.Map;

/**
 * SQL 审核白名单豁免申请相关邮件模板。
 *
 * <p>占位符：{@code {{xxx}}} 形式，调用 {@link MailService} 发送前用 {@link #render} 替换。
 * <p>每个模板对应一个业务事件：
 * <ul>
 *   <li>{@link #APPLY_PENDING_L1}：申请提交，通知 L1 审批人</li>
 *   <li>{@link #L1_PASSED_NOTIFY_L2}：L1 通过，通知 L2 审批人</li>
 *   <li>{@link #APPROVED_NOTIFY_APPLICANT}：终审通过，通知申请人</li>
 *   <li>{@link #L1_REJECTED_NOTIFY_APPLICANT}：L1 退回，通知申请人</li>
 * </ul>
 */
public final class WhitelistMailTemplate {

    private WhitelistMailTemplate() {}

    // ── 业务事件 1：申请提交 → 通知 L1 审批人 ──────────────────────────────
    public static final String APPLY_PENDING_L1_SUBJECT =
            "【SQL 巡检】{{applicantName}} 提交了白名单豁免申请，待您审批 (#{{appId}})";

    public static final String APPLY_PENDING_L1_BODY = """
            您好 {{l1ApproverName}}，

            {{applicantName}} 提交了一条 SQL 审核白名单豁免申请，请您作为一级审批人处理。

            ── 申请信息 ──
            申请编号：#{{appId}}
            环境：{{env}}
            目标工程：{{projectName}}
            目标类型：{{targetType}}
            SQL 摘要：{{sqlSnippet}}
            申请理由：{{applyReason}}
            提交时间：{{applyTime}}

            请尽快登录「SQL 巡检」白名单审批页面处理。
            """;

    // ── 业务事件 2：L1 通过 → 通知 L2 审批人 ──────────────────────────────
    public static final String L1_PASSED_NOTIFY_L2_SUBJECT =
            "【SQL 巡检】白名单申请 #{{appId}} 已通过 L1 审批，待您终审";

    public static final String L1_PASSED_NOTIFY_L2_BODY = """
            您好 {{l2ApproverName}}，

            白名单豁免申请 #{{appId}} 已通过一级审批，请您作为二级审批人终审。

            ── 申请信息 ──
            申请编号：#{{appId}}
            申请人：{{applicantName}}
            一级审批人：{{l1ApproverName}}
            一级审批意见：{{l1Opinion}}
            环境：{{env}}
            目标工程：{{projectName}}
            目标类型：{{targetType}}
            SQL 摘要：{{sqlSnippet}}
            申请理由：{{applyReason}}

            请尽快登录「SQL 巡检」白名单审批页面处理。
            """;

    // ── 业务事件 3：终审通过 → 通知申请人 ──────────────────────────────
    public static final String APPROVED_NOTIFY_APPLICANT_SUBJECT =
            "【SQL 巡检】您的白名单豁免申请 #{{appId}} 已通过";

    public static final String APPROVED_NOTIFY_APPLICANT_BODY = """
            您好 {{applicantName}}，

            您提交的白名单豁免申请 #{{appId}} 已通过终审，SQL 将不再触发告警。

            ── 申请信息 ──
            申请编号：#{{appId}}
            环境：{{env}}
            目标工程：{{projectName}}
            目标类型：{{targetType}}
            SQL 摘要：{{sqlSnippet}}
            二级审批人：{{l2ApproverName}}
            二级审批意见：{{l2Opinion}}
            审批完成时间：{{approveTime}}
            """;

    // ── 业务事件 4：L1 退回 → 通知申请人 ────────────────────────────────
    public static final String L1_REJECTED_NOTIFY_APPLICANT_SUBJECT =
            "【SQL 巡检】您的白名单豁免申请 #{{appId}} 已被退回";

    public static final String L1_REJECTED_NOTIFY_APPLICANT_BODY = """
            您好 {{applicantName}}，

            您提交的白名单豁免申请 #{{appId}} 已被一级审批人退回，请根据意见调整后重新提交。

            ── 申请信息 ──
            申请编号：#{{appId}}
            环境：{{env}}
            目标工程：{{projectName}}
            目标类型：{{targetType}}
            SQL 摘要：{{sqlSnippet}}
            申请理由：{{applyReason}}
            一级审批人：{{l1ApproverName}}
            退回意见：{{l1Opinion}}
            """;

    /**
     * 简单占位符替换（与参考工程 MailTemplate.render 一致）。
     * 未提供值的占位符保留原样，便于调试。
     */
    public static String render(String tpl, Map<String, ?> vars) {
        if (tpl == null) return "";
        String s = tpl;
        if (vars != null) {
            for (Map.Entry<String, ?> e : vars.entrySet()) {
                Object v = e.getValue();
                String replacement = v == null ? "" : String.valueOf(v);
                s = s.replace("{{" + e.getKey() + "}}", replacement);
            }
        }
        return s;
    }
}
