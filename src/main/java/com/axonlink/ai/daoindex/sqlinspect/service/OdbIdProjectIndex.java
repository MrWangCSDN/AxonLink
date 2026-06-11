package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
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
 * 启动期扫描 {@code *.tables.xml}（odb 表定义）文件，建立
 * {@code <schema id="...">} → 工程模块名（领域）的映射表。
 *
 * <p>背景：慢SQL v2 的 E 列（来源文件）odb 形态形如
 * {@code S010010048.CorpDmdOpnccnt:Kapp_serl_num.kapp_serl_num.Entity.selectByIndexWithLock_odb1}，
 * 冒号后首段 {@code Kapp_serl_num} 即 tables.xml 根 {@code <schema>} 的 id 属性
 * （文件名通常同名：{@code Kdpb_cb_alwc_acml.tables.xml} ↔ {@code id="Kdpb_cb_alwc_acml"}）。
 * 本索引把它映射回所属模块（如 {@code dept-bcc}），上层再映射成中文领域。
 *
 * <p>扫描路径与 {@link NsqlIdProjectIndex} 完全同构：
 * {@code <scan.project-roots>/<module-pattern>/**\/*.tables.xml}。
 * <p>匹配不上 → {@link #lookupModule} 返回 {@code null}（上层领域落「其他」）。
 */
@Component
public class OdbIdProjectIndex {

    private static final Logger log = LoggerFactory.getLogger(OdbIdProjectIndex.class);

    /** tables.xml 文件 glob（odb 表定义统一后缀）。 */
    private static final String TABLES_GLOB = "glob:**/*.tables.xml";

    private final DaoIndexAnalysisProperties props;

    /** 不可变映射：schemaId → moduleName（如 "dept-bcc"）。 */
    private volatile Map<String, String> schemaIdToModule = new HashMap<>();
    /** 小写键副本：E 列大小写偶有漂移时兜底。 */
    private volatile Map<String, String> lowerIdToModule = new HashMap<>();

    public OdbIdProjectIndex(DaoIndexAnalysisProperties props) {
        this.props = props;
    }

    /** 应用启动完毕触发扫描——与 {@link NsqlIdProjectIndex} 同节奏，不卡 Bean 初始化链。 */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        rebuild();
    }

    /** 重新扫描——可热刷新（新增模块后无需重启）。 */
    public synchronized void rebuild() {
        long start = System.currentTimeMillis();
        Map<String, String> next = new ConcurrentHashMap<>();
        List<String> projectRoots = props.getScan().getProjectRoots();
        String modulePattern = props.getScan().getModulePattern();
        if (projectRoots == null || projectRoots.isEmpty()) {
            log.warn("[odb-index] scan.projectRoots 为空，跳过 tables.xml 扫描；odb 来源领域将回退「其他」");
            swap(next);
            return;
        }

        PathMatcher moduleMatcher = FileSystems.getDefault()
                .getPathMatcher("glob:" + (modulePattern == null ? "*" : modulePattern));
        PathMatcher tablesMatcher = FileSystems.getDefault().getPathMatcher(TABLES_GLOB);

        List<String> conflicts = new ArrayList<>();
        for (String rootStr : projectRoots) {
            if (rootStr == null || rootStr.isBlank()) continue;
            Path root = Paths.get(rootStr.trim());
            if (!Files.isDirectory(root)) {
                log.warn("[odb-index] 项目根目录不存在 root={}", root);
                continue;
            }
            List<Path> modules = new ArrayList<>();
            try {
                Files.list(root).filter(Files::isDirectory)
                        .filter(p -> moduleMatcher.matches(p.getFileName()))
                        .forEach(modules::add);
            } catch (IOException e) {
                log.warn("[odb-index] 列模块失败 root={}: {}", root, e.getMessage());
                continue;
            }
            for (Path module : modules) {
                String moduleName = module.getFileName().toString();
                int before = next.size();
                try {
                    Files.walkFileTree(module, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (!tablesMatcher.matches(file)) return FileVisitResult.CONTINUE;
                            String schemaId = extractSchemaId(file);
                            if (schemaId != null && !schemaId.isEmpty()) {
                                String prev = next.put(schemaId, moduleName);
                                if (prev != null && !prev.equals(moduleName)) {
                                    conflicts.add(schemaId + ": " + prev + " vs " + moduleName);
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    log.info("[odb-index] module={} 新增 schemaId={}", moduleName, next.size() - before);
                } catch (IOException e) {
                    log.warn("[odb-index] 遍历模块失败 module={}: {}", moduleName, e.getMessage());
                }
            }
        }

        swap(next);
        log.info("[odb-index] 扫描完成 耗时={}ms 索引 schemaId={} 冲突={}",
                System.currentTimeMillis() - start, schemaIdToModule.size(), conflicts.size());
        if (!conflicts.isEmpty()) {
            log.warn("[odb-index] 检测到 {} 个 schemaId 跨模块冲突，仅保留最后扫到的：{}",
                    conflicts.size(), conflicts.subList(0, Math.min(10, conflicts.size())));
        }
    }

    private void swap(Map<String, String> next) {
        Map<String, String> lower = new HashMap<>(next.size());
        for (Map.Entry<String, String> e : next.entrySet()) {
            lower.put(e.getKey().toLowerCase(), e.getValue());
        }
        this.schemaIdToModule = next;
        this.lowerIdToModule = lower;
    }

    /**
     * 查 schemaId（E 列冒号后首段，如 {@code Kapp_serl_num}）对应的模块名。
     * 先精确匹配，再小写兜底；未命中返回 {@code null}（与
     * {@link com.axonlink.ai.daoindex.sqlinspect.slowsql.OdbLocationDomainResolver} 契约一致）。
     */
    public String lookupModule(String schemaId) {
        if (schemaId == null || schemaId.isEmpty()) return null;
        String m = schemaIdToModule.get(schemaId);
        if (m != null) return m;
        return lowerIdToModule.get(schemaId.toLowerCase());
    }

    /** 当前索引规模，便于运维 / 健康检查 / 单测断言。 */
    public int size() {
        return schemaIdToModule.size();
    }

    /** 测试用：注入预构建映射，跳过文件系统扫描。包私有——只供同包单测使用。 */
    void replaceForTesting(Map<String, String> mapping) {
        Map<String, String> next = new HashMap<>(mapping);
        swap(next);
    }

    /**
     * 读单个 tables.xml 的根标签 {@code <schema>} 的 {@code id} 属性；
     * 解析失败回退文件名去掉 {@code .tables.xml} 后缀（截图约定二者同名）。
     * 禁用外部 DTD/Entity 防 XXE（与 nsql 索引同口径）。
     */
    static String extractSchemaId(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(in));
            Element root = doc.getDocumentElement();
            if (root != null) {
                String id = root.getAttribute("id");
                if (id != null && !id.isEmpty()) return id;
            }
        } catch (Exception e) {
            log.warn("[odb-index] 解析失败 file={}: {}（回退文件名）", file, e.getMessage());
        }
        // 回退：文件名约定与 schema id 同名（Kdpb_cb_alwc_acml.tables.xml ↔ id="Kdpb_cb_alwc_acml"）
        String name = file.getFileName().toString();
        return name.endsWith(".tables.xml") ? name.substring(0, name.length() - ".tables.xml".length()) : null;
    }
}
