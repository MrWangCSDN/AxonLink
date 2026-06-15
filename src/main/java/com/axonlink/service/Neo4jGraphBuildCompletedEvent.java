package com.axonlink.service;

/**
 * Neo4j 图构建流水线（{@link Neo4jGraphBuilder#doBuild} 的 phase0 → … → done）
 * <b>成功</b>完成后发布的事件。
 *
 * <p>下游依赖「交易→方法」调用图的索引（如错误码交易维度物化
 * {@code dii_tx_error_code}）应监听本事件，在图就绪后再重建，
 * 避免与图构建（启动期 3~5 分钟、且开头会清空旧图）发生时序竞态。
 *
 * <p>触发来源：启动自动构建 / benchmark webhook 重建 / REST 手动构建，三者皆经
 * {@code doBuild} 收口，故监听本事件即可覆盖全部"图被重建"场景。
 */
public class Neo4jGraphBuildCompletedEvent {

    /** 本次构建的操作 ID；启动自动构建等无操作 ID 时为 {@code null}。 */
    private final String operationId;

    public Neo4jGraphBuildCompletedEvent(String operationId) {
        this.operationId = operationId;
    }

    public String getOperationId() {
        return operationId;
    }
}
