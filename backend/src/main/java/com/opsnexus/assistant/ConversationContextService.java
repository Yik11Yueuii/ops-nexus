package com.opsnexus.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Bounded, user-scoped recent-message cache; relational storage remains authoritative. */
@Service
public class ConversationContextService {
    public record Message(String role, String content) {}
    public record Snapshot(List<Message> messages, int chars, String source) {}
    private final JdbcTemplate db; private final StringRedisTemplate redis; private final ObjectMapper json;
    private final int maxMessages; private final int maxChars; private final Duration ttl;
    public ConversationContextService(JdbcTemplate db, StringRedisTemplate redis, ObjectMapper json,
            @Value("${ops.conversation-context.max-messages:8}") int maxMessages,
            @Value("${ops.conversation-context.max-chars:4000}") int maxChars,
            @Value("${ops.conversation-context.ttl-seconds:1800}") long ttlSeconds) {
        this.db=db;this.redis=redis;this.json=json;this.maxMessages=maxMessages;this.maxChars=maxChars;this.ttl=Duration.ofSeconds(ttlSeconds);
    }
    public Snapshot recent(long userId,long conversationId) {
        try { String cached=redis.opsForValue().get(key(userId,conversationId)); if(cached!=null)return snapshot(read(cached),"REDIS"); } catch(Exception ignored) { }
        var result=snapshot(load(userId,conversationId),"DATABASE"); try { redis.opsForValue().set(key(userId,conversationId),json.writeValueAsString(result.messages()),ttl); } catch(Exception ignored) { } return result;
    }
    public void refresh(long userId,long conversationId) { var result=snapshot(load(userId,conversationId),"DATABASE");try { redis.opsForValue().set(key(userId,conversationId),json.writeValueAsString(result.messages()),ttl); } catch(Exception ignored) { } }
    public void invalidate(long userId,long conversationId) { try { redis.delete(key(userId,conversationId)); } catch(Exception ignored) { } }
    String key(long userId,long conversationId){return "opsnexus:conversation:"+userId+":"+conversationId+":recent";}
    private List<Message> load(long userId,long conversationId) {
        var rows=db.queryForList("SELECT m.role,m.content FROM chat_message m JOIN conversation c ON c.id=m.conversation_id WHERE c.id=? AND c.user_id=? AND (m.role='USER' OR (m.role='ASSISTANT' AND m.confidence_level IS NOT NULL)) ORDER BY m.id DESC LIMIT ?",conversationId,userId,maxMessages);
        var messages=new ArrayList<Message>();for(var row:rows)messages.add(new Message(row.get("ROLE").toString(),row.get("CONTENT").toString()));Collections.reverse(messages);return messages;
    }
    private List<Message> read(String cached)throws Exception{return json.readValue(cached,new TypeReference<List<Message>>(){});}
    private Snapshot snapshot(List<Message> source,String backend){var newest=new ArrayList<Message>();int used=0;for(int i=source.size()-1;i>=0&&newest.size()<maxMessages&&used<maxChars;i--){var item=source.get(i);String content=item.content()==null?"":item.content();int available=maxChars-used;if(content.length()>available)content="…"+content.substring(Math.max(0,content.length()-Math.max(0,available-1)));if(!content.isEmpty()){newest.add(new Message(item.role(),content));used+=content.length();}}Collections.reverse(newest);return new Snapshot(List.copyOf(newest),used,backend);}
}
