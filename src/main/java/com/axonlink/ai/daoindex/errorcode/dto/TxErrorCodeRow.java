package com.axonlink.ai.daoindex.errorcode.dto;

/**
 * 交易维度物化错误码行（对应 dii_tx_error_code 一行）。
 * 字段声明顺序 == dii_tx_error_code 写库列顺序（见 V24），勿改顺序。
 */
public class TxErrorCodeRow {

    private final String  txId;
    private final String  txName;
    private final String  domainKey;
    private final String  errorCode;
    private final String  errorScope;
    private final String  throwText;
    private final String  classFqn;
    private final String  methodName;
    private final String  filePath;
    private final Integer lineNo;
    private final String  moduleName;
    private final String  componentCode;  // null = 工具方法
    private final String  componentName;
    private final String  matchStatus;    // MATCHED / UNMATCHED
    private final Long    throwSeq;

    public TxErrorCodeRow(String txId, String txName, String domainKey, String errorCode,
                          String errorScope, String throwText, String classFqn, String methodName,
                          String filePath, Integer lineNo, String moduleName,
                          String componentCode, String componentName, String matchStatus, Long throwSeq) {
        this.txId = txId;
        this.txName = txName;
        this.domainKey = domainKey;
        this.errorCode = errorCode;
        this.errorScope = errorScope;
        this.throwText = throwText;
        this.classFqn = classFqn;
        this.methodName = methodName;
        this.filePath = filePath;
        this.lineNo = lineNo;
        this.moduleName = moduleName;
        this.componentCode = componentCode;
        this.componentName = componentName;
        this.matchStatus = matchStatus;
        this.throwSeq = throwSeq;
    }

    public String getTxId()          { return txId; }
    public String getTxName()        { return txName; }
    public String getDomainKey()     { return domainKey; }
    public String getErrorCode()     { return errorCode; }
    public String getErrorScope()    { return errorScope; }
    public String getThrowText()     { return throwText; }
    public String getClassFqn()      { return classFqn; }
    public String getMethodName()    { return methodName; }
    public String getFilePath()      { return filePath; }
    public Integer getLineNo()       { return lineNo; }
    public String getModuleName()    { return moduleName; }
    public String getComponentCode() { return componentCode; }
    public String getComponentName() { return componentName; }
    public String getMatchStatus()   { return matchStatus; }
    public Long getThrowSeq()        { return throwSeq; }
}
