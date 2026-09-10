package com.opsnexus.auth;
import com.opsnexus.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.core.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
@Configuration
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class SecurityConfig {
 @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
 @Bean JwtEncoder encoder(@Value("${ops.jwt-secret}") String secret) {
  return new NimbusJwtEncoder(new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
 }
 @Bean JwtDecoder decoder(@Value("${ops.jwt-secret}") String secret) {
  var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
  decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("opsnexus"));
  return decoder;
 }
 @Bean SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper) throws Exception {
  var roles = new JwtGrantedAuthoritiesConverter(); roles.setAuthoritiesClaimName("role"); roles.setAuthorityPrefix("ROLE_");
  var converter = new JwtAuthenticationConverter(); converter.setJwtGrantedAuthoritiesConverter(roles);
  http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a -> a.requestMatchers("/api/auth/login","/api/health","/actuator/health/**","/actuator/info").permitAll().requestMatchers("/api/admin/**","/actuator/**").hasRole("ADMIN").anyRequest().authenticated())
   .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
    .authenticationEntryPoint((req,res,e) -> {res.setStatus(401);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getOutputStream(),ApiResponse.error("UNAUTHORIZED","请先登录"));}))
   .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> {res.setStatus(401);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getOutputStream(),ApiResponse.error("UNAUTHORIZED","请先登录"));})
    .accessDeniedHandler((req,res,ex) -> {res.setStatus(403);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getOutputStream(),ApiResponse.error("FORBIDDEN","需要管理员权限"));}));
  return http.build();
 }
}
