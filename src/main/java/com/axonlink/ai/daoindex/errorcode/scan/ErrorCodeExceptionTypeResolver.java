package com.axonlink.ai.daoindex.errorcode.scan;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves catch compatibility conservatively against this scan's source roots. */
final class ErrorCodeExceptionTypeResolver {

    enum Match {
        MATCH,
        NO_MATCH,
        UNKNOWN
    }

    static JavaParser createParser(List<Path> sourceRoots) {
        AmbiguityAwareTypeSolver solver = new AmbiguityAwareTypeSolver();
        for (Path sourceRoot : sourceRoots) {
            ParserConfiguration sourceConfiguration = new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            solver.addSource(new JavaParserTypeSolver(sourceRoot, sourceConfiguration));
        }
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setSymbolResolver(new SourceSymbolSolver(solver));
        return new JavaParser(configuration);
    }

    static void releaseCaches() {
        // JavaParserFacade's static weak-key map retains its solver through the facade value.
        synchronized (JavaParserFacade.class) {
            JavaParserFacade.clearInstances();
        }
    }

    Match match(ThrowStmt statement, CatchClause clause) {
        ResolvedType thrownType;
        SourceSymbolSolver sourceSolver = null;
        try {
            if (statement.getSymbolResolver() instanceof SourceSymbolSolver configuredSolver) {
                sourceSolver = configuredSolver;
            }
            if (sourceSolver != null && (sourceSolver.hasAmbiguousContext(statement)
                    || statement.getExpression().isMethodCallExpr()
                    && sourceSolver.hasAmbiguousFactory(statement.getExpression().asMethodCallExpr()))) {
                // A type declared in the same compilation unit can bypass the combined solver.
                thrownType = null;
            } else {
                thrownType = statement.getExpression().calculateResolvedType();
            }
        } catch (RuntimeException unresolvedType) {
            thrownType = null;
        }

        Type catchType = clause.getParameter().getType();
        if (!catchType.isUnionType()) {
            return matchType(thrownType, catchType, sourceSolver);
        }
        Match combined = Match.NO_MATCH;
        for (Type alternative : catchType.asUnionType().getElements()) {
            Match result = matchType(thrownType, alternative, sourceSolver);
            if (result == Match.MATCH) {
                return Match.MATCH;
            }
            if (result == Match.UNKNOWN) {
                combined = Match.UNKNOWN;
            }
        }
        return combined;
    }

    private Match matchType(ResolvedType thrownType, Type catchType, SourceSymbolSolver sourceSolver) {
        try {
            ResolvedType resolvedCatchType = catchType.resolve();
            if (!resolvedCatchType.isReferenceType()) {
                return Match.UNKNOWN;
            }
            ResolvedReferenceType caught = resolvedCatchType.asReferenceType();
            if (sourceSolver != null && sourceSolver.types.isAmbiguous(caught.getQualifiedName())) {
                return Match.UNKNOWN;
            }
            if ("java.lang.Throwable".equals(caught.getQualifiedName())) {
                return Match.MATCH;
            }
            if (thrownType == null || !thrownType.isReferenceType()) {
                return Match.UNKNOWN;
            }
            ResolvedReferenceType thrown = thrownType.asReferenceType();
            if (sourceSolver != null && (sourceSolver.hasAmbiguousHierarchy(caught)
                    || sourceSolver.hasAmbiguousHierarchy(thrown))) {
                return Match.UNKNOWN;
            }
            if (caught.isAssignableBy(thrown)) {
                return Match.MATCH;
            }
            if (thrown.isAssignableBy(caught)) {
                // A factory's declared base type can return an instance of this narrower catch.
                return Match.UNKNOWN;
            }
            if (caught.getTypeDeclaration().isEmpty() || thrown.getTypeDeclaration().isEmpty()
                    || !caught.getTypeDeclaration().get().isClass()
                    || !thrown.getTypeDeclaration().get().isClass()) {
                return Match.UNKNOWN;
            }
            // An unresolved parent must not turn a possible relationship into NO_MATCH.
            caught.getTypeDeclaration().get().getAllAncestors();
            thrown.getTypeDeclaration().get().getAllAncestors();
            return Match.NO_MATCH;
        } catch (RuntimeException unresolvedType) {
            return Match.UNKNOWN;
        }
    }

    private static final class SourceSymbolSolver extends JavaSymbolSolver {

        private final AmbiguityAwareTypeSolver types;
        private final Map<Node, Boolean> contextAmbiguities = new IdentityHashMap<>();

        private SourceSymbolSolver(AmbiguityAwareTypeSolver types) {
            super(types);
            this.types = types;
        }

        private boolean hasAmbiguousContext(ThrowStmt statement) {
            Node current = statement;
            while (current != null) {
                if (current instanceof TypeDeclaration<?> || current instanceof CallableDeclaration<?>
                        || current instanceof LambdaExpr || current instanceof InitializerDeclaration) {
                    Boolean ambiguous = contextAmbiguities.get(current);
                    if (ambiguous == null) {
                        ambiguous = current instanceof TypeDeclaration<?> type
                                ? hasAmbiguousLexicalType(type) : hasAmbiguousReferences(current);
                        contextAmbiguities.put(current, ambiguous);
                    }
                    if (ambiguous) {
                        return true;
                    }
                }
                current = current.getParentNode().orElse(null);
            }
            return false;
        }

