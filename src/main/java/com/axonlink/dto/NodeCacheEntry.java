package com.axonlink.dto;

/**
 * ServiceNodeCache 缓存值对象。
 *
 * <p>同一 service_type_id.service_id（或 component_id.service_id）对应多行 field 明细，
 * 写入缓存前已按 key 聚合，field type / multi 以逗号分隔存储。
 */
public class NodeCacheEntry {

    // ── 来自明细表（service_detail / component_detail） ──
    private String serviceName;
    private String serviceLongname;
    private String interfaceInputFieldTypes;
    private String interfaceInputFieldMultis;
    private String interfaceOutputFieldTypes;
    private String interfaceOutputFieldMultis;

    // ── 来自主表（service / component） ──
    private String nodeKind;
    private String packagePath;
    private String domainKey;

    public NodeCacheEntry() {}

    private NodeCacheEntry(Builder b) {
        this.serviceName               = b.serviceName;
        this.serviceLongname           = b.serviceLongname;
        this.interfaceInputFieldTypes  = b.interfaceInputFieldTypes;
        this.interfaceInputFieldMultis = b.interfaceInputFieldMultis;
        this.interfaceOutputFieldTypes = b.interfaceOutputFieldTypes;
        this.interfaceOutputFieldMultis= b.interfaceOutputFieldMultis;
        this.nodeKind                  = b.nodeKind;
        this.packagePath               = b.packagePath;
        this.domainKey                 = b.domainKey;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String serviceName, serviceLongname;
        private String interfaceInputFieldTypes, interfaceInputFieldMultis;
        private String interfaceOutputFieldTypes, interfaceOutputFieldMultis;
        private String nodeKind, packagePath, domainKey;

        public Builder serviceName(String v)                { this.serviceName = v; return this; }
        public Builder serviceLongname(String v)            { this.serviceLongname = v; return this; }
        public Builder interfaceInputFieldTypes(String v)   { this.interfaceInputFieldTypes = v; return this; }
        public Builder interfaceInputFieldMultis(String v)  { this.interfaceInputFieldMultis = v; return this; }
        public Builder interfaceOutputFieldTypes(String v)  { this.interfaceOutputFieldTypes = v; return this; }
        public Builder interfaceOutputFieldMultis(String v) { this.interfaceOutputFieldMultis = v; return this; }
        public Builder nodeKind(String v)                   { this.nodeKind = v; return this; }
        public Builder packagePath(String v)                { this.packagePath = v; return this; }
        public Builder domainKey(String v)                  { this.domainKey = v; return this; }
        public NodeCacheEntry build()                       { return new NodeCacheEntry(this); }
    }

    public String getServiceName()                { return serviceName; }
    public void   setServiceName(String v)        { this.serviceName = v; }
    public String getServiceLongname()            { return serviceLongname; }
    public void   setServiceLongname(String v)    { this.serviceLongname = v; }
    public String getInterfaceInputFieldTypes()   { return interfaceInputFieldTypes; }
    public void   setInterfaceInputFieldTypes(String v) { this.interfaceInputFieldTypes = v; }
    public String getInterfaceInputFieldMultis()  { return interfaceInputFieldMultis; }
    public void   setInterfaceInputFieldMultis(String v){ this.interfaceInputFieldMultis = v; }
    public String getInterfaceOutputFieldTypes()  { return interfaceOutputFieldTypes; }
    public void   setInterfaceOutputFieldTypes(String v){ this.interfaceOutputFieldTypes = v; }
    public String getInterfaceOutputFieldMultis() { return interfaceOutputFieldMultis; }
    public void   setInterfaceOutputFieldMultis(String v){ this.interfaceOutputFieldMultis = v; }
    public String getNodeKind()                   { return nodeKind; }
    public void   setNodeKind(String v)           { this.nodeKind = v; }
    public String getPackagePath()                { return packagePath; }
    public void   setPackagePath(String v)        { this.packagePath = v; }
    public String getDomainKey()                  { return domainKey; }
    public void   setDomainKey(String v)          { this.domainKey = v; }
}
