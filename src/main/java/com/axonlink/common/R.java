package com.axonlink.common;

import lombok.Data;

/**
 * 统一响应体
 */
@Data
public class R<T> {
    private int code;
    private String message;
    private T data;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> R<T> fail(String message) {
        R<T> r = new R<>();
        r.code = 500;
        r.message = message;
        return r;
    }
}