        private boolean hasAmbiguousLexicalType(TypeDeclaration<?> declaration) {
            try {
                if (hasAmbiguousHierarchy(toTypeDeclaration(declaration))) {
                    return true;
                }
            } catch (RuntimeException unresolvedContext) {
                // Auxiliary context only blocks a match when ambiguity is positively established.
            }
            for (FieldDeclaration field : declaration.getFields()) {
                if (hasAmbiguousReferences(field)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasAmbiguousReferences(Node context) {
            for (ClassOrInterfaceType type : context.findAll(ClassOrInterfaceType.class)) {
                try {
                    if (hasAmbiguousHierarchy(type.resolve().asReferenceType())) {
                        return true;
                    }
                } catch (RuntimeException unresolvedContext) {
                    // An unrelated missing dependency does not invalidate a resolved factory.
                }
            }
            for (MethodCallExpr call : context.findAll(MethodCallExpr.class)) {
                if (call.getScope().isPresent() && hasConfirmedAmbiguousScope(call.getScope().get(), call)) {
                    return true;
                }
            }
            for (FieldAccessExpr field : context.findAll(FieldAccessExpr.class)) {
                if (hasConfirmedAmbiguousScope(field.getScope(), field)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasConfirmedAmbiguousScope(Expression scope, Node context) {
            try {
                return hasAmbiguousHierarchy(resolveScopeDeclaration(scope, context));
            } catch (RuntimeException unresolvedContext) {
                return false;
            }
        }

        private boolean hasAmbiguousFactory(MethodCallExpr factory) {
            if (hasAmbiguousHierarchy(factory.resolve().declaringType())) {
                return true;
            }
            if (factory.getScope().isEmpty()) {
                return false;
            }
            return hasAmbiguousHierarchy(resolveScopeDeclaration(factory.getScope().get(), factory));
        }

        private ResolvedReferenceTypeDeclaration resolveScopeDeclaration(Expression scope, Node context) {
            ResolvedType scopeType;
            try {
                scopeType = scope.calculateResolvedType();
            } catch (RuntimeException unresolvedValue) {
                // Static class scopes use the same lexical/import resolution as SymbolSolver.
                SymbolReference<ResolvedTypeDeclaration> scopeDeclaration = JavaParserFactory
                        .getContext(context, types).solveType(scope.toString());
                return scopeDeclaration.getCorrespondingDeclaration().asReferenceType();
            }
            return scopeType.asReferenceType().getTypeDeclaration().orElseThrow();
        }

        private boolean hasAmbiguousHierarchy(ResolvedReferenceType type) {
            return hasAmbiguousHierarchy(type.getTypeDeclaration().orElseThrow());
        }

        private boolean hasAmbiguousHierarchy(ResolvedReferenceTypeDeclaration type) {
            if (types.isAmbiguous(type.getQualifiedName())) {
                return true;
            }
            for (ResolvedReferenceType ancestor : type.getAllAncestors()) {
                if (types.isAmbiguous(ancestor.getQualifiedName())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class AmbiguityAwareTypeSolver extends CombinedTypeSolver {

        private final List<JavaParserTypeSolver> sources = new ArrayList<>();
        private final Map<String, Boolean> ambiguities = new HashMap<>();

        private AmbiguityAwareTypeSolver() {
            super(new ReflectionTypeSolver());
        }

        private void addSource(JavaParserTypeSolver source) {
            sources.add(source);
            add(source);
        }

        @Override
        public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
            if (isAmbiguous(name)) {
                return SymbolReference.unsolved();
            }
            return super.tryToSolveType(name);
        }

        private boolean isAmbiguous(String name) {
            Boolean cached = ambiguities.get(name);
            if (cached != null) {
                return cached;
            }
            Set<Path> files = new HashSet<>();
            for (JavaParserTypeSolver source : sources) {
                SymbolReference<ResolvedReferenceTypeDeclaration> candidate = source.tryToSolveType(name);
                if (!candidate.isSolved()) {
                    continue;
                }
                files.add(sourceFile(candidate.getCorrespondingDeclaration()));
                if (files.size() > 1) {
                    ambiguities.put(name, true);
                    return true;
                }
            }
            ambiguities.put(name, false);
            return false;
        }

        private Path sourceFile(ResolvedReferenceTypeDeclaration declaration) {
            Path file = declaration.toAst().flatMap(Node::findCompilationUnit)
                    .flatMap(CompilationUnit::getStorage)
                    .map(CompilationUnit.Storage::getPath).orElseThrow();
            try {
                return file.toRealPath();
            } catch (IOException unreadableSource) {
                throw new UncheckedIOException(unreadableSource);
            }
        }
    }
}
