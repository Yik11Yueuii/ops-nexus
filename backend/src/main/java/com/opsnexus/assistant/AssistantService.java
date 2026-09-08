package com.opsnexus.assistant;

import com.opsnexus.ingestion.VectorIndex;
import com.opsnexus.knowledge.KnowledgeException;
import com.opsnexus.governance.KnowledgeGapService;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {
    public record Evidence(long versionId,String vectorId,String title,String versionNo,Integer pageNumber,String quote,double score){}
    public record Result(long conversationId,long messageId,String confidence,String sufficiency,List<Evidence> citations){}
    private final VectorIndex vectors;private final DeepSeekChat chat;private final JdbcTemplate db;private final KnowledgeGapService gaps;
    public AssistantService(VectorIndex vectors,DeepSeekChat chat,JdbcTemplate db,KnowledgeGapService gaps){this.vectors=vectors;this.chat=chat;this.db=db;this.gaps=gaps;}
    public Map<String,Object> capabilities(){return Map.of("embeddingConfigured",vectors.configured(),"chatConfigured",chat.configured(),"chatModel",chat.modelName());}
    public List<Map<String,Object>> conversations(long user){return db.queryForList("SELECT c.id,c.title,c.created_at,c.updated_at,(SELECT COUNT(*) FROM chat_message m WHERE m.conversation_id=c.id) AS message_count FROM conversation c WHERE c.user_id=? ORDER BY c.updated_at DESC LIMIT 50",user);}
    public Map<String,Object> conversation(long user,long id){ownedConversation(id,user);var messages=db.queryForList("SELECT m.id,m.role,m.content,m.confidence_level,m.evidence_sufficiency,m.created_at,(SELECT rating FROM message_feedback f WHERE f.message_id=m.id AND f.user_id=?) AS feedback FROM chat_message m WHERE m.conversation_id=? ORDER BY m.id",user,id);for(var m:messages)if("ASSISTANT".equals(m.get("ROLE")))m.put("citations",db.queryForList("SELECT version_id,vector_id,quote_text,page_number,title,version_no FROM message_citation WHERE message_id=? ORDER BY id",m.get("ID")));return Map.of("id",id,"messages",messages);}
    public void deleteConversation(long user,long id){ownedConversation(id,user);db.update("DELETE FROM message_feedback WHERE message_id IN(SELECT id FROM chat_message WHERE conversation_id=?)",id);db.update("DELETE FROM message_citation WHERE message_id IN(SELECT id FROM chat_message WHERE conversation_id=?)",id);db.update("DELETE FROM chat_message WHERE conversation_id=?",id);db.update("DELETE FROM conversation WHERE id=? AND user_id=?",id,user);}
    public void feedback(long user,long message,String rating,String comment){if(!Set.of("UP","DOWN").contains(rating))throw new KnowledgeException(400,"INVALID_INPUT","反馈类型不合法");if(comment!=null&&comment.length()>500)throw new KnowledgeException(400,"INVALID_INPUT","反馈说明不能超过 500 字");if(db.queryForObject("SELECT COUNT(*) FROM chat_message m JOIN conversation c ON c.id=m.conversation_id WHERE m.id=? AND c.user_id=? AND m.role='ASSISTANT'",Integer.class,message,user)==0)throw new KnowledgeException(404,"NOT_FOUND","回答不存在");var ids=db.queryForList("SELECT id FROM message_feedback WHERE message_id=? AND user_id=?",Long.class,message,user);if(ids.isEmpty())db.update("INSERT INTO message_feedback(message_id,user_id,rating,comment_text) VALUES(?,?,?,?)",message,user,rating,comment);else db.update("UPDATE message_feedback SET rating=?,comment_text=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",rating,comment,ids.getFirst());}
    public Result answer(long userId,Long conversationId,long kbId,String question,Consumer<String> delta){
        if(question==null||question.isBlank()||question.length()>1000)throw new KnowledgeException(400,"INVALID_INPUT","问题不能为空且不能超过 1000 字");
        if(!chat.configured())throw new KnowledgeException(503,"CHAT_NOT_CONFIGURED","请先配置 DEEPSEEK_API_KEY 并重启后端");
        if(!vectors.configured())throw new KnowledgeException(503,"EMBEDDING_NOT_CONFIGURED","请先配置 DASHSCOPE_API_KEY 并完成文档向量化");
        if(db.queryForObject("SELECT COUNT(*) FROM knowledge_base WHERE id=? AND status='ACTIVE'",Integer.class,kbId)==0)
            throw new KnowledgeException(404,"NOT_FOUND","知识库不存在");
        long cid=conversationId==null?createConversation(userId,question):ownedConversation(conversationId,userId);
        insert("INSERT INTO chat_message(conversation_id,role,content) VALUES(?,'USER',?)",cid,question.strip());
        var evidence=retrieve(kbId,question.strip());
        if(evidence.isEmpty()){
            gaps.record(kbId,question);
            String refusal="当前知识库没有找到足够的已发布依据，我不能把通用知识当作星云科技内部事实。请补充资料或联系知识管理员。";
            delta.accept(refusal);
            long mid=insert("INSERT INTO chat_message(conversation_id,role,content,confidence_level,evidence_sufficiency) VALUES(?,'ASSISTANT',?,'LOW','INSUFFICIENT')",cid,refusal);
            return new Result(cid,mid,"LOW","INSUFFICIENT",List.of());
        }
        StringBuilder context=new StringBuilder();
        for(int i=0;i<evidence.size();i++){var e=evidence.get(i);context.append("\n[证据").append(i+1).append("] ").append(e.title()).append(" ").append(e.versionNo());if(e.pageNumber()!=null)context.append(" 第").append(e.pageNumber()).append("页");context.append("\n").append(e.quote()).append("\n");}
        String system="""
            你是星云科技内部知识运营助手。企业内部事实只能来自下方证据，不得编造证据中不存在的负责人、版本、时间、配置或执行结果。
            但不要机械复述原文：应理解用户目标，综合多个片段进行归纳、比较、因果分析、风险推演和下一步建议。
            回答使用中文，并尽量按“直接结论—依据与分析—建议下一步”组织；简单问题可缩短结构。
            文档明确记载的内容称为“文档依据”；根据常见工程实践得到的合理延伸必须称为“分析建议”或“待验证假设”，不能冒充星云科技现行规定。
            遇到证据冲突、信息缺失或高风险操作时，明确指出不确定之处，并给出需要补充的信息或安全的验证方法。
            不要声称已经执行命令。引用证据时使用 [证据1] 这样的编号。
            """+context;
        var content=new StringBuilder();
        try{chat.stream(system,question,part->{content.append(part);delta.accept(part);});}
        catch(Exception e){if(content.isEmpty())throw new KnowledgeException(502,"CHAT_FAILED",e.getMessage());throw e;}
        long sourceCount=evidence.stream().map(Evidence::versionId).distinct().count();
        String confidence=sourceCount>=2&&evidence.stream().allMatch(e->e.score()>=0.65)?"HIGH":"MEDIUM";
        String sufficiency=sourceCount>=2?"SUFFICIENT":"PARTIAL";
        long mid=insert("INSERT INTO chat_message(conversation_id,role,content,confidence_level,evidence_sufficiency) VALUES(?,'ASSISTANT',?,?,?)",cid,content.toString(),confidence,sufficiency);
        for(var e:evidence)db.update("INSERT INTO message_citation(message_id,version_id,vector_id,quote_text,page_number,title,version_no) VALUES(?,?,?,?,?,?,?)",mid,e.versionId(),e.vectorId(),e.quote(),e.pageNumber(),e.title(),e.versionNo());
        db.update("UPDATE conversation SET updated_at=CURRENT_TIMESTAMP WHERE id=?",cid);
        return new Result(cid,mid,confidence,sufficiency,evidence);
    }
    List<Evidence> retrieve(long kb,String question){
        List<Document> candidates;
        try{candidates=vectors.search(question,8);}catch(Exception e){throw new KnowledgeException(503,"VECTOR_UNAVAILABLE",e.getMessage());}
        var result=new ArrayList<Evidence>();var seen=new HashSet<String>();
        for(var doc:candidates){
            Object raw=doc.getMetadata().get("versionId");if(!(raw instanceof Number n))continue;long vid=n.longValue();
            var rows=db.queryForList("""
                SELECT v.version_no,d.title FROM document_version v
                JOIN knowledge_document d ON d.id=v.document_id JOIN knowledge_base k ON k.id=d.kb_id
                WHERE v.id=? AND d.kb_id=? AND d.current_version_id=v.id AND v.publish_status='PUBLISHED'
                AND v.process_status='READY' AND k.status='ACTIVE'
                """,vid,kb);
            if(rows.isEmpty()||!seen.add(doc.getId()))continue;
            var row=rows.getFirst();Integer page=doc.getMetadata().get("pageNumber") instanceof Number p?p.intValue():null;
            String text=Objects.requireNonNullElse(doc.getText(),"");String quote=text.length()>900?text.substring(0,900)+"…":text;
            result.add(new Evidence(vid,doc.getId(),row.get("TITLE").toString(),row.get("VERSION_NO").toString(),page,quote,Objects.requireNonNullElse(doc.getScore(),0.0)));
            if(result.size()==4)break;
        }return result;
    }
    private long createConversation(long user,String question){return insert("INSERT INTO conversation(user_id,title) VALUES(?,?)",user,question.substring(0,Math.min(60,question.length())));}
    private long ownedConversation(long id,long user){if(db.queryForObject("SELECT COUNT(*) FROM conversation WHERE id=? AND user_id=?",Integer.class,id,user)==0)throw new KnowledgeException(404,"NOT_FOUND","会话不存在");return id;}
    private long insert(String sql,Object...args){var key=new GeneratedKeyHolder();db.update(c->{var p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);return Objects.requireNonNull(key.getKey()).longValue();}
}
