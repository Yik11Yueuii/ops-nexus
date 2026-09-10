package com.opsnexus.assistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsnexus.knowledge.KnowledgeException;
import com.opsnexus.resilience.AiProviderException;
import com.opsnexus.security.PromptTrustBoundary;
import java.sql.Timestamp;import java.time.Instant;import java.util.*;import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.support.GeneratedKeyHolder;import org.springframework.stereotype.Service;
@Service public class DiagnosisService{
 private final JdbcTemplate db;private final AssistantService assistant;private final DeepSeekChat chat;private final ObjectMapper json;private final PromptTrustBoundary trustBoundary;
 public DiagnosisService(JdbcTemplate db,AssistantService assistant,DeepSeekChat chat,ObjectMapper json,PromptTrustBoundary trustBoundary){this.db=db;this.assistant=assistant;this.chat=chat;this.json=json;this.trustBoundary=trustBoundary;}
 public List<Map<String,Object>> services(){return db.queryForList("SELECT service_name,display_name,current_version,runtime_status FROM service_catalog ORDER BY service_name");}
 public Map<String,Object> diagnose(long user,long kb,String service,String symptom,String context,Consumer<String> delta){
 if(service==null||service.isBlank()||symptom==null||symptom.isBlank())throw new KnowledgeException(400,"INVALID_INPUT","请选择服务并填写故障现象");
  trustBoundary.rejectDirectDisclosure(symptom+"\n"+Objects.requireNonNullElse(context,""));
  String safeSymptom=trustBoundary.redactSecrets(symptom),safeContext=trustBoundary.redactSecrets(Objects.requireNonNullElse(context,""));
  var serviceRows=db.queryForList("SELECT service_name,display_name,owner_name,current_version,runtime_status FROM service_catalog WHERE service_name=?",service);if(serviceRows.isEmpty())throw new KnowledgeException(404,"NOT_FOUND","服务不存在");
  var releases=db.queryForList("SELECT version_no,environment,status,released_at,summary FROM release_record WHERE service_name=? ORDER BY released_at DESC LIMIT 3",service);
  var incidents=db.queryForList("SELECT symptom,root_cause,resolution,status,occurred_at FROM incident_record WHERE service_name=? ORDER BY occurred_at DESC LIMIT 3",service);
  var citations=assistant.retrieve(kb,service+" "+safeSymptom+" "+safeContext);
  if(citations.isEmpty())throw new KnowledgeException(422,"INSUFFICIENT_EVIDENCE","没有找到已发布的排障依据，请先补充并发布相关 SOP");
  var snapshot=new LinkedHashMap<String,Object>();snapshot.put("queriedAt",Instant.now().toString());snapshot.put("service",serviceRows.getFirst());snapshot.put("recentReleases",releases);snapshot.put("similarIncidents",incidents);snapshot.put("citations",citations);
  String untrustedInput="服务："+service+"\n现象："+safeSymptom+"\n上下文："+safeContext+"\n只读工具与文档快照："+write(snapshot);
  var contexts=List.of(new PromptTrustBoundary.UntrustedContext("diagnosis_input_and_tool_results",untrustedInput));
  var out=new StringBuilder();try{chat.stream(trustBoundary.diagnosisSystemPolicy(),List.of(),contexts,"请基于这些数据给出当前故障的安全诊断。",p->{out.append(p);delta.accept(p);});}catch(AiProviderException e){throw new KnowledgeException(503,e.errorCode(),e.getMessage());}
  String safeSnapshot=trustBoundary.redactSecrets(write(snapshot));
  var key=new GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement("INSERT INTO diagnosis_record(user_id,kb_id,service_name,symptom,error_context,result_content,evidence_snapshot) VALUES(?,?,?,?,?,?,?)",new String[]{"id"});p.setLong(1,user);p.setLong(2,kb);p.setString(3,service);p.setString(4,safeSymptom);p.setString(5,safeContext);p.setString(6,out.toString());p.setString(7,safeSnapshot);return p;},key);
  return Map.of("diagnosisId",Objects.requireNonNull(key.getKey()).longValue(),"citations",citations,"toolEvidence",snapshot,"status","OPEN");
 }
 public List<Map<String,Object>> history(long user){return db.queryForList("SELECT id,service_name,symptom,status,created_at FROM diagnosis_record WHERE user_id=? ORDER BY created_at DESC LIMIT 20",user);}
 public void resolve(long user,long id){if(db.update("UPDATE diagnosis_record SET status='RESOLVED',updated_at=? WHERE id=? AND user_id=?",Timestamp.from(Instant.now()),id,user)==0)throw new KnowledgeException(404,"NOT_FOUND","诊断记录不存在");}
 private String write(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
}
