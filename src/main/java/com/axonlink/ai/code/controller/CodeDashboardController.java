package com.axonlink.ai.code.controller;

import com.axonlink.ai.code.service.CodeAnalysisService;
import com.axonlink.ai.code.service.CodeDashboardService;
import com.axonlink.common.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 源码提交分析大屏读接口。只读 summary 层（每日快照），不触发采集/聚合。
 */
@RestController
@RequestMapping("/api/code/dashboard")
public class CodeDashboardController {

    private static final Logger log = LoggerFactory.getLogger(CodeDashboardController.class);

    @Autowired
    private CodeDashboardService dashboardService;

    @Autowired
    private CodeAnalysisService codeAnalysisService;

    /**
     * 本地只读扫描（测试用）：指定本地 git 工程路径，原地只读分析 + 落库 + 聚合，同步返回汇总。
     * 绝不 clone/fetch/reset，不动用户工作副本。完成后即可在大屏选该仓库看效果。
     *
     * <p>请求体 JSON：
     * <pre>
     * {
     *   "repoName":    "ccbs-main",            // 仓库展示名（唯一键，重复则复用并更新配置）
     *   "localPath":   "D:/workspace/ccbs",    // 本地 git 仓库根目录（含 .git）
     *   "branch":      "main",                 // 记录用，可空
     *   "includeExts": "java,xml",             // 扩展名白名单（建议 java,xml 含 flowtrans 定义）
     *   "excludePaths": "target/,test/"        // 排除路径前缀，逗号分隔，可空
     * }
     * </pre>
     * 注：*.flowtrans.xml 无论 includeExts 是否含 xml，均会自动解析提取交易码映射。
     */
    @PostMapping("/scan")
    public R<Map<String, Object>> scan(@RequestBody Map<String, Object> body) {
        try {
            return R.ok(codeAnalysisService.runLocalScan(
                    str(body.get("repoName")),
                    str(body.get("localPath")),
                    str(body.get("branch")),
                    str(body.get("includeExts")),
                    str(body.get("excludePaths"))));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("本地扫描失败", e);
            return R.fail("本地扫描失败：" + e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 仓库选择器：已配置的代码仓库及其最近同步状态。 */
    @GetMapping("/repos")
    public R<List<Map<String, Object>>> repos() {
        try {
            return R.ok(dashboardService.repos());
        } catch (Exception e) {
            log.error("查询仓库列表失败", e);
            return R.fail("查询仓库列表失败：" + e.getMessage());
        }
    }

    /**
     * 大屏首屏：行员/厂商总览(含占比) + 作者 Top + 交易 Top，一次返回。
     *
     * <p>{@code repoId=0}（或不传）→ <b>ALL 汇总模式</b>：跨所有 enabled 仓库聚合。
     */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam(required = false, defaultValue = "0") Long repoId) {
        try {
            return R.ok(dashboardService.overview(repoId));
        } catch (Exception e) {
            log.error("查询大屏总览失败 repoId={}", repoId, e);
            return R.fail("查询大屏总览失败：" + e.getMessage());
        }
    }

    /** 工程维度作者存活行排行。 */
    @GetMapping("/authors")
    public R<List<Map<String, Object>>> authors(@RequestParam Long repoId,
                                                @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            return R.ok(dashboardService.authors(repoId, limit));
        } catch (Exception e) {
            log.error("查询作者排行失败 repoId={}", repoId, e);
            return R.fail("查询作者排行失败：" + e.getMessage());
        }
    }

    /** 项目级 按领域划分：每领域 行员/厂商 拆分 + 占全仓比。 */
    @GetMapping("/domains")
    public R<List<Map<String, Object>>> domains(@RequestParam Long repoId) {
        try {
            return R.ok(dashboardService.domains(repoId));
        } catch (Exception e) {
            log.error("查询领域维度失败 repoId={}", repoId, e);
            return R.fail("查询领域维度失败：" + e.getMessage());
        }
    }

    /** 某领域内作者排行（下钻：该领域谁掌握得多）。 */
    @GetMapping("/domain-authors")
    public R<List<Map<String, Object>>> domainAuthors(@RequestParam Long repoId,
                                                      @RequestParam String domain,
                                                      @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            return R.ok(dashboardService.domainAuthors(repoId, domain, limit));
        } catch (Exception e) {
            log.error("查询领域作者失败 repoId={} domain={}", repoId, domain, e);
            return R.fail("查询领域作者失败：" + e.getMessage());
        }
    }

    /** 交易维度排行（code_tx_file_map 由 flowtrans XML 扫描填充；空则返回空列表）。 */
    @GetMapping("/tx")
    public R<List<Map<String, Object>>> tx(@RequestParam Long repoId,
                                           @RequestParam(required = false, defaultValue = "50") int limit) {
        try {
            return R.ok(dashboardService.tx(repoId, limit));
        } catch (Exception e) {
            log.error("查询交易维度失败 repoId={}", repoId, e);
            return R.fail("查询交易维度失败：" + e.getMessage());
        }
    }

    /**
     * 人员维度统计：每人总代码量 + 归属交易码列表。
     * tx_ids 字段为逗号分隔的交易码（来自 *.flowtrans.xml blame 归属）；
     * 无 flowtrans XML 参与记录时 tx_ids 为 null / tx_count=0。
     *
     * @param personType 可选过滤：STAFF 或 VENDOR；不传则返回全部人员
     */
    @GetMapping("/persons")
    public R<List<Map<String, Object>>> persons(
            @RequestParam Long repoId,
            @RequestParam(required = false) String personType,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        try {
            if (personType != null && !personType.isBlank()) {
                return R.ok(dashboardService.personStatsByType(repoId, personType.toUpperCase(), limit));
            }
            return R.ok(dashboardService.personStats(repoId, limit));
        } catch (Exception e) {
            log.error("查询人员维度失败 repoId={}", repoId, e);
            return R.fail("查询人员维度失败：" + e.getMessage());
        }
    }
}
