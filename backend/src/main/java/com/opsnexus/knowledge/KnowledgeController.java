package com.opsnexus.knowledge;

import com.opsnexus.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class KnowledgeController {
    private final KnowledgeService service;private final VersionCompareService compare;
    public KnowledgeController(KnowledgeService service,VersionCompareService compare){this.service=service;this.compare=compare;}
    private boolean admin(Jwt user){return "ADMIN".equals(user.getClaimAsString("role"));}
    public record BaseInput(@NotBlank @Size(max=100) String name,@Size(max=500) String description){}
    public record CompareInput(long oldVersionId,long newVersionId){}
    @GetMapping("/knowledge-bases")
    public ApiResponse<?> bases(@AuthenticationPrincipal Jwt user){return ApiResponse.ok(service.bases(admin(user)));}
    @PostMapping("/knowledge-bases") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> create(@Valid @RequestBody BaseInput input,@AuthenticationPrincipal Jwt user){
        return ApiResponse.ok(Map.of("id",service.createBase(input.name(),input.description(),Long.parseLong(user.getSubject()))));
    }
    @GetMapping("/admin/knowledge/capabilities")
    public ApiResponse<?> capabilities(){return ApiResponse.ok(service.capabilities());}
    @GetMapping("/knowledge-bases/{id}/documents")
    public ApiResponse<?> documents(@PathVariable long id,@AuthenticationPrincipal Jwt user){return ApiResponse.ok(service.documents(id,admin(user)));}
    @PostMapping("/knowledge-bases/{id}/documents") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> upload(@PathVariable long id,@RequestParam MultipartFile file,@RequestParam String title,
            @RequestParam String versionNo,@RequestParam(required=false) Long documentId,@AuthenticationPrincipal Jwt user)throws Exception{
        return ApiResponse.ok(Map.of("versionId",service.upload(id,documentId,title,versionNo,file.getBytes(),file.getOriginalFilename(),Long.parseLong(user.getSubject()))));
    }
    @GetMapping("/documents/{id}/versions")
    public ApiResponse<?> versions(@PathVariable long id,@AuthenticationPrincipal Jwt user){return ApiResponse.ok(service.versions(id,admin(user)));}
    @GetMapping("/document-versions/{id}/chunks")
    public ApiResponse<?> preview(@PathVariable long id,@AuthenticationPrincipal Jwt user){return ApiResponse.ok(service.preview(id,admin(user)));}
    @GetMapping("/document-versions/{id}/download")
    public ResponseEntity<?> download(@PathVariable long id,@AuthenticationPrincipal Jwt user){
        var path=service.download(id,admin(user));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(path.getFileName().toString()).build().toString())
            .body(new FileSystemResource(path));
    }
    @PostMapping("/document-versions/{id}/process") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> process(@PathVariable long id){service.process(id);return ApiResponse.ok(Map.of("accepted",true));}
    @PostMapping("/document-versions/{id}/publish") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> publish(@PathVariable long id){service.publish(id);return ApiResponse.ok(null);}
    @PostMapping("/document-versions/{id}/archive") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> archive(@PathVariable long id){service.archive(id);return ApiResponse.ok(null);}
    @DeleteMapping("/document-versions/{id}") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> delete(@PathVariable long id){service.delete(id);return ApiResponse.ok(null);}
    @PostMapping("/admin/document-version-compare") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<?> compare(@RequestBody CompareInput input){return ApiResponse.ok(compare.compare(input.oldVersionId(),input.newVersionId()));}
}
