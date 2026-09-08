package com.opsnexus.governance;
import com.opsnexus.knowledge.KnowledgeException;import java.util.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;
@Service public class KnowledgeGapService{
 private final JdbcTemplate db;public KnowledgeGapService(JdbcTemplate db){this.db=db;}
 public synchronized void record(long kb,String question){String q=question.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);var ids=db.queryForList("SELECT id FROM knowledge_gap WHERE kb_id=? AND normalized_question=?",Long.class,kb,q);if(ids.isEmpty())db.update("INSERT INTO knowledge_gap(kb_id,normalized_question,sample_question) VALUES(?,?,?)",kb,q,question.strip());else db.update("UPDATE knowledge_gap SET occurrence_count=occurrence_count+1,last_seen_at=CURRENT_TIMESTAMP,status='OPEN',resolved_at=NULL WHERE id=?",ids.getFirst());}
 public List<Map<String,Object>> list(){return db.queryForList("SELECT g.id,g.kb_id,k.name AS knowledge_base,g.sample_question,g.occurrence_count,g.status,g.first_seen_at,g.last_seen_at,g.resolved_at FROM knowledge_gap g JOIN knowledge_base k ON k.id=g.kb_id ORDER BY CASE WHEN g.status='OPEN' THEN 0 ELSE 1 END,g.occurrence_count DESC,g.last_seen_at DESC");}
 public Map<String,Object> stats(){return Map.of("open",db.queryForObject("SELECT COUNT(*) FROM knowledge_gap WHERE status='OPEN'",Integer.class),"resolved",db.queryForObject("SELECT COUNT(*) FROM knowledge_gap WHERE status='RESOLVED'",Integer.class),"occurrences",db.queryForObject("SELECT COALESCE(SUM(occurrence_count),0) FROM knowledge_gap",Long.class));}
 public void resolve(long id){if(db.update("UPDATE knowledge_gap SET status='RESOLVED',resolved_at=CURRENT_TIMESTAMP WHERE id=?",id)==0)throw new KnowledgeException(404,"NOT_FOUND","知识缺口不存在");}
}
