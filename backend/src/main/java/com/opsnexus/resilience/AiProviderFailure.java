package com.opsnexus.resilience;

public enum AiProviderFailure {
    TIMEOUT(true, true, "AI_PROVIDER_TIMEOUT", "AI 服务响应超时，请稍后重试"),
    UNAVAILABLE(true, true, "AI_PROVIDER_UNAVAILABLE", "AI 服务暂时不可用，请稍后重试"),
    RATE_LIMITED(true, true, "AI_PROVIDER_RATE_LIMITED", "AI 服务繁忙，请稍后重试"),
    AUTHENTICATION(false, false, "AI_PROVIDER_AUTH_FAILED", "AI 服务认证失败，请联系管理员检查配置"),
    BAD_REQUEST(false, false, "AI_PROVIDER_REJECTED", "AI 服务拒绝了请求"),
    MALFORMED_RESPONSE(false, false, "AI_PROVIDER_MALFORMED_RESPONSE", "AI 服务返回格式异常"),
    CIRCUIT_OPEN(false, false, "AI_PROVIDER_UNAVAILABLE", "AI 服务暂时不可用，请稍后重试"),
    CONFIGURATION(false, false, "AI_PROVIDER_NOT_CONFIGURED", "AI 服务尚未配置"),
    CANCELLED(false, false, "AI_PROVIDER_CANCELLED", "AI 请求已取消");

    private final boolean retryable;
    private final boolean circuitFailure;
    private final String errorCode;
    private final String safeMessage;

    AiProviderFailure(boolean retryable, boolean circuitFailure, String errorCode, String safeMessage) {
        this.retryable = retryable;
        this.circuitFailure = circuitFailure;
        this.errorCode = errorCode;
        this.safeMessage = safeMessage;
    }

    public boolean retryable() { return retryable; }
    public boolean circuitFailure() { return circuitFailure; }
    public String errorCode() { return errorCode; }
    public String safeMessage() { return safeMessage; }
}
