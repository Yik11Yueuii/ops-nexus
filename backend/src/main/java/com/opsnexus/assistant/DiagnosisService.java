package com.opsnexus.assistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsnexus.knowledge.KnowledgeException;
import com.opsnexus.resilience.AiProviderException;
import com.opsnexus.security.PromptTrustBoundary;
import java.sql.Timestamp;import java.time.Instant;import java.util.*;import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.support.GeneratedKeyHolder;import org.springframework.stereotype.Service;
@Service public class DiagnosisService{
 private final JdbcTemplate db;private final AssistantService assistant;private final DeepSeekChat chat;private final ObjectMapper json;private final PromptTrustBoundary trustBoundary;private final DiagnosisToolRegistry toolRegistry;
 public DiagnosisService(JdbcTemplate db,AssistantService assistant,DeepSeekChat chat,ObjectMapper json,PromptTrustBoundary trustBoundary,DiagnosisToolRegistry toolRegistry){this.db=db;this.assistant=assistant;this.chat=chat;this.json=json;this.trustBoundary=trustBoundary;this.toolRegistry=toolRegistry;}
 public List<Map<String,Object>> services(){return db.queryForList("SELECT service_name,display_name,current_version,runtime_status FROM service_catalog ORDER BY service_name");}
 public Map<String,Object> diagnose(long user,boolean admin,long kb,String service,String symptom,String context,Consumer<String> delta){
 if(service==null||service.isBlank()||symptom==null||symptom.isBlank())throw new KnowledgeException(400,"INVALID_INPUT","请选择服务并填写故障现象");
  toolRegistry.requireKnownService(service);trustBoundary.rejectDirectDisclosure(symptom+"\n"+Objects.requireNonNullElse(context,""));
  String safeSymptom=trustBoundary.redactSecrets(symptom),safeContext=trustBoundary.redactSecrets(Objects.requireNonNullElse(context,""));
  var citations=assistant.retrieve(kb,service+" "+safeSymptom+" "+safeContext);
  if(citations.isEmpty())throw new KnowledgeException(422,"INSUFFICIENT_EVIDENCE","没有找到已发布的排障依据，请先补充并发布相关 SOP");
  String requestId=UUID.randomUUID().toString();var contexts=new ArrayList<PromptTrustBoundary.UntrustedContext>();contexts.add(new PromptTrustBoundary.UntrustedContext("diagnosis_user_input","服务："+service+"\n现象："+safeSymptom+"\n上下文："+safeContext));contexts.add(new PromptTrustBoundary.UntrustedContext("diagnosis_citations",write(citations)));
  int calls=0;var summaries=new ArrayList<Map<String,Object>>();DeepSeekChat.ToolDecision decision;try{decision=chat.decideTools(trustBoundary.diagnosisToolSystemPolicy(),List.copyOf(contexts),"依据诊断输入和证据选择所需只读工具；不需要工具时直接给出安全诊断。",toolRegistry.specifications());while(!decision.toolCalls().isEmpty()){if(calls+decision.toolCalls().size()>3)throw new KnowledgeException(429,"TOOL_CALL_LIMIT_EXCEEDED","单次诊断最多允许 3 次工具调用");for(var request:decision.toolCalls()){var result=toolRegistry.execute(user,admin,requestId,request);calls++;summaries.add(Map.of("toolName",result.toolName(),"success",true,"truncated",result.truncated(),"summary",result.summary()));contexts.add(new PromptTrustBoundary.UntrustedContext("tool_result_"+result.toolName(),result.safePayload()));}decision=chat.decideTools(trustBoundary.diagnosisToolSystemPolicy(),List.copyOf(contexts),"基于工具结果继续请求必要工具；没有更多工具需要时给出最终安全诊断。",toolRegistry.specifications());}}catch(AiProviderException e){throw new KnowledgeException(503,e.errorCode(),e.getMessage());}
  var out=new StringBuilder();try{if(calls==0){out.append(decision.content());delta.accept(decision.content());}else chat.stream(trustBoundary.diagnosisSystemPolicy(),List.of(),List.copyOf(contexts),"根据诊断输入、文档证据和标记为不可信的工具结果，给出最终安全诊断。",p->{out.append(p);delta.accept(p);});}catch(AiProviderException e){throw new KnowledgeException(503,e.errorCode(),e.getMessage());}
  var snapshot=new LinkedHashMap<String,Object>();snapshot.put("diagnosisRequestId",requestId);snapshot.put("toolCalls",summaries);snapshot.put("citationCount",citations.size());String safeSnapshot=trustBoundary.redactSecrets(write(snapshot));
  var key=new GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement("INSERT INTO diagnosis_record(user_id,kb_id,service_name,symptom,error_context,result_content,evidence_snapshot) VALUES(?,?,?,?,?,?,?)",new String[]{"id"});p.setLong(1,user);p.setLong(2,kb);p.setString(3,service);p.setString(4,safeSymptom);p.setString(5,safeContext);p.setString(6,out.toString());p.setString(7,safeSnapshot);return p;},key);
  return Map.of("diagnosisId",Objects.requireNonNull(key.getKey()).longValue(),"citations",citations,"toolEvidence",summaries,"status","OPEN");
 }
 public List<Map<String,Object>> history(long user){return db.queryForList("SELECT id,service_name,symptom,status,created_at FROM diagnosis_record WHERE user_id=? ORDER BY created_at DESC LIMIT 20",user);}
 public void resolve(long user,long id){if(db.update("UPDATE diagnosis_record SET status='RESOLVED',updated_at=? WHERE id=? AND user_id=?",Timestamp.from(Instant.now()),id,user)==0)throw new KnowledgeException(404,"NOT_FOUND","诊断记录不存在");}
 private String write(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
}
