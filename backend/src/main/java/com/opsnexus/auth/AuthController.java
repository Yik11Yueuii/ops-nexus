package com.opsnexus.auth;
import com.opsnexus.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/auth")
public class AuthController {
 private final JdbcTemplate db; private final PasswordEncoder passwords; private final JwtEncoder jwt;
 public AuthController(JdbcTemplate db, PasswordEncoder passwords, JwtEncoder jwt) {this.db=db;this.passwords=passwords;this.jwt=jwt;}
 public record Login(@NotBlank @Size(max=50) String username,@NotBlank @Size(max=100) String password) {}
 @PostMapping("/login")
 public ResponseEntity<?> login(@Valid @RequestBody Login input) {
  var users=db.queryForList("SELECT * FROM app_user WHERE username=? AND status='ENABLED'",input.username());
  if(users.isEmpty() || !passwords.matches(input.password(),users.getFirst().get("PASSWORD_HASH").toString()))
   return ResponseEntity.status(401).body(ApiResponse.error("BAD_CREDENTIALS","用户名或密码错误"));
  var u=users.getFirst(); var now=Instant.now();
  var claims=JwtClaimsSet.builder().issuer("opsnexus").subject(u.get("ID").toString()).issuedAt(now).expiresAt(now.plusSeconds(7200)).claim("role",u.get("ROLE").toString()).build();
  var token=jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
  return ResponseEntity.ok(ApiResponse.ok(Map.of("token",token,"expiresIn",7200,"user",profile(u))));
 }
 @GetMapping("/me")
 public ResponseEntity<?> me(@AuthenticationPrincipal Jwt token) {
  var rows=db.queryForList("SELECT * FROM app_user WHERE id=? AND status='ENABLED'",Long.parseLong(token.getSubject()));
  return rows.isEmpty()?ResponseEntity.status(401).body(ApiResponse.error("UNAUTHORIZED","账号不可用")):ResponseEntity.ok(ApiResponse.ok(profile(rows.getFirst())));
 }
 private Map<String,Object> profile(Map<String,Object> u) {return Map.of("id",u.get("ID"),"username",u.get("USERNAME"),"displayName",u.get("DISPLAY_NAME"),"role",u.get("ROLE"));}
}

