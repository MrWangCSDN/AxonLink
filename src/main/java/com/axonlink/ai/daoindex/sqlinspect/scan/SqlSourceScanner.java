package com.axonlink.ai.daoindex.sqlinspect.scan;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.dto.SqlCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从源码目录扫描 {@code @Statement(sql = "...")} 注解，输出 {@link SqlCandidate} 列表。
 *
 * <p>扫描规则遵循 {@code dao-index-analysis.scan.*} 配置：
 * <pre>
 * project-roots:         多个工程根目录
 *   └─ *-bcc/            module-pattern（* 通配）
 *      └─ target/gen/    source-root-relative
 *         └─ **&#47;tables/**&#47;*.java   file-pattern，且路径必须含 tables 目录
 * </pre>
 *
 * <p>用 regex 抽取，兼容单行和字符串拼接形式（"a" + "b" + "c"）。
 */
@Component
public class SqlSourceScanner {

    private static final Logger log = LoggerFactory.getLogger(SqlSourceScanner.class);

    /**
     * 只匹配 {@code @Statement(} 起点，用 possessive 量词 {@code *+} 阻止回溯。
     * 之前的正则在特殊输入上会发生灾难性回溯（StackOverflowError）。
     */
    private static final Pattern STATEMENT_START_PATTERN = Pattern.compile(
            "@(?:\\w++\\.)*+Statement\\s*+\\(");

    /** 单文件安全上限：超过 2 MB 的源文件直接跳过（生成代码一般远小于此）。 */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /** 提取 Java 文件里的包名（package xxx.yyy;）。 */
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([\\w.]+)\\s*;");
    /** 提取首个 public 顶层类/接口/枚举名。 */
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile(
            "(?:public\\s+)?(?:abstract\\s+|final\\s+)?(?:class|interface|enum)\\s+(\\w+)");

    private final DaoIndexAnalysisProperties props;

    public SqlSourceScanner(DaoIndexAnalysisProperties props) {
        this.props = props;
    }

    /**
     * 按配置扫描所有 project-roots，返回全部 SQL 候选。
     *
     * @param maxPerFile 单文件最多抽取多少条，防御极端文件；{@code <= 0} 表示不限制
     */
    public List<SqlCandidate> scanAll(int maxPerFile) {
        List<SqlCandidate> out = new ArrayList<>();
        DaoIndexAnalysisProperties.Scan cfg = props.getScan();
        if (cfg == null || cfg.getProjectRoots() == null || cfg.getProjectRoots().isEmpty()) {
            log.warn("[dii-scan] 未配置 dao-index-analysis.scan.project-roots，扫描结果为空");
            return out;
        }
        for (String root : cfg.getProjectRoots()) {
            if (root == null || root.isBlank()) continue;
            try {
                scanProjectRoot(Path.of(root.trim()), cfg, out, maxPerFile);
            } catch (Exception e) {
                log.error("[dii-scan] 扫描 project-root={} 失败：{}", root, e.getMessage(), e);
                // 一个工程失败不影响其他工程，继续
            }
        }
        log.info("[dii-scan] 全部扫描完成，共抽取 SQL 候选 {} 条", out.size());
        return out;
    }

    private void scanProjectRoot(Path root,
                                 DaoIndexAnalysisProperties.Scan cfg,
                                 List<SqlCandidate> out,
                                 int maxPerFile) throws IOException {
        if (!Files.isDirectory(root)) {
            log.warn("[dii-scan] project-root 不存在或不是目录：{}", root);
            return;
        }
        String moduleGlob = cfg.getModulePattern() == null ? "*-bcc" : cfg.getModulePattern();
        String relative   = cfg.getSourceRootRelative() == null ? "target/gen" : cfg.getSourceRootRelative();
        String requiredDir = cfg.getRequiredParentDir();

        // 递归查找所有匹配 module-pattern 的目录（最多 5 层，防止误入 node_modules 等死目录）
        List<Path> modules = new ArrayList<>();
        try (var walker = Files.walk(root, 5)) {
            walker.filter(Files::isDirectory)
                    .filter(p -> matchGlob(p.getFileName().toString(), moduleGlob))
                    .forEach(modules::add);
        }
        if (modules.isEmpty()) {
            log.warn("[dii-scan] 在 project-root={} 下未找到匹配 module-pattern={} 的子目录",
                    root, moduleGlob);
            return;
        }
        log.info("[dii-scan] project-root={} 命中 {} 个 {} 模块：{}",
                root, modules.size(), moduleGlob,
                modules.stream().map(p -> p.getFileName().toString()).toList());

        for (Path mod : modules) {
            Path srcDir = mod.resolve(relative);
            if (!Files.isDirectory(srcDir)) {
                log.debug("[dii-scan] 模块 {} 下没有 {}，跳过", mod.getFileName(), relative);
                continue;
            }
            scanDir(srcDir, requiredDir, mod.getFileName().toString(), out, maxPerFile);
        }
    }

