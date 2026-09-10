package com.opsnexus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ops-test;DB_CLOSE_DELAY=-1","ops.seed-samples=false","ops.data-dir=./target/auth-test-runtime"})
@AutoConfigureMockMvc
class AuthIntegrationTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
 String login(String name,String password)throws Exception{
  var response=mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("username",name,"password",password)))).andExpect(status().isOk()).andReturn();
  return json.readTree(response.getResponse().getContentAsString()).at("/data/token").asText();
 }
 @Test void authenticationAndRoles()throws Exception {
  mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
  String user=login("user","OpsUser2026!");
  mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+user)).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("USER")).andExpect(jsonPath("$.data.password_hash").doesNotExist());
  mvc.perform(get("/api/admin/status").header("Authorization","Bearer "+user)).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/knowledge-gaps").header("Authorization","Bearer "+user)).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/observability/summary").header("Authorization","Bearer "+user)).andExpect(status().isForbidden());
  mvc.perform(get("/actuator/metrics").header("Authorization","Bearer "+user)).andExpect(status().isForbidden());
  String admin=login("admin","OpsAdmin2026!");
  mvc.perform(get("/api/admin/status").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.database").value("UP"));
  mvc.perform(get("/api/admin/observability/summary").header("Authorization","Bearer "+admin)).andExpect(status().isOk());
  mvc.perform(get("/actuator/metrics").header("Authorization","Bearer "+admin)).andExpect(status().isOk());
  mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+user+"tampered")).andExpect(status().isUnauthorized());
  assertTrue(db.queryForObject("SELECT password_hash FROM app_user WHERE username='admin'",String.class).startsWith("$2"));
 }
}
