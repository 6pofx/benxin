package com.benxin.llm.spring;

/** {@code @LlmAgent} 代理方法调用失败。 */
public class LlmAgentInvocationException extends RuntimeException {

    public LlmAgentInvocationException(String message) {
        super(message);
    }

    public LlmAgentInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}