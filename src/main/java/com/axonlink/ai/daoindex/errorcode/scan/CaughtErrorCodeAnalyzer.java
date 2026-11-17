package com.axonlink.ai.daoindex.errorcode.scan;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.Set;

/** Filters only throw sites whose original exception is provably handled in this execution body. */
final class CaughtErrorCodeAnalyzer {

    private enum Handling {
        HANDLED, PROPAGATES, UNKNOWN
    }

    private enum Completion {
        NORMAL, OVERRIDES, UNKNOWN
    }

    private static final Set<String> MESSAGE_READS =
            Set.of("getMessage", "getLocalizedMessage", "toString");

    private final ErrorCodeExceptionTypeResolver types = new ErrorCodeExceptionTypeResolver();

    boolean isHandled(ThrowStmt statement) {
        return handlingBefore(statement, null) == Handling.HANDLED;
    }

    private Handling handlingBefore(ThrowStmt statement, Node boundary) {
        Node child = statement;
        Node parent = child.getParentNode().orElse(null);
        while (parent != null && parent != boundary) {
            if (isExecutionBoundary(parent, child)) {
                return Handling.PROPAGATES;
            }
            if (parent instanceof TryStmt enclosing) {
                Handling caught = Handling.PROPAGATES;
                if (child == enclosing.getTryBlock()) {
                    caught = handlingByCatch(statement, enclosing);
                    if (caught == Handling.UNKNOWN) {
                        // A catch may have retained the object for later execution outside finally.
                        return Handling.UNKNOWN;
                    }
                }
                // A throw in finally is outside this try's own handlers and finalizer.
                boolean inFinally = enclosing.getFinallyBlock().orElse(null) == child;
                if (!inFinally && enclosing.getFinallyBlock()
                        .map(block -> completion(block) == Completion.OVERRIDES).orElse(false)) {
                    return Handling.HANDLED;
                }
                if (caught == Handling.HANDLED) {
                    return Handling.HANDLED;
                }
            }
            child = parent;
            parent = parent.getParentNode().orElse(null);
        }
        return Handling.PROPAGATES;
    }

    private Handling handlingByCatch(ThrowStmt statement, TryStmt enclosing) {
        for (CatchClause clause : enclosing.getCatchClauses()) {
            ErrorCodeExceptionTypeResolver.Match match = types.match(statement, clause);
            if (match == ErrorCodeExceptionTypeResolver.Match.UNKNOWN) {
                // An earlier uncertain catch may rethrow; a later broad catch cannot supersede it.
                return Handling.UNKNOWN;
            }
            if (match == ErrorCodeExceptionTypeResolver.Match.MATCH) {
                return handlerOutcome(clause);
            }
        }
        return Handling.PROPAGATES;
    }

    private Handling handlerOutcome(CatchClause clause) {
        String parameter = clause.getParameter().getNameAsString();
        boolean rethrown = false;
        for (NameExpr reference : clause.getBody().findAll(NameExpr.class)) {
            if (!parameter.equals(reference.getNameAsString())) {
                continue;
            }
            if (inDeferredBody(reference, clause)) {
                return Handling.UNKNOWN;
            }
            Expression value = reference;
            Node parent = value.getParentNode().orElse(null);
            while (parent instanceof EnclosedExpr || parent instanceof CastExpr) {
                value = (Expression) parent;
                parent = parent.getParentNode().orElse(null);
            }
            if (parent instanceof ThrowStmt rethrow && rethrow.getExpression() == value) {
                Handling nested = handlingBefore(rethrow, clause);
                if (nested == Handling.UNKNOWN) {
                    return Handling.UNKNOWN;
                }
                rethrown |= nested == Handling.PROPAGATES;
            } else if (!isMessageRead(value, parent)) {
                // Includes aliases, wrapping the caught object, and passing it to unknown helpers.
                return Handling.UNKNOWN;
            }
        }
        return rethrown ? Handling.PROPAGATES : Handling.HANDLED;
    }

    private boolean isMessageRead(Expression value, Node parent) {
        return parent instanceof MethodCallExpr call
                && call.getScope().orElse(null) == value
                && call.getArguments().isEmpty()
                && MESSAGE_READS.contains(call.getNameAsString());
    }

    private boolean inDeferredBody(Node node, CatchClause clause) {
        Node child = node;
        Node parent = child.getParentNode().orElse(null);
        while (parent != null && parent != clause) {
            if (isExecutionBoundary(parent, child)) {
                return true;
            }
            child = parent;
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean isExecutionBoundary(Node parent, Node child) {
        if (parent instanceof LambdaExpr || parent instanceof CallableDeclaration<?>
                || parent instanceof TypeDeclaration<?> || parent instanceof InitializerDeclaration) {
            return true;
        }
        return parent instanceof ObjectCreationExpr creation && child instanceof BodyDeclaration<?>
                && creation.getAnonymousClassBody().map(body -> body.contains(child)).orElse(false);
    }

    /** Unknown exits keep the original exception, including variable rethrows and complex control flow. */
    private Completion completion(Statement statement) {
        if (statement.isReturnStmt()) {
            return Completion.OVERRIDES;
        }
        if (statement instanceof ThrowStmt throwing) {
            Expression expression = throwing.getExpression();
            boolean replacement = expression.isObjectCreationExpr()
                    || expression.isMethodCallExpr()
                    && expression.asMethodCallExpr().getNameAsString().matches("[A-Z]\\d{3,4}");
            return replacement ? Completion.OVERRIDES : Completion.UNKNOWN;
        }
        if (statement instanceof BlockStmt block) {
            for (Statement child : block.getStatements()) {
                Completion result = completion(child);
                if (result != Completion.NORMAL) {
                    return result;
                }
            }
            return Completion.NORMAL;
        }
        if (statement instanceof IfStmt conditional) {
            Completion whenTrue = completion(conditional.getThenStmt());
            Completion whenFalse = conditional.getElseStmt()
                    .map(this::completion).orElse(Completion.NORMAL);
            if (whenTrue == Completion.UNKNOWN || whenFalse == Completion.UNKNOWN) {
                return Completion.UNKNOWN;
            }
            return whenTrue == Completion.OVERRIDES && whenFalse == Completion.OVERRIDES
                    ? Completion.OVERRIDES : Completion.NORMAL;
        }
        if (statement.isExpressionStmt() || statement.isEmptyStmt()) {
            return Completion.NORMAL;
        }
        return Completion.UNKNOWN;
    }
}
