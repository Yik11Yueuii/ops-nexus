package com.opsnexus.governance;
import com.opsnexus.common.ApiResponse;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.bind.annotation.*;
@RestController
public class HealthController {
 private final JdbcTemplate db; private final RedisConnectionFactory redis;private final AiGovernanceService aiGovernance;
 public HealthController(JdbcTemplate db,RedisConnectionFactory redis,AiGovernanceService aiGovernance){this.db=db;this.redis=redis;this.aiGovernance=aiGovernance;}
 @GetMapping("/api/health") public ApiResponse<?> health(){return ApiResponse.ok(Map.of("application","UP"));}
 @GetMapping("/api/admin/status") public ApiResponse<?> status(){
  String state="UNAVAILABLE";
  try(var connection=redis.getConnection()){ if("PONG".equals(connection.ping())) state="UP"; }catch(Exception ignored){}
  boolean embedding=System.getenv("DASHSCOPE_API_KEY")!=null, chat=System.getenv("DEEPSEEK_API_KEY")!=null;
  return ApiResponse.ok(Map.of("database",db.queryForObject("SELECT 1",Integer.class)==1?"UP":"DOWN","redis",state,"aiLimiter",aiGovernance.limiterBackend(),"embeddingConfigured",embedding,"chatConfigured",chat,"modelConfigured",embedding&&chat,"stage","功能冻结候选版"));
 }
}
