package com.damai.common;

import lombok.Data;
import java.io.Serializable;

@Data
public class ApiResponse<T> implements Serializable {

    private Integer code;
    private String message;
    private T data;

    private ApiResponse() {}

    public static <T> ApiResponse<T> error(String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = -100;
        r.message = message;
        return r;
    }

    public static <T> ApiResponse<T> ok() {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 0;
        return r;
    }

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 0;
        r.data = data;
        return r;
    }
}