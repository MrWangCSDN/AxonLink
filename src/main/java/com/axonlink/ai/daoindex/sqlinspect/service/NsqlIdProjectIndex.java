package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 启动期扫描 {@code *.nsql.xml} 文件，建立 {@code <sqls id="...">} → 工程名（领域）的映射表。
 *
 * <p>背景：导入 IndexWarnLog Excel 时每条命名 SQL 形如 {@code FtBusiNamedSql.sel_kx_xx_xx}，
 * 其中 {@code FtBusiNamedSql} 是 {@code <sqls>} 标签的 id 属性。本索引把 Excel 行命名 SQL 的
 * 第一段映射回所属模块（如 {@code loan-bcc}），作为 {@code project_name} 落库。
 *
 * <p>扫描路径：{@code <scan.project-roots>/<module-pattern>/**\/*.nsql.xml}
 * <p>典型布局：
 * <pre>
 *   /path/to/ccbs-loan-impl/loan-bcc/src/main/resources/namedsql/ft/FtBusiNamedSql.nsql.xml
 *   /path/to/ccbs-dept-impl/dept-bcc/src/main/resources/namedsql/...
 * </pre>
 *
 * <p>匹配不上 → 调用方走 {@code "other"} 兜底。
 */
@Component
public class NsqlIdProjectIndex {

    private static final Logger log = LoggerFactory.getLogger(NsqlIdProjectIndex.class);

    /** 标识"扫不到时"的兜底 project name，对外暴露便于上层比对。 */
    public static final String UNKNOWN_PROJECT = "other";

    /** nsql 文件 glob。固定，不暴露配置（这是行业里 nsql 文件统一后缀）。 */
    private static final String NSQL_GLOB = "glob:**/*.nsql.xml";

    private final DaoIndexAnalysisProperties props;

    /** 不可变映射：sqlsId → projectName（模块名，如 "loan-bcc"）。 */
    private volatile Map<String, String> sqlsIdToProject = new HashMap<>();

    public NsqlIdProjectIndex(DaoIndexAnalysisProperties props) {
        this.props = props;
    }

    /**
     * 应用启动完毕（ApplicationReadyEvent）触发扫描——晚于 Spring 上下文初始化、
     * 不阻塞容器启动；扫描期间收到的导入请求 lookup 返回 {@link #UNKNOWN_PROJECT}（"other"）。
     * <p>注：用 ApplicationReadyEvent 而非 @PostConstruct，原因是扫描可能耗时数秒，
     * 不应卡住 Bean 初始化链；运维方也可在启动日志里清晰看到扫描完成事件。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        rebuild();
    }

    /**
     * 重新扫描——可被运维/测试调用以热刷新（例如新增 *-bcc 模块后无需重启）。
     */
    public synchronized void rebuild() {
        long start = System.currentTimeMillis();
        Map<String, String> next = new ConcurrentHashMap<>();
        List<String> projectRoots = props.getScan().getProjectRoots();
        String modulePattern = props.getScan().getModulePattern();
        if (projectRoots == null || projectRoots.isEmpty()) {
            log.warn("[nsql-index] scan.projectRoots 为空，跳过 nsql 扫描；" +
                     "导入接口的 project_name 将全部回退为 {}", UNKNOWN_PROJECT);
            sqlsIdToProject = next;
            return;
        }

        // 把 module-pattern（如 "*-bcc"）编译成 PathMatcher，用于过滤模块目录
        PathMatcher moduleMatcher = FileSystems.getDefault()
                .getPathMatcher("glob:" + (modulePattern == null ? "*" : modulePattern));
        PathMatcher nsqlMatcher = FileSystems.getDefault().getPathMatcher(NSQL_GLOB);

        int filesScanned = 0;
        int idsIndexed = 0;
        List<String> conflicts = new ArrayList<>();

        for (String rootStr : projectRoots) {
            if (rootStr == null || rootStr.isBlank()) continue;
            Path root = Paths.get(rootStr.trim());
            if (!Files.isDirectory(root)) {
                log.warn("[nsql-index] 项目根目录不存在 root={}", root);
                continue;
            }

            // 列 root 下一级目录，过滤出符合 *-bcc 的模块
            List<Path> modules = new ArrayList<>();
            try {
                Files.list(root).filter(Files::isDirectory)
                        .filter(p -> moduleMatcher.matches(p.getFileName()))
                        .forEach(modules::add);
            } catch (IOException e) {
                log.warn("[nsql-index] 列模块失败 root={}: {}", root, e.getMessage());
                continue;
            }

            for (Path module : modules) {
                String moduleName = module.getFileName().toString();
                int beforeIds = next.size();
                try {
                    Files.walkFileTree(module, new SimpleFileVisitor<>() {
                        int localScanned = 0;
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (!nsqlMatcher.matches(file)) return FileVisitResult.CONTINUE;
                            localScanned++;
                            String sqlsId = extractSqlsId(file);
                            if (sqlsId != null && !sqlsId.isEmpty()) {
                                String prev = next.put(sqlsId, moduleName);
                                if (prev != null && !prev.equals(moduleName)) {
                                    conflicts.add(sqlsId + ": " + prev + " vs " + moduleName);
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    // 简化：walkFileTree 内统计回报由外层依据 next 大小变化反推
                    int idsThisModule = next.size() - beforeIds;
                    idsIndexed += idsThisModule;
                    log.info("[nsql-index] module={} 新增 sqlsId={} ", moduleName, idsThisModule);
                } catch (IOException e) {
                    log.warn("[nsql-index] 遍历模块失败 module={}: {}", moduleName, e.getMessage());
                }
            }
            filesScanned = idsIndexed;  // 总数（含跨 root）
        }

        sqlsIdToProject = next;
        long elapsed = System.currentTimeMillis() - start;
        log.info("[nsql-index] 扫描完成 耗时={}ms 索引 sqlsId={} 冲突={}",
                elapsed, sqlsIdToProject.size(), conflicts.size());
        if (!conflicts.isEmpty()) {
            // 同名 sqls.id 落到不同模块：罕见但需告警（namedsql 设计上应跨模块全局唯一）
            log.warn("[nsql-index] 检测到 {} 个 sqlsId 跨模块冲突，仅保留最后扫到的：{}",
                    conflicts.size(),
                    conflicts.subList(0, Math.min(10, conflicts.size())));
        }
    }

    /**
     * 查一条 {@code <sqls>} id 对应的 project 名。
     *
     * @param sqlsId 命名 SQL 第一段（首个 {@code .} 之前），如 {@code FtBusiNamedSql}
     * @return 命中返回模块名（如 {@code loan-bcc}），未命中返回 {@link #UNKNOWN_PROJECT}
     */
    public String lookupProject(String sqlsId) {
        if (sqlsId == null || sqlsId.isEmpty()) return UNKNOWN_PROJECT;
        String name = sqlsIdToProject.get(sqlsId);
        return name == null ? UNKNOWN_PROJECT : name;
    }

    /** 当前索引规模，便于运维 / 健康检查 / 单测断言。 */
    public int size() {
        return sqlsIdToProject.size();
    }

    /**
     * 测试用：注入预构建映射，跳过文件系统扫描。
     * <p>包私有可见——只供同包单测使用。
     */
    void replaceForTesting(Map<String, String> mapping) {
        this.sqlsIdToProject = new HashMap<>(mapping);
    }

    /**
     * 读单个 nsql.xml 的根标签 {@code <sqls>} 的 {@code id} 属性。
     * <p>采用 DOM 解析（namedsql 文件通常 < 100KB，DOM 简单清晰）；禁用外部 DTD/Entity 防 XXE。
     *
     * @return id 字符串；解析失败返回 null
     */
    static String extractSqlsId(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 安全：关 DOCTYPE / external-general-entities 防 XXE
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(in));
            Element root = doc.getDocumentElement();
            if (root == null) return null;
            // 标签名是 "sqls"（项目约定，截图里也是这个）；个别项目可能用别名——这里只信首 element
            String id = root.getAttribute("id");
            return id == null || id.isEmpty() ? null : id;
        } catch (Exception e) {
            log.warn("[nsql-index] 解析失败 file={}: {}", file, e.getMessage());
            return null;
        }
    }
}
