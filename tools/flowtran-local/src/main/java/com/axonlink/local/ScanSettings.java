package com.axonlink.local;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

record ScanSettings(String workspaceRoots, String sourceRoots, String includePackages, Integer maxFileKb) {
    // 按 AxonLink AST 建图的默认前缀和 Java 文件大小设置初始化表单。
    static final ScanSettings DEFAULTS = new ScanSettings("", "", "cn.sunline.ltts.busi", 500);
    // 按本地验证工程的启动目录定位示例工作区。
    static final String EXAMPLE_WORKSPACE = "examples/workspace";

    ScanSettings normalize() {
        String workspace = normalizeRoots(workspaceRoots);
        String sources = normalizeRoots(sourceRoots);
        if (workspace.isEmpty() && sources.isEmpty()) {
            throw new IllegalArgumentException("请填写工作区目录或 Java 源码目录");
        }
        String packages = String.join(",", split(includePackages));
        int limit = maxFileKb == null ? DEFAULTS.maxFileKb() : maxFileKb;
        if (limit <= 0) {
            throw new IllegalArgumentException("Java 文件大小上限须大于 0 KB");
        }
        return new ScanSettings(workspace, sources,
            packages.isEmpty() ? DEFAULTS.includePackages() : packages, limit);
    }

    List<Path> workspacePaths() {
        return paths(workspaceRoots);
    }

    List<Path> sourcePaths() {
        return paths(sourceRoots);
    }

    private static String normalizeRoots(String roots) {
        return String.join(",", paths(roots).stream().map(path -> {
            Path absolute = path.toAbsolutePath().normalize();
            if (!Files.isDirectory(absolute)) {
                throw new IllegalArgumentException("目录不存在或不可读取 " + absolute);
            }
            return absolute.toString();
        }).distinct().toList());
    }

    private static List<Path> paths(String text) {
        return split(text).stream().map(Path::of).toList();
    }

    private static List<String> split(String text) {
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(text.split("[,\\r\\n]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
