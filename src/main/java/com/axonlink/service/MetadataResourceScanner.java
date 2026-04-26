package com.axonlink.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 统一收敛 XML 元数据扫描规则：
 * 只认源码目录 {@code src/main/resources}，并在遍历阶段直接跳过 {@code target} 子树。
 */
@Component
public class MetadataResourceScanner {

    private static final Logger log = LoggerFactory.getLogger(MetadataResourceScanner.class);
    private static final String SOURCE_METADATA_SUFFIX = "/src/main/resources";

    public List<Path> collectMetadataFiles(String workspaceRoots,
                                           int workspaceWalkDepth,
                                           int fileWalkDepth,
                                           Predicate<Path> fileFilter) {
        List<Path> metadataRoots = collectMetadataRoots(workspaceRoots, workspaceWalkDepth);
        if (metadataRoots.isEmpty()) {
            return List.of();
        }

        List<Path> files = metadataRoots.parallelStream()
            .map(root -> scanFiles(root, fileWalkDepth, fileFilter))
            .flatMap(List::stream)
            .distinct()
            .sorted()
            .toList();
        return files;
    }

    public List<Path> collectMetadataRoots(String workspaceRoots, int workspaceWalkDepth) {
        if (isBlank(workspaceRoots)) {
            return List.of();
        }

        Map<String, Path> metadataRoots = new LinkedHashMap<>();
        for (String root : workspaceRoots.split(",")) {
            String trimmed = root.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path workspaceRoot = Path.of(trimmed);
            if (!Files.exists(workspaceRoot)) {
                continue;
            }
            collectMetadataRoots(workspaceRoot, workspaceWalkDepth, metadataRoots);
        }
        return metadataRoots.values().stream().sorted().toList();
    }

    private List<Path> scanFiles(Path metadataRoot, int fileWalkDepth, Predicate<Path> fileFilter) {
        try (Stream<Path> walk = Files.walk(metadataRoot, fileWalkDepth)) {
            return walk.filter(Files::isRegularFile)
                .filter(fileFilter)
                .sorted()
                .toList();
        } catch (Exception e) {
            log.debug("[MetadataScanner] 扫描 {} 失败: {}", metadataRoot, e.getMessage());
            return List.of();
        }
    }

    private void collectMetadataRoots(Path workspaceRoot,
                                      int workspaceWalkDepth,
                                      Map<String, Path> metadataRoots) {
        try {
            Files.walkFileTree(workspaceRoot, Set.of(), workspaceWalkDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path fileName = dir.getFileName();
                    if (fileName != null && "target".equalsIgnoreCase(fileName.toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    String normalized = normalize(dir);
                    if (normalized.endsWith(SOURCE_METADATA_SUFFIX)) {
                        registerMetadataRoot(metadataRoots, dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            log.debug("[MetadataScanner] 扫描 {} 失败: {}", workspaceRoot, e.getMessage());
        }
    }

    private void registerMetadataRoot(Map<String, Path> metadataRoots, Path candidate) {
        String normalized = normalize(candidate);
        String moduleKey = normalized.substring(0, normalized.length() - SOURCE_METADATA_SUFFIX.length());
        Path existing = metadataRoots.get(moduleKey);
        if (existing == null || normalized.compareTo(normalize(existing)) < 0) {
            metadataRoots.put(moduleKey, candidate);
        }
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
