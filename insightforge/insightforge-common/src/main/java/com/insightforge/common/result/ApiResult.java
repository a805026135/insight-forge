package com.insightforge.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一 API 返回体（教学骨架，Phase 0）。
 * code=0 表示成功；非 0 表示业务/错误码。
 */
@Data
public class ApiResult<T> implements Serializable {

    private int code;
    private String message;
    private T data;

    public static <T> ApiResult<T> ok(T data) {
        ApiResult<T> r = new ApiResult<>();
        r.setCode(0);
        r.setMessage("ok");
        r.setData(data);
        return r;
    }

    public static <T> ApiResult<T> fail(int code, String message) {
        ApiResult<T> r = new ApiResult<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}
