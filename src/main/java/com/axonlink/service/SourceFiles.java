package com.axonlink.service;

import java.nio.file.Files;
import java.nio.file.Path;

/** 元数据被扫描自 target/classes 时，源码查看统一优先使用工程中的 XML。 */
public final class SourceFiles {
    private SourceFiles() {}
    public static Path preferred(Path original) {
        String path = original.toString().replace('\\', '/');
        if (!path.endsWith(".xml") || !path.contains("/target/classes/")) return original;
        Path source = Path.of(path.replace("/target/classes/", "/src/main/resources/")).toAbsolutePath().normalize();
        return Files.exists(source) ? source : original;
    }
}
