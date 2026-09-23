package com.benxin.llm.core.sandbox;

/** 沙箱配置错误（例如围栏根目录不存在）。 */
public class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}