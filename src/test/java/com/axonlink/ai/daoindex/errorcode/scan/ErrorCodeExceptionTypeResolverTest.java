package com.axonlink.ai.daoindex.errorcode.scan;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.axonlink.ai.daoindex.errorcode.scan.ErrorCodeExceptionTypeResolver.Match.MATCH;
import static com.axonlink.ai.daoindex.errorcode.scan.ErrorCodeExceptionTypeResolver.Match.NO_MATCH;
import static com.axonlink.ai.daoindex.errorcode.scan.ErrorCodeExceptionTypeResolver.Match.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorCodeExceptionTypeResolverTest {

    @TempDir
    Path directory;

    private Path businessSources;
    private Path generatedSources;

    @BeforeEach
    void createSourceRoots() throws Exception {
        businessSources = Files.createDirectories(directory.resolve("business"));
        generatedSources = Files.createDirectories(directory.resolve("generated"));
        writeSource(businessSources, "example/error/BaseBusinessException.java", """
                package example.error;
                public class BaseBusinessException extends RuntimeException {}
                """);
        writeSource(businessSources, "example/error/BusinessException.java", """
                package example.error;
                public class BusinessException extends BaseBusinessException {}
                """);
        writeSource(generatedSources, "example/errors/CmError.java", """
                package example.errors;
                import example.error.BusinessException;
                public class CmError {
                    public static class Chrg {
                        public static BusinessException E1654() {
                            return new BusinessException();
                        }
                    }
                }
                """);
    }

    @Test
    void resolvesNestedFactoryAndExceptionHierarchyAcrossBothSourceRoots() throws Exception {
        assertEquals(MATCH, match("CmError.Chrg.E1654()", "Exception"));
    }

    @Test
    void matchesImportedSourceBaseException() throws Exception {
        assertEquals(MATCH, match("""
                import example.errors.CmError;
                import example.error.BaseBusinessException;
                """, "CmError.Chrg.E1654()", "BaseBusinessException"));
    }

    @Test
    void resolvesFactoryAndCatchTypeFromSamePackage() throws Exception {
        CompilationUnit unit = parse("""
                package example.error;
                class Operation {
                    void execute() {
                        try {
                            throw example.errors.CmError.Chrg.E1654();
                        } catch (BaseBusinessException caught) {
                        }
                    }
                }
                """);

        assertEquals(MATCH, match(unit));
    }

    @Test
    void resolvesImportedNestedFactoryClass() throws Exception {
        assertEquals(MATCH, match("import example.errors.CmError.Chrg;",
                "Chrg.E1654()", "Exception"));
    }

    @Test
    void resolvesFactoryThroughWildcardImport() throws Exception {
        assertEquals(MATCH, match("import example.errors.*;",
                "CmError.Chrg.E1654()", "Exception"));
    }

    @Test
    void unrelatedConcreteCatchCannotHandleBusinessException() throws Exception {
        assertEquals(NO_MATCH, match("CmError.Chrg.E1654()", "java.io.IOException"));
    }

    @Test
    void narrowerCatchMayInterceptSubtypeOfDeclaredReturnType() throws Exception {
        writeSource(businessSources, "example/error/SpecialBusinessException.java", """
                package example.error;
                public class SpecialBusinessException extends BusinessException {}
                """);

        assertEquals(UNKNOWN, match("CmError.Chrg.E1654()", "example.error.SpecialBusinessException"));
    }

    @Test
    void broadDeclaredReturnTypeDoesNotMakeNarrowRuntimeCatchDefinite() throws Exception {
        writeFactoryReturning("RuntimeException", "new IllegalArgumentException()");

        assertEquals(UNKNOWN, match("CmError.Chrg.E1654()", "IllegalArgumentException"));
    }

    @Test
    void multiCatchMatchesWhenOneAlternativeAcceptsDeclaredType() throws Exception {
        assertEquals(MATCH, match("CmError.Chrg.E1654()",
                "java.io.IOException | example.error.BaseBusinessException"));
    }

    @Test
    void multiCatchWithOnlyDisjointAlternativesDoesNotMatch() throws Exception {
        assertEquals(NO_MATCH, match("CmError.Chrg.E1654()",
                "java.io.IOException | java.lang.Error"));
    }

    @Test
    void multiCatchWithPotentialNarrowMatchStaysUnknown() throws Exception {
        writeFactoryReturning("RuntimeException", "new IllegalArgumentException()");

        assertEquals(UNKNOWN, match("CmError.Chrg.E1654()",
                "java.io.IOException | IllegalArgumentException"));
    }

    @Test
    void missingFactoryKeepsExceptionCatchUnknown() throws Exception {
        assertEquals(UNKNOWN, match("MissingFactory.E1654()", "Exception"));
    }

    @Test
    void unresolvedCatchTypeStaysUnknown() throws Exception {
        assertEquals(UNKNOWN, match("CmError.Chrg.E1654()", "MissingException"));
    }

    @Test
    void multiCatchWithMissingAlternativeAndNoDefiniteMatchStaysUnknown() throws Exception {
        assertEquals(UNKNOWN, match("CmError.Chrg.E1654()",
                "java.io.IOException | MissingException"));
    }

    @Test
    void definiteMultiCatchAlternativeStillMatchesWhenAnotherTypeIsMissing() throws Exception {
        assertEquals(MATCH, match("CmError.Chrg.E1654()",
                "MissingException | example.error.BaseBusinessException"));
    }

    @Test
    void missingExceptionBaseDoesNotProduceFalseNoMatch() throws Exception {
        writeSource(businessSources, "example/error/BusinessException.java", """
                package example.error;
                public class BusinessException extends MissingBaseException {}
                """);

        assertEquals(UNKNOWN, match("CmError.Chrg.E1654()", "Exception"));
    }

    @Test
    void confirmedJavaLangThrowableAcceptsUnknownFactoryType() throws Exception {
        assertEquals(MATCH, match("MissingFactory.E1654()", "java.lang.Throwable"));
    }

    @Test
    void shadowedThrowableNameDoesNotActAsUniversalCatch() throws Exception {
        writeSource(businessSources, "example/error/Throwable.java", """
                package example.error;
                public class Throwable extends RuntimeException {}
                """);

        assertEquals(UNKNOWN, match("import example.error.Throwable;",
                "MissingFactory.E1654()", "Throwable"));
    }

    @Test
    void overloadedFactoryUsesSelectedDeclaredReturnType() throws Exception {
        writeSource(generatedSources, "example/errors/CmError.java", """
                package example.errors;
                public class CmError {
                    public static class Chrg {
                        public static java.io.IOException E1654(String value) {
                            return new java.io.IOException(value);
                        }
                        public static example.error.BusinessException E1654(int value) {
                            return new example.error.BusinessException();
                        }
                    }
                }
                """);

        assertEquals(MATCH, match("CmError.Chrg.E1654(\"value\")", "java.io.IOException"));
        assertEquals(NO_MATCH, match("CmError.Chrg.E1654(1)", "java.io.IOException"));
    }

    @Test
    void duplicateFactoryAcrossSourceRootsStaysUnknown() throws Exception {
        writeSource(businessSources, "example/errors/CmError.java", """
                package example.errors;
                public class CmError {
                    public static class Chrg {
                        public static java.io.IOException E1654() {
                            return new java.io.IOException();
                        }
                    }
                }
                """);

        assertEquals(UNKNOWN, match("CmError.Chrg.E1654()", "java.io.IOException"));
    }

    @Test
    void duplicateFactoryDefinedInOperationCompilationUnitStaysUnknown() throws Exception {
        writeSource(generatedSources, "example/app/CmError.java", """
                package example.app;
                public class CmError {
                    public static class Chrg {
                        public static Error E1654() {
                            return new Error();
                        }
                    }
                }
                """);
        CompilationUnit unit = parse("""
                package example.app;
                class Operation {
                    void execute() {
                        try {
                            throw CmError.Chrg.E1654();
                        } catch (Exception caught) {
                        }
                    }
                }
                class CmError {
                    public static class Chrg {
                        public static RuntimeException E1654() {
                            return new RuntimeException();
                        }
                    }
                }
                """);

        assertEquals(UNKNOWN, match(unit));
    }

    @Test
    void duplicateLexicalFactoryScopeWithDifferentInheritedMethodStaysUnknown() throws Exception {
        writeSource(generatedSources, "example/app/Codes.java", """
                package example.app;
                class Codes extends BadFactory {}
                class BadFactory {
                    static Error E1654() {
                        return new Error();
                    }
                }
                """);
        CompilationUnit unit = parse("""
                package example.app;
                class Operation {
                    void execute() {
                        try {
                            throw Codes.E1654();
                        } catch (Exception caught) {
                        }
                    }
                }
                class Codes extends GoodFactory {}
                class GoodFactory {
                    static RuntimeException E1654() {
                        return new RuntimeException();
                    }
                }
                """);

        assertEquals(UNKNOWN, match(unit));
    }

    @Test
    void duplicateVariableFactoryScopeWithInheritedMethodStaysUnknown() throws Exception {
        writeSource(generatedSources, "example/app/Codes.java", """
                package example.app;
                class Codes extends BadFactory {}
                class BadFactory {
                    static Error E1654() {
                        return new Error();
                    }
                }
                """);
        CompilationUnit unit = parse("""
                package example.app;
                class Operation {
                    void execute(Codes codes) {
                        try {
                            throw codes.E1654();
                        } catch (Exception caught) {
                        }
                    }
                }
                class Codes extends GoodFactory {}
                class GoodFactory {
                    static RuntimeException E1654() {
                        return new RuntimeException();
                    }
                }
                """);

        assertEquals(UNKNOWN, match(unit));
    }

    @Test
    void unambiguousVariableFactoryScopeStillMatches() throws Exception {
        CompilationUnit unit = parse("""
                package example.app;
                class Operation {
                    void execute(Codes codes) {
                        try {
                            throw codes.E1654();
                        } catch (Exception caught) {
                        }
                    }
                }
                class Codes {
                    RuntimeException E1654() {
                        return new RuntimeException();
                    }
                }
                """);

        assertEquals(MATCH, match(unit));
    }

    @Test
    void duplicateIntermediateReceiverTypeStaysUnknown() throws Exception {
        writeAmbiguousHolder();
        CompilationUnit unit = parse("""
                package example.app;
                class Operation {
                    void execute(Holder holder) {
                        try {
                            throw holder.codes.E1654();
                        } catch (Exception caught) {
                        }
                    }
                }
                class Holder {
                    GoodFactory codes;
                }
                class GoodFactory {
                    RuntimeException E1654() {
                        return new RuntimeException();
                    }
                }
                """);

        assertEquals(UNKNOWN, match(unit));
    }

    @Test
    void duplicateLexicalSuperclassWithUnqualifiedFieldStaysUnknown() throws Exception {
        writeAmbiguousHolder();
        CompilationUnit unit = parse("""
                package example.app;
                class Operation extends Holder {
                    void execute() {
                        try {
                            throw codes.E1654();
                        } catch (Exception caught) {
                        }
                    }
                }
                class Holder {
                    GoodFactory codes;
                }
                class GoodFactory {
                    RuntimeException E1654() {
                        return new RuntimeException();
                    }
                }
                """);

        assertEquals(UNKNOWN, match(unit));
    }

    @Test
    void duplicateLexicalSuperclassWithUnqualifiedFactoryMethodStaysUnknown() throws Exception {
        writeAmbiguousHolder();
        CompilationUnit unit = parse("""
                package example.app;
                class Operation extends Holder {
                    void execute() {
                        try {
                            throw factory().E1654();
                        } catch (Exception caught) {
                        }
                    }
                }
                class Holder {
                    GoodFactory factory() {
                        return null;
                    }
                }
                class GoodFactory {
                    RuntimeException E1654() {
                        return new RuntimeException();
                    }
                }
                """);

        assertEquals(UNKNOWN, match(unit));
    }

    @Test
    void duplicateReceiverInVarInitializerStaysUnknown() throws Exception {
        writeAmbiguousHolder();
        CompilationUnit unit = parse("""
                package example.app;
                class Operation {
                    void execute(Holder holder) {
                        var factory = holder.codes;
                        try {
                            throw factory.E1654();
                        } catch (Exception caught) {
                        }
                    }
                }
                class Holder {
                    GoodFactory codes;
                }
                class GoodFactory {
                    RuntimeException E1654() {
                        return new RuntimeException();
                    }
                }
                """);

        assertEquals(UNKNOWN, match(unit));
    }

    @Test
    void unrelatedUnresolvedContextTypeDoesNotInvalidateKnownMatch() throws Exception {
        CompilationUnit unit = parse("""
                package example.app;
                import example.errors.CmError;
                class Operation {
                    MissingDependency unused;
                    void execute() {
                        try {
                            throw CmError.Chrg.E1654();
                        } catch (Exception caught) {
                        }
                    }
                }
                """);

        assertEquals(MATCH, match(unit));
    }

    @Test
    void duplicateExceptionAcrossSourceRootsStaysUnknown() throws Exception {
        writeSource(generatedSources, "example/error/BusinessException.java", """
                package example.error;
                public class BusinessException extends Error {}
                """);

        assertEquals(UNKNOWN, match("CmError.Chrg.E1654()", "Exception"));
    }

    @Test
    void duplicateExceptionAncestorAcrossSourceRootsStaysUnknown() throws Exception {
        writeSource(generatedSources, "example/error/BaseBusinessException.java", """
                package example.error;
                public class BaseBusinessException extends Error {}
                """);

        assertEquals(UNKNOWN, match("CmError.Chrg.E1654()", "Exception"));
    }

    @Test
    void unrelatedDuplicateTypeDoesNotInvalidateKnownMatch() throws Exception {
        writeSource(businessSources, "other/Duplicate.java", "package other; public class Duplicate {}");
        writeSource(generatedSources, "other/Duplicate.java", "package other; public class Duplicate {}");

        assertEquals(MATCH, match("CmError.Chrg.E1654()", "Exception"));
    }

    @Test
    void repeatedSameSourceRootDoesNotMakeTypesAmbiguous() throws Exception {
        Path operation = writeSource(businessSources, "example/app/Operation.java", """
                package example.app;
                import example.errors.CmError;
                class Operation {
                    void execute() {
                        try {
                            throw CmError.Chrg.E1654();
                        } catch (Exception caught) {
                        }
                    }
                }
                """);
        JavaParser parser = ErrorCodeExceptionTypeResolver.createParser(
                List.of(businessSources, generatedSources, businessSources));

        assertEquals(MATCH, match(parser.parse(operation).getResult().orElseThrow()));
    }

    @Test
    void symlinkToSamePhysicalSourceDoesNotMakeTypesAmbiguous() throws Exception {
        Path alias = Files.createSymbolicLink(directory.resolve("business-link"), businessSources);
        Path operation = writeSource(businessSources, "example/app/Operation.java", """
                package example.app;
                import example.errors.CmError;
                class Operation {
                    void execute() {
                        try {
                            throw CmError.Chrg.E1654();
                        } catch (Exception caught) {
                        }
                    }
                }
                """);
        JavaParser parser = ErrorCodeExceptionTypeResolver.createParser(
                List.of(businessSources, generatedSources, alias));

        assertEquals(MATCH, match(parser.parse(operation).getResult().orElseThrow()));
    }

    private ErrorCodeExceptionTypeResolver.Match match(String expression, String catchType) throws Exception {
        return match("import example.errors.CmError;", expression, catchType);
    }

    private ErrorCodeExceptionTypeResolver.Match match(String imports, String expression,
                                                       String catchType) throws Exception {
        return match(parse("""
                package example.app;
                %s
                class Operation {
                    void execute() {
                        try {
                            throw %s;
                        } catch (%s caught) {
                        }
                    }
                }
                """.formatted(imports, expression, catchType)));
    }

    private ErrorCodeExceptionTypeResolver.Match match(CompilationUnit unit) {
        TryStmt tryStatement = unit.findFirst(TryStmt.class).orElseThrow();
        ThrowStmt statement = tryStatement.getTryBlock().findFirst(ThrowStmt.class).orElseThrow();
        CatchClause clause = tryStatement.getCatchClauses().get(0);
        return new ErrorCodeExceptionTypeResolver().match(statement, clause);
    }

    private CompilationUnit parse(String source) throws Exception {
        Path operation = writeSource(businessSources, "example/app/Operation.java", source);
        JavaParser parser = ErrorCodeExceptionTypeResolver.createParser(List.of(businessSources, generatedSources));
        return parser.parse(operation).getResult().orElseThrow();
    }

    private void writeFactoryReturning(String returnType, String returnExpression) throws Exception {
        writeSource(generatedSources, "example/errors/CmError.java", """
                package example.errors;
                public class CmError {
                    public static class Chrg {
                        public static %s E1654() {
                            return %s;
                        }
                    }
                }
                """.formatted(returnType, returnExpression));
    }

    private void writeAmbiguousHolder() throws Exception {
        writeSource(generatedSources, "example/app/Holder.java", """
                package example.app;
                class Holder {
                    BadFactory codes;
                    BadFactory factory() {
                        return null;
                    }
                }
                class BadFactory {
                    Error E1654() {
                        return new Error();
                    }
                }
                """);
    }

    private Path writeSource(Path root, String relativePath, String source) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }
}
