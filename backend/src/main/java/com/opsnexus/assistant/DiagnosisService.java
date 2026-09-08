package com.opsnexus.assistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsnexus.knowledge.KnowledgeException;
import java.sql.Timestamp;import java.time.Instant;import java.util.*;import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.support.GeneratedKeyHolder;import org.springframework.stereotype.Service;
@Service public class DiagnosisService{
 private final JdbcTemplate db;private final AssistantService assistant;private final DeepSeekChat chat;private final ObjectMapper json;
 public DiagnosisService(JdbcTemplate db,AssistantService assistant,DeepSeekChat chat,ObjectMapper json){this.db=db;this.assistant=assistant;this.chat=chat;this.json=json;}
 public List<Map<String,Object>> services(){return db.queryForList("SELECT service_name,display_name,current_version,runtime_status FROM service_catalog ORDER BY service_name");}
 public Map<String,Object> diagnose(long user,long kb,String service,String symptom,String context,Consumer<String> delta){
  if(service==null||service.isBlank()||symptom==null||symptom.isBlank())throw new KnowledgeException(400,"INVALID_INPUT","请选择服务并填写故障现象");
  var serviceRows=db.queryForList("SELECT service_name,display_name,owner_name,current_version,runtime_status FROM service_catalog WHERE service_name=?",service);if(serviceRows.isEmpty())throw new KnowledgeException(404,"NOT_FOUND","服务不存在");
  var releases=db.queryForList("SELECT version_no,environment,status,released_at,summary FROM release_record WHERE service_name=? ORDER BY released_at DESC LIMIT 3",service);
  var incidents=db.queryForList("SELECT symptom,root_cause,resolution,status,occurred_at FROM incident_record WHERE service_name=? ORDER BY occurred_at DESC LIMIT 3",service);
  var citations=assistant.retrieve(kb,service+" "+symptom+" "+Objects.requireNonNullElse(context,""));
  if(citations.isEmpty())throw new KnowledgeException(422,"INSUFFICIENT_EVIDENCE","没有找到已发布的排障依据，请先补充并发布相关 SOP");
  var snapshot=new LinkedHashMap<String,Object>();snapshot.put("queriedAt",Instant.now().toString());snapshot.put("service",serviceRows.getFirst());snapshot.put("recentReleases",releases);snapshot.put("similarIncidents",incidents);snapshot.put("citations",citations);
  String prompt="""
      你是企业故障辅助诊断专家。根据只读工具事实和文档证据分析，不得声称已执行命令。严格输出：
      ## 初步判断
      ## 排查清单（按优先级编号）
      ## 风险与止损
      ## 待验证假设
      ## 建议记录
      工具事实可以直接陈述；推理必须标为待验证，危险操作先提示审批或回滚条件。
      """+"工具快照："+write(snapshot)+"\n文档证据编号沿用 citations 顺序。";
  var out=new StringBuilder();chat.stream(prompt,"服务："+service+"\n现象："+symptom+"\n上下文："+Objects.requireNonNullElse(context,"未提供"),p->{out.append(p);delta.accept(p);});
  var key=new GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement("INSERT INTO diagnosis_record(user_id,kb_id,service_name,symptom,error_context,result_content,evidence_snapshot) VALUES(?,?,?,?,?,?,?)",new String[]{"id"});p.setLong(1,user);p.setLong(2,kb);p.setString(3,service);p.setString(4,symptom);p.setString(5,context);p.setString(6,out.toString());p.setString(7,write(snapshot));return p;},key);
  return Map.of("diagnosisId",Objects.requireNonNull(key.getKey()).longValue(),"citations",citations,"toolEvidence",snapshot,"status","OPEN");
 }
 public List<Map<String,Object>> history(long user){return db.queryForList("SELECT id,service_name,symptom,status,created_at FROM diagnosis_record WHERE user_id=? ORDER BY created_at DESC LIMIT 20",user);}
 public void resolve(long user,long id){if(db.update("UPDATE diagnosis_record SET status='RESOLVED',updated_at=? WHERE id=? AND user_id=?",Timestamp.from(Instant.now()),id,user)==0)throw new KnowledgeException(404,"NOT_FOUND","诊断记录不存在");}
 private String write(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
}
