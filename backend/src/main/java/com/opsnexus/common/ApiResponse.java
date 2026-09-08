package com.opsnexus.common;
import java.util.UUID;
public record ApiResponse<T>(String code, String message, T data, String requestId) {
 public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>("OK", "成功", data, UUID.randomUUID().toString()); }
 public static ApiResponse<Void> error(String code, String message) { return new ApiResponse<>(code,message,null,UUID.randomUUID().toString()); }
}

