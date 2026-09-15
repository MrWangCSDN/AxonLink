package com.axonlink.ai.replay.dto;

/** 乐观锁定位对象：配置记录 id 与 version。 */
public record ReplayConfigVersionedId(long id, Integer version) {
}
