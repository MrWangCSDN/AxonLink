package com.axonlink.ai.daoindex.errorcode.scan;

import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 访问 throw 语句，识别错误码工厂调用 throw &lt;Scope&gt;.E####/R####(...)。
 * classFqn = pkg + "." + 栈顶（最近一层）具名类简名，与 Neo4jGraphBuilder:556 对齐。
 */
public class ThrowStmtVisitor extends VoidVisitorAdapter<Void> {

    /** 错误码方法名权威正则：E/R + 3~4 位数字。 */
    private static final Pattern CODE = Pattern.compile("^[ER]\\d{3,4}$");

    private final String pkg;
    private final String filePath;
    private final String moduleName;
    private final AtomicLong seq;
    private final Deque<String> classStack = new ArrayDeque<>();
    private final Deque<String> methodStack = new ArrayDeque<>();
    private final List<ErrorCodeThrow> results = new ArrayList<>();

    public ThrowStmtVisitor(CompilationUnit cu, String filePath, String moduleName, AtomicLong seq) {
        this.pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        this.filePath = filePath;
        this.moduleName = moduleName;
        this.seq = seq;
    }

    public List<ErrorCodeThrow> getResults() {
        return results;
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration n, Void arg) {
        classStack.push(n.getNameAsString());
        super.visit(n, arg);          // 递归进入嵌套类
        classStack.pop();
    }

    @Override
    public void visit(EnumDeclaration n, Void arg) {
        classStack.push(n.getNameAsString());  // enum 入栈（错误类常为枚举/常量类）
        super.visit(n, arg);
        classStack.pop();
    }

    @Override
    public void visit(RecordDeclaration n, Void arg) {
        classStack.push(n.getNameAsString());  // record 入栈（Java 16+ 记录类型，方法体内也可 throw 错误码）
        super.visit(n, arg);
        classStack.pop();
    }

    @Override
    public void visit(AnnotationDeclaration n, Void arg) {
        classStack.push(n.getNameAsString());  // 注解声明入栈，避免内部默认方法 throw 时栈顶为 null
        super.visit(n, arg);
        classStack.pop();
    }

    @Override
    public void visit(MethodDeclaration n, Void arg) {
        methodStack.push(n.getNameAsString());
        super.visit(n, arg);
        methodStack.pop();
    }

    @Override
    public void visit(ThrowStmt n, Void arg) {
        Expression expr = n.getExpression();
        // 只接受 MethodCallExpr：throw new XxxException / throw e 均不命中
        if (!expr.isMethodCallExpr()) {
            super.visit(n, arg);
            return;
        }
        MethodCallExpr mce = expr.asMethodCallExpr();
        String methodName = mce.getNameAsString();
        if (!CODE.matcher(methodName).matches()) {
            super.visit(n, arg);
            return;
        }
        if (mce.getScope().isEmpty()) {       // 错误码方法必有限定符 Scope
            super.visit(n, arg);
            return;
        }
        String scope = mce.getScope().get().toString();
        // 栈顶 = 最近一层具名类；兜底为 "<unknown>"，杜绝未处理声明类型导致 "<pkg>.null" 脏 FQN
        String enclosingSimple = classStack.peek() != null ? classStack.peek() : "<unknown>";
        String classFqn = (pkg == null || pkg.isEmpty())
                ? enclosingSimple
                : pkg + "." + enclosingSimple;
        String method = methodStack.peek() != null ? methodStack.peek() : "<unknown>";
        Integer lineNo = n.getBegin().map(p -> p.line).orElse(null);
        results.add(new ErrorCodeThrow(
                methodName, scope, cleanThrowText(n), classFqn, method,
                filePath, lineNo, moduleName, null, null, seq.incrementAndGet()));
        super.visit(n, arg);
    }

    /**
     * 取 throw 语句文本，但<b>剥除注释</b>。
     *
     * <p>直接 {@code ThrowStmt.toString()} 会带上挂在该语句上的注释，且 JavaParser 把
     * <b>行尾 {@code //} 注释当作前置注释打印</b>——于是源码里 {@code throw ...E0085(); // 说明}
     * 落库后变成 {@code // 说明 throw ...E0085();}，注释跑到 throw 前面，语义完全不同。
     * 故克隆节点后移除其关联注释 + 所有内部注释，再 toString（多行 throw 的结构保留）。
     */
    private static String cleanThrowText(ThrowStmt n) {
        ThrowStmt c = n.clone();
        c.removeComment();                                              // 节点自身关联注释（含被误当前置的行尾注释）
        new ArrayList<>(c.getAllContainedComments()).forEach(Comment::remove);  // 内部/孤儿注释
        return c.toString().trim();
    }
}
