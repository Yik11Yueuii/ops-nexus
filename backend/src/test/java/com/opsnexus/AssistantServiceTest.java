package com.opsnexus;

import com.opsnexus.assistant.*;
import com.opsnexus.ingestion.VectorIndex;
import com.opsnexus.knowledge.VersionCompareService;
import com.opsnexus.governance.KnowledgeGapService;
import com.opsnexus.governance.AiGovernanceService;
import com.opsnexus.analytics.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:assistant-test;DB_CLOSE_DELAY=-1","spring.data.redis.port=1","spring.data.redis.connect-timeout=100ms","spring.data.redis.timeout=100ms","ops.seed-samples=false","ops.data-dir=./target/assistant-test-runtime"})
class AssistantServiceTest {
 @Autowired AssistantService service; @Autowired DiagnosisService diagnosis; @Autowired VersionCompareService versionCompare; @Autowired KnowledgeGapService gaps; @Autowired BusinessToolService businessTools; @Autowired AnalyticsService analytics; @Autowired SqlSafetyValidator sqlValidator; @Autowired AiGovernanceService aiGovernance; @Autowired JdbcTemplate db;
 @MockitoBean VectorIndex vectors; @MockitoBean DeepSeekChat chat;
 @BeforeEach void setup(){
  db.update("DELETE FROM ai_call_log");db.update("DELETE FROM sql_query_audit");db.update("DELETE FROM tool_call_audit");db.update("DELETE FROM gap_verification");db.update("DELETE FROM gap_occurrence");db.update("DELETE FROM knowledge_gap");db.update("DELETE FROM diagnosis_record");db.update("DELETE FROM incident_record");db.update("DELETE FROM release_record");
  db.update("DELETE FROM message_feedback");db.update("DELETE FROM message_citation");db.update("DELETE FROM chat_message");db.update("DELETE FROM conversation");
  db.update("DELETE FROM document_chunk_ref");db.update("UPDATE knowledge_document SET current_version_id=NULL");
  db.update("DELETE FROM document_version");db.update("DELETE FROM knowledge_document");db.update("DELETE FROM knowledge_base");
  db.update("INSERT INTO knowledge_base(id,name,created_by) VALUES(100,'测试知识库',1)");
  db.update("INSERT INTO knowledge_document(id,kb_id,title,current_version_id,created_by) VALUES(200,100,'部署手册',302,1)");
  db.update("INSERT INTO document_version(id,document_id,version_no,original_name,file_type,file_size,checksum,storage_path,process_status,publish_status) VALUES(301,200,'v1','old.md','MD',1,'a','a','READY','ARCHIVED')");
  db.update("INSERT INTO document_version(id,document_id,version_no,original_name,file_type,file_size,checksum,storage_path,process_status,publish_status) VALUES(302,200,'v2','new.md','MD',1,'b','b','READY','PUBLISHED')");
  db.update("INSERT INTO document_chunk_ref(version_id,vector_id,chunk_index,content) VALUES(301,'test-old',0,'连接池最大连接数为 8\n发布后直接全量')");
  db.update("INSERT INTO document_chunk_ref(version_id,vector_id,chunk_index,content) VALUES(302,'test-new',0,'连接池最大连接数为 16\n先灰度发布再全量')");
  when(vectors.configured()).thenReturn(true);when(chat.configured()).thenReturn(true);
  if(db.queryForObject("SELECT COUNT(*) FROM service_catalog WHERE service_name='order-service'",Integer.class)==0)db.update("INSERT INTO service_catalog(service_name,display_name,current_version,runtime_status) VALUES('order-service','订单服务','2.3.1','DEMO')");
 }
 @Test void sendsOnlyCurrentPublishedEvidenceAndPersistsCitation(){
  var old=Document.builder().id("old").text("旧版本机密片段").metadata("versionId",301L).score(.95).build();
  var current=Document.builder().id("new").text("当前版本要求先灰度发布").metadata("versionId",302L).score(.80).build();
  when(vectors.search(anyString(),eq(8))).thenReturn(List.of(old,current));
  doAnswer(inv->{String system=inv.getArgument(0);assertFalse(system.contains("旧版本机密片段"));assertTrue(system.contains("当前版本要求先灰度发布"));assertTrue(system.contains("分析建议"));assertTrue(system.contains("待验证假设"));java.util.function.Consumer<String> out=inv.getArgument(3);out.accept("应先灰度发布。");return null;}).when(chat).stream(anyString(),anyList(),anyString(),any());
  var streamed=new StringBuilder();
  var result=service.answer(1,null,100,"如何发布？",streamed::append);
  assertEquals("应先灰度发布。",streamed.toString());assertEquals(1,result.citations().size());
  assertEquals("v2",result.citations().getFirst().versionNo());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM message_citation WHERE message_id=?",Integer.class,result.messageId()));
  service.feedback(1,result.messageId(),"UP",null);assertEquals("UP",db.queryForObject("SELECT rating FROM message_feedback WHERE message_id=?",String.class,result.messageId()));
  assertEquals(2,((List<?>)service.conversation(1,result.conversationId()).get("messages")).size());
  assertThrows(com.opsnexus.knowledge.KnowledgeException.class,()->service.conversation(2,result.conversationId()));
  assertThrows(com.opsnexus.knowledge.KnowledgeException.class,()->service.feedback(2,result.messageId(),"DOWN",null));
 }
 @Test void refusesWithoutSendingToChatWhenNoValidEvidence(){
  when(vectors.search(anyString(),eq(8))).thenReturn(List.of(Document.builder().id("old").text("旧内容").metadata("versionId",301L).score(.99).build()));
  var out=new StringBuilder();var result=service.answer(1,null,100,"内部流程？",out::append);
  assertEquals("LOW",result.confidence());assertTrue(result.citations().isEmpty());verify(chat,never()).stream(anyString(),anyString(),any());
 }
 @Test void diagnosisCombinesToolsAndPersistsAuditableSnapshot(){
  db.update("INSERT INTO release_record(service_name,version_no,environment,status,released_at,summary) VALUES('order-service','2.3.1','PROD','SUCCESS',CURRENT_TIMESTAMP,'连接池参数变更')");
  db.update("INSERT INTO incident_record(service_name,symptom,root_cause,resolution,status,occurred_at) VALUES('order-service','连接超时','连接泄漏','修复释放逻辑','RESOLVED',CURRENT_TIMESTAMP)");
  when(vectors.search(anyString(),eq(8))).thenReturn(List.of(Document.builder().id("sop").text("先确认 active 与 max 指标，再检查慢查询").metadata("versionId",302L).score(.86).build()));
  doAnswer(inv->{String prompt=inv.getArgument(0);assertTrue(prompt.contains("2.3.1"));assertTrue(prompt.contains("连接池参数变更"));assertTrue(prompt.contains("连接泄漏"));assertTrue(prompt.contains("先确认 active"));java.util.function.Consumer<String> out=inv.getArgument(2);out.accept("## 初步判断\n待验证：连接可能未释放。");return null;}).when(chat).stream(anyString(),anyString(),any());
  var streamed=new StringBuilder();var result=diagnosis.diagnose(1,100,"order-service","Redis 连接超时","发布后出现",streamed::append);
  long id=((Number)result.get("diagnosisId")).longValue();assertTrue(streamed.toString().contains("待验证"));
  var saved=db.queryForMap("SELECT status,result_content,evidence_snapshot FROM diagnosis_record WHERE id=?",id);
  assertEquals("OPEN",saved.get("STATUS"));assertTrue(saved.get("EVIDENCE_SNAPSHOT").toString().contains("recentReleases"));
  assertEquals(1,diagnosis.history(1).size());assertTrue(diagnosis.history(2).isEmpty());
  diagnosis.resolve(1,id);assertEquals("RESOLVED",db.queryForObject("SELECT status FROM diagnosis_record WHERE id=?",String.class,id));
 }
 @Test void comparesVersionsLocallyAndReportsRiskHints(){
  var result=versionCompare.compare(301,302);assertEquals("v1",result.oldVersion());assertEquals("v2",result.newVersion());
  assertEquals(2,result.added());assertEquals(2,result.deleted());assertFalse(result.riskHints().isEmpty());
  assertTrue(result.riskHints().stream().anyMatch(x->x.contains("运行参数")));assertTrue(result.riskHints().stream().anyMatch(x->x.contains("发布流程")));
 }
 @Test void deduplicatesAndResolvesKnowledgeGaps(){
  gaps.record(100,"如何配置未知组件？");gaps.record(100,"  如何配置未知组件？  ");
  var row=db.queryForMap("SELECT id,occurrence_count,status FROM knowledge_gap");assertEquals(2,((Number)row.get("OCCURRENCE_COUNT")).intValue());assertEquals("OPEN",row.get("STATUS"));
  long gapId=((Number)row.get("ID")).longValue();gaps.startProcessing(gapId,1,"管理员开始处理");gaps.resolve(gapId,1,"MANUAL_RESOLUTION","历史缺口人工关闭");assertEquals("RESOLVED",db.queryForObject("SELECT status FROM knowledge_gap",String.class));
 }
 @Test void businessQueryUsesOnlyWhitelistedToolsAndWritesAudit(){
  db.update("INSERT INTO release_record(service_name,version_no,environment,status,released_at,summary) VALUES('order-service','2.4.0','PROD','SUCCESS',CURRENT_TIMESTAMP,'灰度发布完成')");
  var result=businessTools.query(1,"order-service 最近有哪些发布记录？");
  assertEquals("latest_releases",result.get("toolName"));assertEquals("order-service",result.get("serviceName"));assertEquals(1,result.get("resultCount"));
  var audit=db.queryForMap("SELECT user_id,tool_name,parameter_summary,result_count,success FROM tool_call_audit");
  assertEquals(1L,((Number)audit.get("USER_ID")).longValue());assertEquals("latest_releases",audit.get("TOOL_NAME"));assertEquals("service=order-service",audit.get("PARAMETER_SUMMARY"));assertEquals(true,audit.get("SUCCESS"));
  assertThrows(com.opsnexus.knowledge.KnowledgeException.class,()->businessTools.query(1,"SELECT * FROM users"));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM tool_call_audit",Integer.class));
 }
 @Test void analyticsValidatesAstExecutesWithReadOnlyAccountAndAudits(){
  db.update("INSERT INTO incident_record(service_name,symptom,status,occurred_at) VALUES('order-service','连接超时','RESOLVED',CURRENT_TIMESTAMP)");
  when(chat.complete(anyString(),eq("统计各服务的故障数量"))).thenReturn("SELECT service_name, COUNT(id) AS incident_count FROM incident_record GROUP BY service_name ORDER BY incident_count DESC");
  var result=analytics.query(1,"统计各服务的故障数量");assertEquals(1,result.get("rowCount"));assertTrue(result.get("generatedSql").toString().contains("LIMIT 100"));
  assertEquals("SUCCESS",db.queryForObject("SELECT execution_status FROM sql_query_audit",String.class));
  assertThrows(com.opsnexus.knowledge.KnowledgeException.class,()->sqlValidator.validate("DELETE FROM incident_record"));
  assertThrows(com.opsnexus.knowledge.KnowledgeException.class,()->sqlValidator.validate("SELECT password_hash FROM app_user"));
  assertThrows(com.opsnexus.knowledge.KnowledgeException.class,()->sqlValidator.validate("SELECT service_name FROM incident_record; DELETE FROM incident_record"));
 }
 @Test void aiGovernanceBlocksConcurrencyAndRateLimitAndLogsEstimatedTokens(){
  long user=Math.abs(System.nanoTime());var first=aiGovernance.enter(user,"TEST","mock-model",20);assertThrows(com.opsnexus.knowledge.KnowledgeException.class,()->aiGovernance.enter(user,"TEST","mock-model",1));first.output(12);first.close();
  for(int i=0;i<9;i++)aiGovernance.enter(user,"TEST","mock-model",4).close();
  var limited=assertThrows(com.opsnexus.knowledge.KnowledgeException.class,()->aiGovernance.enter(user,"TEST","mock-model",1));assertEquals("AI_RATE_LIMITED",limited.code);
  var row=db.queryForMap("SELECT input_tokens,output_tokens,token_estimated,status FROM ai_call_log ORDER BY id LIMIT 1");assertEquals(5,((Number)row.get("INPUT_TOKENS")).intValue());assertEquals(3,((Number)row.get("OUTPUT_TOKENS")).intValue());assertEquals(true,row.get("TOKEN_ESTIMATED"));assertEquals("SUCCESS",row.get("STATUS"));assertEquals("MEMORY",aiGovernance.limiterBackend());
 }
 @Test void sendsPriorTurnsWithRolesButDoesNotLeakAcrossConversationsOrUsers(){
  var current=Document.builder().id("new").text("order-service 当前发布与事故信息").metadata("versionId",302L).score(.80).build();when(vectors.search(anyString(),eq(8))).thenReturn(List.of(current));
  var histories=new ArrayList<List<DeepSeekChat.ConversationMessage>>();
  doAnswer(inv->{histories.add(List.copyOf(inv.getArgument(1)));java.util.function.Consumer<String> out=inv.getArgument(3);out.accept("已回答");return null;}).when(chat).stream(anyString(),anyList(),anyString(),any());
  var first=service.answer(1,null,100,"order-service 是什么？",x->{});
  service.answer(1,first.conversationId(),100,"它最近一次发布是什么时候？",x->{});
  service.answer(1,first.conversationId(),100,"那最近发生过什么事故？",x->{});
  service.answer(1,null,100,"它最近发生过什么事故？",x->{});
  assertEquals(4,histories.size());assertTrue(histories.get(0).isEmpty());assertEquals(2,histories.get(1).size());assertEquals("user",histories.get(1).get(0).role());assertTrue(histories.get(1).get(0).content().contains("order-service"));assertEquals(4,histories.get(2).size());assertTrue(histories.get(3).isEmpty());
  assertThrows(com.opsnexus.knowledge.KnowledgeException.class,()->service.answer(2,first.conversationId(),100,"它是什么？",x->{}));
 } @Test void excludesIncompleteAssistantMessagesFromModelHistory(){
  db.update("INSERT INTO conversation(user_id,title) VALUES(1,'历史测试')");long cid=db.queryForObject("SELECT MAX(id) FROM conversation",Long.class);
  db.update("INSERT INTO chat_message(conversation_id,role,content) VALUES(?,'USER','order-service 是什么？')",cid);
  db.update("INSERT INTO chat_message(conversation_id,role,content) VALUES(?,'ASSISTANT','未完成的异常输出')",cid);
  var current=Document.builder().id("new").text("order-service 当前信息").metadata("versionId",302L).score(.80).build();when(vectors.search(anyString(),eq(8))).thenReturn(List.of(current));
  doAnswer(inv->{List<DeepSeekChat.ConversationMessage> history=inv.getArgument(1);assertEquals(1,history.size());assertTrue(history.getFirst().content().contains("order-service"));assertFalse(history.stream().anyMatch(m->m.content().contains("异常输出")));java.util.function.Consumer<String> out=inv.getArgument(3);out.accept("已回答");return null;}).when(chat).stream(anyString(),anyList(),anyString(),any());
  service.answer(1,cid,100,"它最近一次发布是什么时候？",x->{});
 }}
