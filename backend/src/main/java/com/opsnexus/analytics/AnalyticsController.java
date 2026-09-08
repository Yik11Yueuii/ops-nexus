package com.opsnexus.analytics;
import com.opsnexus.common.ApiResponse;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/analytics") public class AnalyticsController{
 private final AnalyticsService service;public AnalyticsController(AnalyticsService service){this.service=service;}public record Input(String question){}
 @PostMapping("/query")public ApiResponse<?> query(@RequestBody Input input,@AuthenticationPrincipal Jwt jwt){return ApiResponse.ok(service.query(Long.parseLong(jwt.getSubject()),input.question()));}
 @GetMapping("/audits")public ApiResponse<?> audits(){return ApiResponse.ok(service.audits());}
}