    private void scanDir(Path srcDir, String requiredParentDir,
                         String projectName, List<SqlCandidate> out, int maxPerFile) throws IOException {
        Files.walkFileTree(srcDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!file.getFileName().toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                if (requiredParentDir != null && !requiredParentDir.isBlank()) {
                    if (!pathContainsSegment(file, requiredParentDir)) return FileVisitResult.CONTINUE;
                }
                try {
                    extractFromFile(file, projectName, out, maxPerFile);
                } catch (Exception e) {
                    log.warn("[dii-scan] 解析文件失败 {}：{}", file, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void extractFromFile(Path file, String projectName,
                                 List<SqlCandidate> out, int maxPerFile) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            log.debug("[dii-scan] 跳过超大文件 {} ({} bytes)", file, size);
            return;
        }
        String src = Files.readString(file, StandardCharsets.UTF_8);

        String pkg = firstGroup(PACKAGE_PATTERN.matcher(src));
        String cls = firstGroup(CLASS_NAME_PATTERN.matcher(src));
        String classFqn = (pkg == null ? "" : pkg + ".") + (cls == null ? file.getFileName().toString() : cls);

        // Step 1: 找所有 @Statement( 起点
        Matcher starts = STATEMENT_START_PATTERN.matcher(src);
        int count = 0;
        int searchPos = 0;
        while (starts.find(searchPos)) {
            if (maxPerFile > 0 && count >= maxPerFile) break;
            int openIdx = starts.end() - 1;   // 指向 '('
            int closeIdx = findMatchingRightParen(src, openIdx);
            if (closeIdx < 0) {
                log.debug("[dii-scan] 注解括号不闭合，跳过 file={} offset={}", file, openIdx);
                searchPos = starts.end();   // 下一个位置继续找
                continue;
            }
            String body = src.substring(openIdx + 1, closeIdx);

            // Step 2: 手工扫描注解体，找 sql = "xxx" + "yyy" 的拼接
            String sql = extractSqlAttributeValue(body);
            if (sql != null && !sql.isBlank()) {
                out.add(new SqlCandidate(sql.trim(), projectName, classFqn, file.toString()));
                count++;
            }
            searchPos = closeIdx + 1;
        }
    }

    /**
     * 在 {@code @Statement(...)} 的注解体里找 {@code sql} 属性的值。
     *
     * <p>完全手工扫描，不用回溯正则，避免栈爆。
     *
     * <p>处理步骤：
     * <ol>
     *   <li>找 identifier = "sql"（作为单词边界）</li>
     *   <li>跳过空白和 {@code =}</li>
     *   <li>连续解析 {@code "..." + "..."}，拼接字符串字面量</li>
     * </ol>
     *
     * @return 拼接后的 SQL 文本；没找到 sql 属性或解析失败返回 null
     */
    private static String extractSqlAttributeValue(String body) {
        int i = 0, n = body.length();
        while (i < n) {
            // 找 "sql" 单词起点：前一位要么是非字母数字下划线、要么是开头
            if (body.charAt(i) == 's' && i + 2 < n
                    && body.charAt(i + 1) == 'q' && body.charAt(i + 2) == 'l') {
                boolean boundaryBefore = (i == 0) || !isIdentifierChar(body.charAt(i - 1));
                boolean boundaryAfter  = (i + 3 >= n) || !isIdentifierChar(body.charAt(i + 3));
                if (boundaryBefore && boundaryAfter) {
                    int j = i + 3;
                    // 跳空白
                    while (j < n && Character.isWhitespace(body.charAt(j))) j++;
                    if (j < n && body.charAt(j) == '=') {
                        j++;
                        return parseStringConcat(body, j);
                    }
                }
            }
            i++;
        }
        return null;
    }

    /**
     * 从 {@code pos} 起解析 {@code "abc" + "def\n"} 形式的 Java 字符串拼接，返回拼好的内容。
     * 遇到非字符串、非加号、非空白的字符就停（比如碰到 {@code ,} 或 {@code )}）。
     */
    private static String parseStringConcat(String s, int pos) {
        StringBuilder out = new StringBuilder();
        int i = pos, n = s.length();
        boolean sawAnyString = false;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '+') {
                if (!sawAnyString) return null;  // 非法：开头就 +
                i++;
                continue;
            }
            if (c == '\"') {
                // 解析一个字符串字面量
                i++;   // 跳过起始 "
                StringBuilder piece = new StringBuilder();
                while (i < n) {
                    char ch = s.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        char next = s.charAt(i + 1);
                        switch (next) {
                            case 'n': piece.append('\n'); break;
                            case 't': piece.append('\t'); break;
                            case 'r': piece.append('\r'); break;
                            case '\\': piece.append('\\'); break;
                            case '\"': piece.append('\"'); break;
                            case '\'': piece.append('\''); break;
                            default: piece.append('\\').append(next);
                        }
                        i += 2;
                    } else if (ch == '\"') {
                        i++;   // 跳过闭 "
                        break;
                    } else {
                        piece.append(ch);
                        i++;
                    }
                }
                out.append(piece);
                sawAnyString = true;
                continue;
            }
            // 其他字符出现 → 拼接结束
            break;
        }
        return sawAnyString ? out.toString() : null;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * 从开括号 {@code '('} 位置起，找到对应的闭括号 {@code ')'} 索引；
     * 嵌套括号、字符串字面量、字符字面量、转义字符都要正确跳过。
     *
     * @return 闭括号的索引；找不到返回 -1
     */
    private static int findMatchingRightParen(String s, int openParenIdx) {
        int depth = 0;
        int i = openParenIdx;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\"') {
                // 跳过字符串字面量
                i = skipStringLiteral(s, i);
                continue;
            } else if (c == '\'') {
                i = skipCharLiteral(s, i);
                continue;
            } else if (c == '/' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '/') {
                    // 行注释
                    int nl = s.indexOf('\n', i + 2);
                    i = (nl < 0) ? s.length() : nl + 1;
                    continue;
                } else if (next == '*') {
                    // 块注释
                    int end = s.indexOf("*/", i + 2);
                    i = (end < 0) ? s.length() : end + 2;
                    continue;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    /** 从 '"' 位置开始，返回闭合后的下一个位置（指向闭合 '"' 之后）。 */
    private static int skipStringLiteral(String s, int startQuoteIdx) {
        int i = startQuoteIdx + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { i += 2; continue; }
            if (c == '\"') return i + 1;
            i++;
        }
        return s.length();
    }

    private static int skipCharLiteral(String s, int startQuoteIdx) {
        int i = startQuoteIdx + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { i += 2; continue; }
            if (c == '\'') return i + 1;
            i++;
        }
        return s.length();
    }


    private static boolean matchGlob(String name, String glob) {
        if (glob == null || glob.isEmpty()) return true;
        String regex = "^" + glob.replace(".", "\\.").replace("*", ".*") + "$";
        return name.matches(regex);
    }

    private static boolean pathContainsSegment(Path p, String seg) {
        for (Path part : p) {
            if (part.getFileName().toString().equalsIgnoreCase(seg)) return true;
        }
        return false;
    }

    private static String firstGroup(Matcher m) {
        return m.find() ? m.group(1) : null;
    }
}
