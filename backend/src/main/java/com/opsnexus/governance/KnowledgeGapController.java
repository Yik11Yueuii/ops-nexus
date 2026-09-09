package com.opsnexus.governance;

import com.opsnexus.common.ApiResponse;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/knowledge-gaps")
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeGapController {
    public record NoteInput(String note) {}
    public record ResolveInput(String resolutionType, String resolutionNote) {}
    private final KnowledgeGapService service;

    public KnowledgeGapController(KnowledgeGapService service) { this.service = service; }

    @GetMapping
    public ApiResponse<?> list() { return ApiResponse.ok(Map.of("stats", service.stats(), "items", service.list())); }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable long id) { return ApiResponse.ok(service.detail(id)); }

    @PatchMapping("/{id}/processing")
    public ApiResponse<?> processing(@PathVariable long id, @RequestBody NoteInput input, @AuthenticationPrincipal Jwt user) {
        service.startProcessing(id, userId(user), input == null ? null : input.note());
        return ApiResponse.ok(Map.of("id", id, "status", "PROCESSING"));
    }

    @PostMapping("/{id}/revalidate")
    public ApiResponse<?> revalidate(@PathVariable long id, @RequestBody(required = false) NoteInput input, @AuthenticationPrincipal Jwt user) {
        return ApiResponse.ok(service.revalidate(id, userId(user), input == null ? null : input.note()));
    }

    @PatchMapping("/{id}/resolve")
    public ApiResponse<?> resolve(@PathVariable long id, @RequestBody ResolveInput input, @AuthenticationPrincipal Jwt user) {
        service.resolve(id, userId(user), input == null ? null : input.resolutionType(), input == null ? null : input.resolutionNote());
        return ApiResponse.ok(Map.of("id", id, "status", "RESOLVED"));
    }

    private long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}