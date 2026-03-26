package com.axonlink.dto;

/**
 * 链路节点 VO（聚合 flow_step + NodeCacheEntry）
 */
public class FlowChainNode {

    private int    step;
    private String nodeType;
    private String nodeName;
    private String nodeLongname;
    private String nodeKind;
    private String domainKey;
    private boolean crossDomain;
    private boolean ruleViolation;
    private String incorrectCalls;
    private String inputFieldTypes;
    private String inputFieldMultis;
    private String outputFieldTypes;
    private String outputFieldMultis;

    public int     getStep()             { return step; }
    public void    setStep(int v)        { this.step = v; }
    public String  getNodeType()         { return nodeType; }
    public void    setNodeType(String v) { this.nodeType = v; }
    public String  getNodeName()         { return nodeName; }
    public void    setNodeName(String v) { this.nodeName = v; }
    public String  getNodeLongname()           { return nodeLongname; }
    public void    setNodeLongname(String v)   { this.nodeLongname = v; }
    public String  getNodeKind()               { return nodeKind; }
    public void    setNodeKind(String v)       { this.nodeKind = v; }
    public String  getDomainKey()              { return domainKey; }
    public void    setDomainKey(String v)      { this.domainKey = v; }
    public boolean isCrossDomain()             { return crossDomain; }
    public void    setCrossDomain(boolean v)   { this.crossDomain = v; }
    public boolean isRuleViolation()           { return ruleViolation; }
    public void    setRuleViolation(boolean v) { this.ruleViolation = v; }
    public String  getIncorrectCalls()         { return incorrectCalls; }
    public void    setIncorrectCalls(String v) { this.incorrectCalls = v; }
    public String  getInputFieldTypes()        { return inputFieldTypes; }
    public void    setInputFieldTypes(String v){ this.inputFieldTypes = v; }
    public String  getInputFieldMultis()       { return inputFieldMultis; }
    public void    setInputFieldMultis(String v){ this.inputFieldMultis = v; }
    public String  getOutputFieldTypes()       { return outputFieldTypes; }
    public void    setOutputFieldTypes(String v){ this.outputFieldTypes = v; }
    public String  getOutputFieldMultis()      { return outputFieldMultis; }
    public void    setOutputFieldMultis(String v){ this.outputFieldMultis = v; }
}
