package com.opsnexus.common;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
@RestControllerAdvice
public class Errors {
 @ExceptionHandler(com.opsnexus.knowledge.KnowledgeException.class)
 public org.springframework.http.ResponseEntity<?> knowledge(com.opsnexus.knowledge.KnowledgeException e) {
  return org.springframework.http.ResponseEntity.status(e.status).body(ApiResponse.error(e.code,e.getMessage()));
 }
 @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
 @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
 public ApiResponse<Void> size(Exception e) { return ApiResponse.error("FILE_SIZE","上传文件不能超过 20 MB"); }
 @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
 @ResponseStatus(HttpStatus.BAD_REQUEST)
 public ApiResponse<Void> invalid(Exception e) { return ApiResponse.error("INVALID_INPUT","请检查输入内容"); }
}
