package com.axonlink.controller;

import com.axonlink.service.ProjectIndexer;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 源码查看 + 跨文件导航接口
 *
 * GET /api/source              读取指定文件，返回源码 + AST 符号表
 * GET /api/source/find         按类名查找文件（优先查全局索引，降级到文件系统遍历）
 * GET /api/source/index/stats  查看当前索引状态
 */
@RestController
@RequestMapping("/api/source")
public class SourceController {

    @Autowired
    private ProjectIndexer projectIndexer;

    // ── 主接口：读取文件 ─────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<Map<String, Object>> getSource(
            @RequestParam                                String filePath,
            @RequestParam(required = false, defaultValue = "") String methodName) {
        try {
            Path path = Paths.get(filePath).toAbsolutePath().normalize();
            if (!path.toString().endsWith(".java")) return ResponseEntity.badRequest().build();
            if (!Files.exists(path) || !Files.isReadable(path)) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(buildResponse(path, methodName));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 跨文件导航：按类名或 FQN 查找源文件
     * GET /api/source/find?className=Foo&methodName=bar         （简单名，有冲突时取首次）
     * GET /api/source/find?fqn=com.example.Foo&methodName=bar  （FQN 精确，解决同名冲突）
     *
     * 优先级：fqn > className（索引命中）> className（文件系统降级）
     */
    @GetMapping("/find")
    public ResponseEntity<Map<String, Object>> findClass(
            @RequestParam(required = false, defaultValue = "") String currentFilePath,
            @RequestParam(required = false, defaultValue = "") String className,
            @RequestParam(required = false, defaultValue = "") String fqn,
            @RequestParam(required = false, defaultValue = "") String methodName) {
        try {
            // ① FQN 精确查找（无歧义，推荐用于有同名冲突的类）
            if (!fqn.isBlank()) {
                Optional<Path> byFqn = projectIndexer.findByFqn(fqn);
                if (byFqn.isPresent()) return ResponseEntity.ok(buildResponse(byFqn.get(), methodName));
                // FQN 未命中（索引可能还在构建）→ 尝试从 FQN 末尾提取 simpleName 降级
                String simpleFromFqn = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                Optional<Path> bySimple = projectIndexer.findBySimpleName(simpleFromFqn);
                if (bySimple.isPresent()) return ResponseEntity.ok(buildResponse(bySimple.get(), methodName));
                return ResponseEntity.notFound().build();
            }

            // ② 简单类名查找（全局索引优先）
            if (!className.isBlank()) {
                Optional<Path> indexed = projectIndexer.findBySimpleName(className);
                if (indexed.isPresent()) return ResponseEntity.ok(buildResponse(indexed.get(), methodName));

                // ③ 降级：在当前文件所在工程内遍历文件系统
                if (!currentFilePath.isBlank()) {
                    Path current    = Paths.get(currentFilePath).toAbsolutePath().normalize();
                    Path sourceRoot = deriveSourceRoot(current);
                    try (Stream<Path> walk = Files.walk(sourceRoot)) {
                        Optional<Path> found = walk
                                .filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().equals(className + ".java"))
                                .findFirst();
                        if (found.isPresent()) return ResponseEntity.ok(buildResponse(found.get(), methodName));
                    }
                }
            }

            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── 索引状态接口（调试用）────────────────────────────────────────────────
    @GetMapping("/index/stats")
    public ResponseEntity<Map<String, Object>> getIndexStats() {
        Map<String, Object> stats = projectIndexer.getStats();
        stats.put("indexed", projectIndexer.isIndexed());
        return ResponseEntity.ok(stats);
    }

    /**
     * 实时索引进度接口（前端轮询用）
     * GET /api/source/index/progress
     *
     * 返回字段：
     *   phase      : idle | collecting | parsing | done | error
     *   indexing   : 是否正在索引
     *   total      : 发现的总文件数
     *   parsed     : 深度解析完成数（< maxFileKb 的文件）
     *   skipped    : 大文件轻量处理数（只登记类名，跳过 AST）
     *   processed  : parsed + skipped
     *   percent    : 0-100
     *   classCount : 已入索引的类总数
     *   elapsedMs  : 耗时（毫秒）
     *   maxFileKb  : 当前大文件阈值
     */
    @GetMapping("/index/progress")
    public ResponseEntity<Map<String, Object>> getIndexProgress() {
        return ResponseEntity.ok(projectIndexer.getIndexProgress());
    }

    // ── 热重载接口：编译后调用此接口更新索引，无需重启服务 ────────────────────
    @PostMapping("/index/refresh")
    public ResponseEntity<Map<String, Object>> refreshIndex() {
        Map<String, Object> result = projectIndexer.refresh();
        return ResponseEntity.ok(result);
    }

    // ── 索引列表接口（调试用）────────────────────────────────────────────────
    @GetMapping("/index/classes")
    public ResponseEntity<Map<String, String>> listIndexedClasses() {
        Map<String, String> result = new LinkedHashMap<>();
        projectIndexer.getAllClasses()
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> result.put(e.getKey(), e.getValue().toString()));
        return ResponseEntity.ok(result);
    }

    // ── 公共：组装响应体 ─────────────────────────────────────────────────────
    private Map<String, Object> buildResponse(Path path, String methodName) throws IOException {
        String source   = Files.readString(path);
        String filename = path.getFileName().toString();

        // 包名（快速从源码前几行提取）
        String pkg = "";
        for (String line : source.split("\n", 20)) {
            String t = line.trim();
            if (t.startsWith("package ")) { pkg = t.replace("package ", "").replace(";", "").trim(); break; }
        }

        // 类型（class / interface / enum / abstract class）
        String kind = "class";
        if (source.contains(" interface "))         kind = "interface";
        else if (source.contains(" enum "))         kind = "enum";
        else if (source.contains("abstract class")) kind = "abstract class";

        // 优先取缓存的 symbols/typeMap，避免重复解析
        List<Map<String, Object>> symbols;
        Map<String, String>       typeMap;
        if (projectIndexer.isIndexed() && !projectIndexer.getSymbols(path).isEmpty()) {
            symbols = projectIndexer.getSymbols(path);
            typeMap = projectIndexer.getTypeMap(path);
        } else {
            ParsedInfo info = parseJava(source);
            symbols = info.symbols();
            typeMap = info.typeMap();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filename",  filename);
        result.put("source",    source);
        result.put("filePath",  path.toString());
        result.put("pkg",       pkg);
        result.put("kind",      kind);
        result.put("symbols",   symbols);
        result.put("typeMap",   typeMap);
        if (!methodName.isBlank()) result.put("entryMethod", methodName);
        return result;
    }

    // ── JavaParser 解析（索引未命中时的降级解析）────────────────────────────
    // 与 ProjectIndexer 保持一致：递归 visitor 同时处理内部类/接口/枚举/方法/字段
    private ParsedInfo parseJava(String source) {
        List<Map<String, Object>> symbols = new ArrayList<>();
        Map<String, String>       typeMap = new LinkedHashMap<>();
        try {
            ParserConfiguration cfg = new ParserConfiguration();
            cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            ParseResult<CompilationUnit> result = new JavaParser(cfg).parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) return new ParsedInfo(symbols, typeMap);

            CompilationUnit cu = result.getResult().get();

            cu.accept(new VoidVisitorAdapter<Void>() {

                // ── 类 / 接口 ───────────────────────────────────────────────
                @Override
                public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                    addTypeSym(n, n.isInterface() ? "interface" : "class", symbols);
                    super.visit(n, arg); // 递归处理内部类、方法、字段
                }

                // ── 枚举 ─────────────────────────────────────────────────────
                @Override
                public void visit(EnumDeclaration n, Void arg) {
                    addTypeSym(n, "enum", symbols);
                    super.visit(n, arg);
                }

                // ── 方法 ─────────────────────────────────────────────────────
                @Override
                public void visit(MethodDeclaration n, Void arg) {
                    super.visit(n, arg);
                    Map<String, Object> sym = new LinkedHashMap<>();
                    sym.put("name", n.getNameAsString());
                    sym.put("line", n.getName().getBegin().map(p -> p.line).orElse(-1));
                    sym.put("type", "method");
                    symbols.add(sym);
                }

                // ── 字段 ─────────────────────────────────────────────────────
                @Override
                public void visit(FieldDeclaration n, Void arg) {
                    super.visit(n, arg);
                    for (VariableDeclarator v : n.getVariables()) {
                        typeMap.put(v.getNameAsString(),
                                v.getTypeAsString().replaceAll("<.*", "").trim());
                    }
                }

                private void addTypeSym(TypeDeclaration<?> n, String declType,
                                        List<Map<String, Object>> out) {
                    Map<String, Object> sym = new LinkedHashMap<>();
                    sym.put("name", n.getNameAsString());
                    sym.put("line", n.getName().getBegin().map(p -> p.line).orElse(-1));
                    sym.put("type", declType);
                    out.add(sym);
                }
            }, null);
        } catch (Exception ignored) {}
        return new ParsedInfo(symbols, typeMap);
    }

    // ── 从当前文件路径推导 Maven 源码根目录（降级时使用）────────────────────
    private Path deriveSourceRoot(Path filePath) {
        String s = filePath.toString();
        int idx = s.indexOf("/src/main/java/");
        if (idx >= 0) return Path.of(s.substring(0, idx + "/src/main/java".length()));
        idx = s.indexOf("/src/main/kotlin/");
        if (idx >= 0) return Path.of(s.substring(0, idx + "/src/main/kotlin".length()));
        return filePath.getParent();
    }

    private record ParsedInfo(List<Map<String, Object>> symbols, Map<String, String> typeMap) {}
}
