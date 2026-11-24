package com.axonlink.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

final class SourceLayout {
    private static final Logger log = LoggerFactory.getLogger(SourceLayout.class);
    private final List<Path> roots;

    SourceLayout(List<Path> roots) {
        this.roots = roots;
    }

    List<Path> collectJava(List<Path> sourceRoots, Graph graph, String scanId) {
        Set<Path> javaRoots = new LinkedHashSet<>(sourceRoots);
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root, 8)) {
                walk.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Path.of("src/main/java")) || p.endsWith(Path.of("target/gen")))
                    .forEach(javaRoots::add);
            } catch (IOException | UncheckedIOException e) {
                graph.report(new Graph.Issue(null, root.toString(), -1, "SOURCE_ROOT", e.getMessage()));
            }
        }
        log.debug("[scan={}] 发现 Java 源码目录 count={} roots={}", scanId, javaRoots.size(), javaRoots);
        Set<Path> javaFiles = new LinkedHashSet<>();
        for (Path source : javaRoots) {
            try (Stream<Path> walk = Files.walk(source)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") && !p.toString().replace('\\', '/').contains("/test/"))
                    .forEach(javaFiles::add);
            } catch (IOException | UncheckedIOException e) {
                graph.report(new Graph.Issue(null, source.toString(), -1, "SOURCE_ROOT", e.getMessage()));
            }
        }
        return javaFiles.stream().sorted().toList();
    }

    String parentProject(Path file) {
        Path relative = relativeToWorkspace(file);
        return relative == null ? "unknown" : relative.getName(0).toString();
    }

    String module(Path file) {
        Path relative = relativeToWorkspace(file);
        return relative == null || relative.getNameCount() < 2
            ? "unknown" : relative.getName(0) + "/" + relative.getName(1);
    }

    private Path relativeToWorkspace(Path file) {
        for (Path root : roots) {
            if (file.startsWith(root) && !file.equals(root)) {
                return root.relativize(file);
            }
        }
        return null;
    }
}
