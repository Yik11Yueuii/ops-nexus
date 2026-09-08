package com.opsnexus.governance;
import com.opsnexus.common.ApiResponse;import java.util.Map;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/knowledge-gaps") @PreAuthorize("hasRole('ADMIN')") public class KnowledgeGapController{
 private final KnowledgeGapService service;public KnowledgeGapController(KnowledgeGapService service){this.service=service;}
 @GetMapping public ApiResponse<?> list(){return ApiResponse.ok(Map.of("stats",service.stats(),"items",service.list()));}
 @PatchMapping("/{id}/resolve") public ApiResponse<?> resolve(@PathVariable long id){service.resolve(id);return ApiResponse.ok(Map.of("id",id));}
}
