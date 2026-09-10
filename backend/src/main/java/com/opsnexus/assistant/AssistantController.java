package com.opsnexus.assistant;

import com.opsnexus.knowledge.KnowledgeException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.opsnexus.governance.AiGovernanceService;

@RestController @RequestMapping("/api/assistant")
public class AssistantController {
    private final AssistantService service;private final DiagnosisService diagnosis;private final AiGovernanceService governance;
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    public AssistantController(AssistantService service,DiagnosisService diagnosis,AiGovernanceService governance){this.service=service;this.diagnosis=diagnosis;this.governance=governance;}
    public record ChatInput(Long conversationId,long kbId,String question){}
    public record DiagnosisInput(long kbId,String serviceName,String symptom,String context){}
    public record FeedbackInput(String rating,String comment){}
    @GetMapping("/capabilities") public Object capabilities(){return Map.of("code","OK","message","成功","data",service.capabilities());}
    @GetMapping("/conversations") public Object conversations(@AuthenticationPrincipal Jwt user){return Map.of("code","OK","message","成功","data",service.conversations(Long.parseLong(user.getSubject())));}
    @GetMapping("/conversations/{id}") public Object conversation(@PathVariable long id,@AuthenticationPrincipal Jwt user){return Map.of("code","OK","message","成功","data",service.conversation(Long.parseLong(user.getSubject()),id));}
    @DeleteMapping("/conversations/{id}") public Object deleteConversation(@PathVariable long id,@AuthenticationPrincipal Jwt user){service.deleteConversation(Long.parseLong(user.getSubject()),id);return Map.of("code","OK","message","已删除","data",Map.of("id",id));}
    @PutMapping("/messages/{id}/feedback") public Object feedback(@PathVariable long id,@RequestBody FeedbackInput input,@AuthenticationPrincipal Jwt user){service.feedback(Long.parseLong(user.getSubject()),id,input.rating(),input.comment());return Map.of("code","OK","message","感谢反馈","data",Map.of("id",id,"rating",input.rating()));}
    @PostMapping(value="/chat/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatInput input,@AuthenticationPrincipal Jwt user){
        long userId=Long.parseLong(user.getSubject());var permit=governance.enter(userId,"RAG_CHAT","deepseek-chat",input.question()==null?0:input.question().length());
        var emitter=new SseEmitter(65000L);
        workers.submit(()->{
            try{
                send(emitter,"start",Map.of("conversationId",input.conversationId()==null?0:input.conversationId()));
                var result=service.answer(userId,input.conversationId(),input.kbId(),input.question(),part->{permit.output(part.length());send(emitter,"delta",Map.of("content",part));});
                send(emitter,"evidence",Map.of("citations",result.citations()));
                send(emitter,"complete",Map.of("conversationId",result.conversationId(),"messageId",result.messageId(),"confidenceLevel",result.confidence(),"evidenceSufficiency",result.sufficiency(),"contextTurnsUsed",result.contextTurnsUsed(),"approximateContextChars",result.approximateContextChars()));
                emitter.complete();
            }catch(KnowledgeException e){permit.fail(e.code);send(emitter,"error",Map.of("code",e.code,"message",e.getMessage()));emitter.complete();}
            catch(Exception e){permit.fail("CHAT_FAILED");send(emitter,"error",Map.of("code","CHAT_FAILED","message","回答生成失败，请稍后重试"));emitter.complete();}finally{permit.close();}
        });return emitter;
    }
    @GetMapping("/diagnosis/services") public Object services(){return Map.of("code","OK","message","成功","data",diagnosis.services());}
    @GetMapping("/diagnosis/history") public Object history(@AuthenticationPrincipal Jwt user){return Map.of("code","OK","message","成功","data",diagnosis.history(Long.parseLong(user.getSubject())));}
    @PatchMapping("/diagnosis/{id}/resolve") public Object resolve(@PathVariable long id,@AuthenticationPrincipal Jwt user){diagnosis.resolve(Long.parseLong(user.getSubject()),id);return Map.of("code","OK","message","已标记解决","data",Map.of("id",id));}
    @PostMapping(value="/diagnose/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter diagnose(@RequestBody DiagnosisInput input,@AuthenticationPrincipal Jwt user){long userId=Long.parseLong(user.getSubject());boolean admin="ADMIN".equals(user.getClaimAsString("role"));var permit=governance.enter(userId,"DIAGNOSIS","deepseek-chat",(input.symptom()==null?0:input.symptom().length())+(input.context()==null?0:input.context().length()));var emitter=new SseEmitter(65000L);workers.submit(()->{try{send(emitter,"start",Map.of("serviceName",input.serviceName()));var result=diagnosis.diagnose(userId,admin,input.kbId(),input.serviceName(),input.symptom(),input.context(),p->{permit.output(p.length());send(emitter,"delta",Map.of("content",p));});send(emitter,"evidence",result);send(emitter,"complete",Map.of("diagnosisId",result.get("diagnosisId"),"status","OPEN"));emitter.complete();}catch(KnowledgeException e){permit.fail(e.code);send(emitter,"error",Map.of("code",e.code,"message",e.getMessage()));emitter.complete();}catch(Exception e){permit.fail("DIAGNOSIS_FAILED");send(emitter,"error",Map.of("code","DIAGNOSIS_FAILED","message","诊断生成失败，请稍后重试"));emitter.complete();}finally{permit.close();}});return emitter;}
    private void send(SseEmitter emitter,String name,Object data){try{emitter.send(SseEmitter.event().name(name).data(data));}catch(Exception e){throw new CompletionException(e);}}
}
