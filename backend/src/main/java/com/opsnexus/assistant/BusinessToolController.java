package com.opsnexus.assistant;
import com.opsnexus.common.ApiResponse;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/assistant/business-query") public class BusinessToolController{
 private final BusinessToolService service;public BusinessToolController(BusinessToolService service){this.service=service;}public record Input(String question){}
 @PostMapping public ApiResponse<?> query(@RequestBody Input input,@AuthenticationPrincipal Jwt user){return ApiResponse.ok(service.query(Long.parseLong(user.getSubject()),input.question()));}
}
