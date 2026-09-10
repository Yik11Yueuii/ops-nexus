package com.opsnexus.resilience;

public class AiProviderException extends RuntimeException {
    private final AiProvider provider;
    private final AiProviderFailure failure;

    public AiProviderException(AiProvider provider, AiProviderFailure failure, Throwable cause) {
        super(failure.safeMessage(), cause);
        this.provider = provider;
        this.failure = failure;
    }

    public AiProviderException(AiProvider provider, AiProviderFailure failure) {
        this(provider, failure, null);
    }

    public AiProvider provider() { return provider; }
    public AiProviderFailure failure() { return failure; }
    public String errorCode() { return failure.errorCode(); }
}
