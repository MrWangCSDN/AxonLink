package com.axonlink.chain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class ChainPage {
    private final int offset, size;
    private final String key;
    private int available;

    ChainPage(ChainGraph graph, List<String> query, int size, String cursor, String snapshotAt) {
        if (size < 1 || size > 200) throw new ChainResult.Problem("INVALID_ARGUMENT","pageSize 范围为 1 至 200");
        if (snapshotAt != null && !snapshotAt.equals(graph.snapshotAt)) throw stale();
        this.size = size;
        this.key = graph.revision + "." + fingerprint(query) + ".";
        if (cursor == null) offset = 0;
        else {
            if (!cursor.startsWith(key)) throw stale();
            try { offset = Integer.parseInt(cursor.substring(key.length())); }
            catch (NumberFormatException e) { throw stale(); }
            if (offset < 0) throw stale();
        }
    }
    <T> List<T> slice(List<T> list) {
        available = Math.max(available,list.size());
        int start = Math.min(offset,list.size());
        return list.subList(start, start + Math.min(list.size() - start,size));
    }
    boolean includes(int index) {
        available = Math.max(available,index + 1);
        return index >= offset && index - offset < size;
    }
    String next() {
        if (offset > 0 && offset >= available) throw stale();
        return available - offset > size ? key + (offset + size) : null;
    }

    private static String fingerprint(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static ChainResult.Problem stale() {
        return new ChainResult.Problem("STALE_RESULT","游标或快照已失效，请使用原查询重新读取第一页");
    }
}
