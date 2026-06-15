package com.axonlink.ai.daoindex.errorcode.dto;

/**
 * 一处 throw 错误码明细（对应 dii_error_code 一行）。
 * 字段声明顺序 == dii_error_code 写库列顺序（见 V24），勿改顺序。
 */
public class ErrorCodeThrow {

    private final String  errorCode;
    private final String  errorScope;
    private final String  throwText;
    private final String  classFqn;
    private final String  methodName;
    private final String  filePath;
    private final Integer lineNo;       // 解析失败为 null，不参与去重
    private final String  moduleName;
    private final String  innerClassName; // 预留，本期可空
    private final String  codeSignature;  // 预留，本期可空
    private final Long    throwSeq;        // 扫描内自增序号

    public ErrorCodeThrow(String errorCode, String errorScope, String throwText,
                          String classFqn, String methodName, String filePath,
                          Integer lineNo, String moduleName, String innerClassName,
                          String codeSignature, Long throwSeq) {
        this.errorCode = errorCode;
        this.errorScope = errorScope;
        this.throwText = throwText;
        this.classFqn = classFqn;
        this.methodName = methodName;
        this.filePath = filePath;
        this.lineNo = lineNo;
        this.moduleName = moduleName;
        this.innerClassName = innerClassName;
        this.codeSignature = codeSignature;
        this.throwSeq = throwSeq;
    }

    public String getErrorCode()      { return errorCode; }
    public String getErrorScope()     { return errorScope; }
    public String getThrowText()      { return throwText; }
    public String getClassFqn()       { return classFqn; }
    public String getMethodName()     { return methodName; }
    public String getFilePath()       { return filePath; }
    public Integer getLineNo()        { return lineNo; }
    public String getModuleName()     { return moduleName; }
    public String getInnerClassName() { return innerClassName; }
    public String getCodeSignature()  { return codeSignature; }
    public Long getThrowSeq()         { return throwSeq; }
}
