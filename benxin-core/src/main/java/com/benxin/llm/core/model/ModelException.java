package com.benxin.llm.core.model;

/** 模型调用异常（含 HTTP 状态码与响应体片段，便于排障）。 */
public class ModelException extends RuntimeException {

    private final int statusCode;

    public ModelException(String message) {
        this(message, 0, null);
    }

    public ModelException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public ModelException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    /** 是否属于可重试的瞬时错误（限流 / 服务端错误 / 网络中断）。 */
    public boolean isRetryable() {
        if (statusCode == 429 || statusCode >= 500) {
            return true;
        }
        return statusCode == 0 && getCause() instanceof java.io.IOException;
    }
}