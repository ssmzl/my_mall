package com.mall.common;

import lombok.Getter;

/**
 * 业务异常，携带业务错误码（10xxx 起），由全局异常处理器转换为 Result。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(Result.CODE_BIZ_ERROR, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
