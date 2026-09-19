package com.mall.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体：code / message / data 三段式。
 * 错误码约定：200 成功，400 参数错误，401 未登录，403 无权限，500 系统异常，业务错误码 10xxx 起。
 */
@Data
public class Result<T> implements Serializable {

    public static final int CODE_SUCCESS = 200;
    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_UNAUTHORIZED = 401;
    public static final int CODE_FORBIDDEN = 403;
    public static final int CODE_ERROR = 500;
    /** 通用业务错误码，业务码自 10xxx 起 */
    public static final int CODE_BIZ_ERROR = 10000;

    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.setCode(CODE_SUCCESS);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> fail(String message) {
        return fail(CODE_BIZ_ERROR, message);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
