-- ─────────────────────────────────────────────────────────────────────────────
-- 方法调用关系网（Call Graph）三张表
-- MySQL 8.x
-- ─────────────────────────────────────────────────────────────────────────────

-- ① 方法节点：每个业务方法一行
CREATE TABLE IF NOT EXISTS cg_method_node (
    id           BIGINT        NOT NULL COMMENT '主键',
    signature    VARCHAR(800)  NOT NULL COMMENT '全局唯一签名 FQN#method(ParamA,ParamB)',
    module       VARCHAR(120)  COMMENT '所属模块，如 dept-parent/dept-bcs',
    class_fqn    VARCHAR(500)  NOT NULL COMMENT '全限定类名',
    class_name   VARCHAR(200)  NOT NULL COMMENT '简单类名',
    class_type   VARCHAR(20)   COMMENT '类型：CLASS/INTERFACE/ENUM',
    layer        VARCHAR(20)   COMMENT '架构层：APS/BCS/DAO/POJO/OTHER',
    method_name  VARCHAR(200)  NOT NULL COMMENT '方法名',
    param_types  VARCHAR(500)  COMMENT '参数简单类名，逗号分隔',
    return_type  VARCHAR(200)  COMMENT '返回类型简单名',
    modifiers    VARCHAR(80)   COMMENT '修饰符，如 public,static',
    file_path    VARCHAR(1000) COMMENT '源文件绝对路径',
    line_no      INT           COMMENT '方法声明行号',
    create_time  DATETIME      NOT NULL COMMENT '首次扫描入库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_signature (signature(400)),
    KEY idx_class_fqn   (class_fqn(200)),
    KEY idx_class_name  (class_name),
    KEY idx_method_name (method_name),
    KEY idx_layer       (layer)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='方法节点';

-- ② 调用边：caller → callee
--    call_type 枚举：
--      SYS_UTIL    SysUtil.getInstance/getRemoteInstance(X.class).method()
--      STATIC      ClassName.staticMethod()
--      INSTANCE    obj.method()（包括 this.xxx()）
--      INTERFACE   通过接口引用调用（不含 SysUtil）
CREATE TABLE IF NOT EXISTS cg_call_edge (
    id          BIGINT       NOT NULL COMMENT '主键',
    caller_sig  VARCHAR(800) NOT NULL COMMENT '调用方签名',
    callee_sig  VARCHAR(800) NOT NULL COMMENT '被调用方签名',
    call_type   VARCHAR(20)  NOT NULL COMMENT 'SYS_UTIL/STATIC/INSTANCE/INTERFACE',
    line_no     INT          COMMENT '调用所在行号',
    create_time DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_caller (caller_sig(300)),
    KEY idx_callee (callee_sig(300))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='方法调用边';

-- ③ 接口→实现映射（阶段1收集，供边解析时使用）
CREATE TABLE IF NOT EXISTS cg_interface_impl (
    interface_fqn VARCHAR(500) NOT NULL COMMENT '接口全限定名',
    impl_fqn      VARCHAR(500) NOT NULL COMMENT '实现类全限定名',
    create_time   DATETIME     NOT NULL,
    PRIMARY KEY (interface_fqn(200), impl_fqn(200))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口实现映射';
