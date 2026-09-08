package com.opsnexus.knowledge;

public class KnowledgeException extends RuntimeException {
    public final int status;
    public final String code;
    public KnowledgeException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
