package com.opsnexus.bootstrap;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
@Component
public class DemoData implements CommandLineRunner {
 private final JdbcTemplate db; private final PasswordEncoder passwords;
 @Value("${ops.admin-password}") private String admin;
 @Value("${ops.user-password}") private String user;
 public DemoData(JdbcTemplate db,PasswordEncoder passwords){this.db=db;this.passwords=passwords;}
 public void run(String... args) {
  add("admin","星云管理员","ADMIN",admin); add("user","星云工程师","USER",user);
 for(String name:new String[]{"order-service","user-service","payment-service","api-gateway"})
   if(db.queryForObject("SELECT COUNT(*) FROM service_catalog WHERE service_name=?",Integer.class,name)==0)
    db.update("INSERT INTO service_catalog(service_name,display_name,owner_name,current_version,runtime_status) VALUES(?,?,?,?,?)",name,name,"星云技术团队","1.0.0","DEMO");
  if(db.queryForObject("SELECT COUNT(*) FROM release_record",Integer.class)==0){
   db.update("INSERT INTO release_record(service_name,version_no,environment,status,released_at,summary) VALUES('order-service','2.3.1','PROD','SUCCESS',?,'连接池上限调整为 16，先灰度后全量')",ago(2));
   db.update("INSERT INTO release_record(service_name,version_no,environment,status,released_at,summary) VALUES('order-service','2.3.0','PROD','ROLLED_BACK',?,'连接池等待超时升高后回滚')",ago(12));
  }
  if(db.queryForObject("SELECT COUNT(*) FROM incident_record",Integer.class)==0){
   db.update("INSERT INTO incident_record(service_name,symptom,root_cause,resolution,status,occurred_at,resolved_at) VALUES('order-service','Redis 获取连接超时，接口延迟升高','慢查询占用连接且连接未及时释放','止损限流，确认连接指标，修复连接释放后灰度发布','RESOLVED',?,?)",ago(10),ago(10));
   db.update("INSERT INTO incident_record(service_name,symptom,root_cause,resolution,status,occurred_at,resolved_at) VALUES('order-service','发布后连接池 active 持续达到上限','实例容量与流量不匹配','回滚版本并扩容实例，复核连接池参数','RESOLVED',?,?)",ago(25),ago(25));
  }
 }
 private void add(String name,String display,String role,String password) {
  if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?",Integer.class,name)==0)
   db.update("INSERT INTO app_user(username,password_hash,display_name,role) VALUES(?,?,?,?)",name,passwords.encode(password),display,role);
 }
 private Timestamp ago(long days){return Timestamp.from(Instant.now().minus(days,ChronoUnit.DAYS));}
}
