package com.axonlink.ai.tool;

import com.axonlink.service.ServiceNodeCache;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据补充工具。
 */
@Component
public class MetadataTool {

    private final ServiceNodeCache serviceNodeCache;

    public MetadataTool(ServiceNodeCache serviceNodeCache) {
        this.serviceNodeCache = serviceNodeCache;
    }

    public Map<String, Object> buildMetadata(String txId, Map<String, Object> chain) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("txId", txId);
        metadata.put("cacheStats", serviceNodeCache.getStats());
        metadata.put("serviceCount", asListSize(chain.get("service")));
        metadata.put("componentCount", asListSize(chain.get("component")));
        metadata.put("tableCount", asListSize(chain.get("data")));
        metadata.put("layerCount", intValue(chain.get("layers")));
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private int asListSize(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return 0;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
