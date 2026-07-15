package com.axonlink.ai.daoindex.errorcode.scan;

import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** ThrowStmtVisitor 全谱匹配测试（仅依赖 JavaParser 内存 parse，无 Spring/DB）。 */
class ThrowStmtVisitorTest {

    /** 解析源码字符串，跑 visitor，返回命中的错误码明细。 */
    private List<ErrorCodeThrow> scan(String src) {
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        CompilationUnit cu = new JavaParser(cfg).parse(src).getResult().orElseThrow();
        ThrowStmtVisitor v = new ThrowStmtVisitor(cu, "/abs/Test.java", "loan-bcc", new AtomicLong(0));
        v.visit(cu, null);
        return v.getResults();
    }

    private List<String> codes(List<ErrorCodeThrow> rs) {
        return rs.stream().map(ErrorCodeThrow::getErrorCode).collect(Collectors.toList());
    }

    @Test
    void threeSegWithCategory() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ throw CmError.Brch.E0003(\"a\"); } }");
        assertEquals(1, rs.size());
        assertEquals("E0003", rs.get(0).getErrorCode());
        assertEquals("CmError.Brch", rs.get(0).getErrorScope());
        assertEquals("com.x.A", rs.get(0).getClassFqn());
        assertEquals("m", rs.get(0).getMethodName());
    }

    @Test
    void twoSegNoCategory() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ throw CmError.E0005(\"a\"); } }");
        assertEquals("E0005", rs.get(0).getErrorCode());
        assertEquals("CmError", rs.get(0).getErrorScope());
    }

    @Test
    void rReturnCode() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ throw CmError.R0003(\"a\"); } }");
        assertEquals(List.of("R0003"), codes(rs));
    }

    @Test
    void anyUppercaseLetterPrefixMatched() {
        // 内网实测：首字母是各域命名空间，不止 E/R——P(Prnt)/A(Acfp,Agnc)/B(Bkdf)/D(Dbsp) 均为真实错误码
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){"
                + " throw StError.Prnt.P0321();"
                + " } void n(){ throw StError.Acfp.A0002(acnum); }"
                + " void o(){ throw StError.Bkdf.B0216(); }"
                + " void p(){ throw StError.Dbsp.D0102(rsp.getMsg()); }"
                + " void q(){ throw StError.Remt.R1029(); } }");
        assertEquals(List.of("P0321", "A0002", "B0216", "D0102", "R1029"), codes(rs));
        assertEquals("StError.Prnt", rs.get(0).getErrorScope());
    }

    @Test
    void staticImportedCategoryScopeMatched() {
        // 静态导入嵌套类后 scope 只剩一段（实测形态：throw Prnt.P0333();）
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ throw Prnt.P0333(); } }");
        assertEquals(List.of("P0333"), codes(rs));
        assertEquals("Prnt", rs.get(0).getErrorScope());
    }

    @Test
    void nonErrorCodeMethodNamesFiltered() {
        // 结构像但方法名不符：两个字母 / 小写字母 / 位数不符（2 位、5 位）都不算错误码
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ throw X.AB123(); }"
                + " void n(){ throw X.e0003(); }"
                + " void o(){ throw X.P12(); }"
                + " void p(){ throw X.P00001(); } }");
        assertTrue(rs.isEmpty());
    }

    @Test
    void throwTextStripsTrailingLineComment() {
        // 源码：throw ...E0085(); // 说明 —— 注释在 throw 后面。
        // 旧实现 n.toString() 会把行尾注释当前置注释，落库变成 "// 说明 throw ...;"（语义全变）。
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ throw TaError.Trac.E0085(); // 查询无对应记录\n } }");
        assertEquals(1, rs.size());
        String text = rs.get(0).getThrowText();
        assertEquals("throw TaError.Trac.E0085();", text);
        assertFalse(text.contains("查询"), "throw_text 不应混进注释");
        assertFalse(text.startsWith("//"), "注释不应跑到 throw 前面");
    }

    @Test
    void throwTextStripsLeadingLineComment() {
        // 注释在 throw 前一行也应剥除（前置注释）
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ // 前置说明\n throw TaError.Trac.E0086(); } }");
        assertEquals("throw TaError.Trac.E0086();", rs.get(0).getThrowText());
    }

    @Test
    void multiLineThrow() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ throw CmError.Brch.E0003(\n  \"line1\",\n  \"line2\"); } }");
        assertEquals(1, rs.size());
        assertTrue(rs.get(0).getThrowText().contains("line1"));
        assertTrue(rs.get(0).getThrowText().contains("line2"));
    }

    @Test
    void nestedCallArg() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ throw CmError.E0003(a.b().c()); } }");
        assertEquals(1, rs.size());
        assertTrue(rs.get(0).getThrowText().contains("a.b().c()"));
    }

    @Test
    void newExceptionFiltered() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ throw new RuntimeException(\"x\"); } }");
        assertTrue(rs.isEmpty());
    }

    @Test
    void stringLiteralThrowNotMatched() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ String s = \"throw CmError.E0003(x)\"; } }");
        assertTrue(rs.isEmpty());
    }

    @Test
    void commentThrowNotMatched() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ /* throw CmError.E0003(x) */ int i = 1; } }");
        assertTrue(rs.isEmpty());
    }

    @Test
    void lambdaThrowAttributedToMethod() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; import java.util.*; class A { void m(){ "
                + "Runnable r = () -> { throw CmError.E0003(\"x\"); }; } }");
        assertEquals(1, rs.size());
        assertEquals("m", rs.get(0).getMethodName());
        assertEquals("com.x.A", rs.get(0).getClassFqn());
    }

    @Test
    void assignedThenThrowFiltered() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ RuntimeException e = new RuntimeException(); throw e; } }");
        assertTrue(rs.isEmpty());
    }

    @Test
    void enumBodyThrowRecorded() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; enum E { A; void f(){ throw CmError.E0001(\"x\"); } }");
        assertEquals(1, rs.size());
        assertEquals("E0001", rs.get(0).getErrorCode());
        assertEquals("com.x.E", rs.get(0).getClassFqn());
        assertEquals("f", rs.get(0).getMethodName());
    }

    @Test
    void recordBodyThrowRecorded() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; record R(int v) { void f(){ throw CmError.E0009(\"x\"); } }");
        assertEquals(1, rs.size());
        assertEquals("E0009", rs.get(0).getErrorCode());
        assertEquals("com.x.R", rs.get(0).getClassFqn());
        assertEquals("f", rs.get(0).getMethodName());
    }

    @Test
    void innerClassUsesInnermostSimpleName() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class Outer { class Inner { void g(){ throw CmError.E0007(\"x\"); } } }");
        assertEquals(1, rs.size());
        assertEquals("com.x.Inner", rs.get(0).getClassFqn());
        assertEquals("g", rs.get(0).getMethodName());
    }

    @Test
    void lineNoCaptured() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x;\nclass A {\n  void m(){\n    throw CmError.E0003(\"x\");\n  }\n}");
        assertEquals(Integer.valueOf(4), rs.get(0).getLineNo());
    }

    @Test
    void threeDigitAndFourDigitCode() {
        List<ErrorCodeThrow> rs = scan(
                "package com.x; class A { void m(){ throw CmError.E001(\"a\"); }"
                + " void n(){ throw CmError.E0003(\"b\"); } }");
        assertEquals(List.of("E001", "E0003"), codes(rs));
    }
}
