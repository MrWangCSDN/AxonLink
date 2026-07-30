package com.axonlink.ai.daoindex.errorcode.definition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorDefinitionIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsExactAndSingleScopeCompatibilityKeys() throws Exception {
        writeErrorXml(tempDir.resolve("project/src/main/resources/CmError.error.xml"),
                "CmError", "Comm", "E0004", "NEW-0004", "account status error");
        writeErrorXml(tempDir.resolve("project/src/main/resources/ApError.error.xml"),
                "ApError", "Aplt", "E0001", "NEW-0001", "application error");

        ErrorDefinitionIndex index = load(tempDir.toString());

        assertEquals("NEW-0004",
                index.lookup(" CmError.Comm ", " E0004 ").orElseThrow().errorCode());
        assertEquals("application error",
                index.lookup("Aplt", "E0001").orElseThrow().message());
    }

    @Test
    void rejectsUnsupportedScopeAndCodeShapes() throws Exception {
        writeErrorXml(tempDir.resolve("CmError.error.xml"),
                "CmError", "Comm", "E0004", "NEW-0004", "description");
        ErrorDefinitionIndex index = load(tempDir.toString());

        assertTrue(index.lookup("", "E0004").isEmpty());
        assertTrue(index.lookup("CmError.Comm.More", "E0004").isEmpty());
        assertTrue(index.lookup("CmError.Comm", "").isEmpty());
        assertTrue(index.lookup("CmError.Comm", "E.0004").isEmpty());
        assertTrue(index.lookup("Missing.Scope", "E0004").isEmpty());
    }

    @Test
    void conflictingSuffixIsAmbiguous() throws Exception {
        writeErrorXml(tempDir.resolve("a/ApError.error.xml"),
                "ApError", "Aplt", "E0001", "NEW-A", "first");
        writeErrorXml(tempDir.resolve("b/OtherError.error.xml"),
                "OtherError", "Aplt", "E0001", "NEW-B", "second");

        ErrorDefinitionIndex index = load(tempDir.toString());

        assertEquals("NEW-A",
                index.lookup("ApError.Aplt", "E0001").orElseThrow().errorCode());
        assertEquals("NEW-B",
                index.lookup("OtherError.Aplt", "E0001").orElseThrow().errorCode());
        assertTrue(index.lookup("Aplt", "E0001").isEmpty());
    }

    @Test
    void conflictingExactKeyIsAmbiguous() throws Exception {
        writeErrorXml(tempDir.resolve("a/CmError.error.xml"),
                "CmError", "Comm", "E0004", "NEW-A", "first");
        writeErrorXml(tempDir.resolve("b/CmError.error.xml"),
                "CmError", "Comm", "E0004", "NEW-B", "second");

        ErrorDefinitionIndex index = load(tempDir.toString());

        assertTrue(index.lookup("CmError.Comm", "E0004").isEmpty());
        assertTrue(index.lookup("Comm", "E0004").isEmpty());
    }

    @Test
    void isolatesMalformedFilesAndIgnoresBuildOutputs() throws Exception {
        writeErrorXml(tempDir.resolve("source/Valid.error.xml"),
                "ValidError", "Good", "E0001", "VALID", "valid definition");
        writeRaw(tempDir.resolve("source/Broken.error.xml"), "<errorConf><errors>");
        writeErrorXml(tempDir.resolve("target/classes/Conflict.error.xml"),
                "ValidError", "Good", "E0001", "TARGET", "must be ignored");
        writeErrorXml(tempDir.resolve("build/resources/Conflict.error.xml"),
                "ValidError", "Good", "E0001", "BUILD", "must be ignored");

        ErrorDefinitionIndex index = load(tempDir.toString());

        assertEquals("VALID",
                index.lookup("ValidError.Good", "E0001").orElseThrow().errorCode());
    }

    @Test
    void ignoresWorkspaceRootInsideExcludedDirectory() throws Exception {
        Path excludedRoot = tempDir.resolve("target/classes");
        writeErrorXml(excludedRoot.resolve("Generated.error.xml"),
                "GeneratedError", "Generated", "E0001", "GENERATED", "must be ignored");

        ErrorDefinitionIndex index = load(excludedRoot.toString());

        assertTrue(index.lookup("GeneratedError.Generated", "E0001").isEmpty());
    }

    @Test
    void rejectsDoctypeWithoutBlockingOtherFiles() throws Exception {
        writeErrorXml(tempDir.resolve("Valid.error.xml"),
                "ValidError", "Good", "E0001", "VALID", "valid definition");
        writeRaw(tempDir.resolve("Unsafe.error.xml"), """
                <?xml version="1.0"?>
                <!DOCTYPE errorConf [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <errorConf id="UnsafeError">
                  <errors id="Unsafe">
                    <error id="E0002" errorCode="UNSAFE" message="&xxe;"/>
                  </errors>
                </errorConf>
                """);

        ErrorDefinitionIndex index = load(tempDir.toString());

        assertEquals("VALID",
                index.lookup("ValidError.Good", "E0001").orElseThrow().errorCode());
        assertTrue(index.lookup("UnsafeError.Unsafe", "E0002").isEmpty());
    }

    private static ErrorDefinitionIndex load(String roots) {
        ErrorDefinitionIndex index = new ErrorDefinitionIndex(roots);
        index.reload();
        return index;
    }

    private static void writeErrorXml(Path path, String confId, String errorsId,
                                      String errorId, String errorCode, String message) throws Exception {
        writeRaw(path, """
                <?xml version="1.0" encoding="UTF-8"?>
                <errorConf xmlns="urn:test" id="%s">
                  <errors id="%s">
                    <error id="%s" errorCode="%s" message="%s"/>
                  </errors>
                </errorConf>
                """.formatted(confId, errorsId, errorId, errorCode, message));
    }

    private static void writeRaw(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
