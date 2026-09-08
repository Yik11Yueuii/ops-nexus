package com.opsnexus.knowledge;

import com.opsnexus.ingestion.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final DocumentParser parser;
    private final VectorIndex index;
    private final Path uploads;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), r -> { var t = new Thread(r,"document-ingestion"); t.setDaemon(true); return t; });
    public KnowledgeService(JdbcTemplate db, TransactionTemplate tx, DocumentParser parser, VectorIndex index,
            @Value("${ops.data-dir:./runtime}") String dir) {
        this.db=db; this.tx=tx; this.parser=parser; this.index=index;
        uploads=Path.of(dir).toAbsolutePath().normalize().resolve("uploads");
    }
    @PostConstruct void recover() throws Exception {
        Files.createDirectories(uploads);
        db.update("UPDATE document_version SET process_status='FAILED',failure_reason='上次处理被中断，请重试' WHERE process_status='PROCESSING'");
        for (var v : rows("SELECT * FROM document_version WHERE process_status='READY'")) {
            long id = number(v,"id");
            var chunks = chunks(id);
            if (chunks.isEmpty() || chunks.stream().anyMatch(c -> !index.has(c.get("vectorId").toString())))
                db.update("UPDATE document_version SET process_status='FAILED',failure_reason='向量快照缺失或损坏，需重新处理' WHERE id=?",id);
        }
    }
    @PreDestroy void shutdown() { worker.shutdownNow(); }
    public Map<String,Object> capabilities() {
        var result = new LinkedHashMap<String,Object>();
        result.put("embeddingConfigured",index.configured()); result.put("embeddingModel",index.modelName());
        result.put("vectorError",index.error()); return result;
    }
    public List<Map<String,Object>> bases(boolean admin) {
        return rows("SELECT k.*, (SELECT COUNT(*) FROM knowledge_document d WHERE d.kb_id=k.id) AS document_count FROM knowledge_base k WHERE status='ACTIVE' ORDER BY id");
    }
    public synchronized long createBase(String name, String description, long user) {
        require(name,100,"知识库名称");
        try { return insert("INSERT INTO knowledge_base(name,description,created_by) VALUES(?,?,?)",name.strip(),description,user); }
        catch (org.springframework.dao.DuplicateKeyException e) { throw conflict("知识库名称已存在"); }
    }
    public List<Map<String,Object>> documents(long kb, boolean admin) {
        base(kb);
        return rows("""
            SELECT d.*, (SELECT COUNT(*) FROM document_version v WHERE v.document_id=d.id) AS version_count,
            (SELECT MAX(created_at) FROM document_version v WHERE v.document_id=d.id) AS latest_at
            FROM knowledge_document d WHERE kb_id=?
            """ + (admin ? "" : " AND EXISTS(SELECT 1 FROM document_version v WHERE v.id=d.current_version_id AND v.process_status='READY' AND v.publish_status='PUBLISHED')") + " ORDER BY d.id DESC",kb);
    }
    public List<Map<String,Object>> versions(long doc, boolean admin) {
        var d = doc(doc); base(number(d,"kbId"));
        return rows("SELECT id,document_id,version_no,original_name,file_type,file_size,checksum,process_status,publish_status,effective_at,chunk_count,failure_reason,created_at,published_at FROM document_version WHERE document_id=?"
            + (admin ? "" : " AND id="+numberOrZero(d.get("currentVersionId"))+" AND process_status='READY' AND publish_status='PUBLISHED'") + " ORDER BY id DESC",doc);
    }
    public synchronized long upload(long kb, Long documentId, String title, String version, byte[] bytes, String name, long user) throws Exception {
        base(kb); require(title,200,"文档标题"); require(version,30,"版本号");
        if (bytes.length==0 || bytes.length>20*1024*1024) throw new KnowledgeException(413,"FILE_SIZE","文件不能为空且不能超过 20 MB");
        if (name==null || name.length()>255) throw bad("文件名称不合法");
        String type = name.substring(name.lastIndexOf('.')+1).toUpperCase(Locale.ROOT);
        if (type.equals("MARKDOWN")) type="MD";
        if (!Set.of("PDF","DOCX","MD","TXT").contains(type)) throw bad("仅支持 PDF、DOCX、Markdown、TXT");
        if (documentId!=null && number(doc(documentId),"kbId")!=kb) throw bad("文档不属于该知识库");
        String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (db.queryForObject("SELECT COUNT(*) FROM document_version v JOIN knowledge_document d ON v.document_id=d.id WHERE d.kb_id=? AND v.checksum=?",Integer.class,kb,checksum)>0)
            throw conflict("该文件已存在于当前知识库");
        if (documentId!=null && db.queryForObject("SELECT COUNT(*) FROM document_version WHERE document_id=? AND version_no=?",Integer.class,documentId,version)>0)
            throw conflict("版本号已存在，请使用新版本号");
        var parts=parser.parse(bytes,type);
        String stored=UUID.randomUUID()+"."+type.toLowerCase(Locale.ROOT);
        Path path=uploads.resolve(stored); Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
        final String fileType=type;
        try {
            return tx.execute(s -> {
                long did=documentId==null?insert("INSERT INTO knowledge_document(kb_id,title,created_by) VALUES(?,?,?)",kb,title.strip(),user):documentId;
                long vid=insert("INSERT INTO document_version(document_id,version_no,original_name,file_type,file_size,checksum,storage_path,process_status,chunk_count) VALUES(?,?,?,?,?,?,?,'PARSED',?)",
                    did,version.strip(),name,fileType,bytes.length,checksum,stored,parts.size());
                for(var part:parts) db.update("INSERT INTO document_chunk_ref(version_id,vector_id,chunk_index,page_number,content) VALUES(?,?,?,?,?)",
                    vid,"version-"+vid+"-chunk-"+part.index(),part.index(),part.page(),part.content());
                return vid;
            });
        } catch(Exception e) { Files.deleteIfExists(path); throw e; }
    }
    public List<Map<String,Object>> preview(long id, boolean admin) {
        accessible(id,admin); return chunks(id);
    }
    public Path download(long id, boolean admin) {
        var v=accessible(id,admin); Path path=uploads.resolve(v.get("storagePath").toString()).normalize();
        if (!path.startsWith(uploads) || !Files.isRegularFile(path)) throw new KnowledgeException(404,"FILE_MISSING","原文件不存在");
        return path;
    }
    public synchronized void process(long id) {
        var v=version(id);
        if ("PROCESSING".equals(v.get("processStatus"))) throw conflict("该版本正在处理");
        if ("READY".equals(v.get("processStatus"))) return;
        if (!index.configured()) throw new KnowledgeException(503,"MODEL_NOT_CONFIGURED","尚未配置模型密钥，正文已保存，可预览；配置后重启后端再向量化");
        if (index.error()!=null) throw new KnowledgeException(503,"VECTOR_UNAVAILABLE",index.error());
        db.update("UPDATE document_version SET process_status='PROCESSING',failure_reason=NULL WHERE id=?",id);
        try { worker.execute(() -> ingest(id)); }
        catch (RejectedExecutionException e) {
            db.update("UPDATE document_version SET process_status='PARSED' WHERE id=?",id);
            throw new KnowledgeException(429,"QUEUE_FULL","处理队列已满，请稍后重试");
        }
    }
    private void ingest(long id) {
        try {
            var v=version(id); var d=doc(number(v,"documentId"));
            var docs=new ArrayList<Document>();
            for(var c:chunks(id)) {
                var meta=new HashMap<String,Object>();
                meta.put("kbId",number(d,"kbId")); meta.put("documentId",number(d,"id")); meta.put("versionId",id);
                meta.put("title",d.get("title"));meta.put("versionNo",v.get("versionNo")); meta.put("chunkIndex",c.get("chunkIndex"));
                if(c.get("pageNumber")!=null)meta.put("pageNumber",c.get("pageNumber"));
                docs.add(new Document(c.get("vectorId").toString(),c.get("content").toString(),meta));
            }
            index.add(docs);
            db.update("UPDATE document_version SET process_status='READY',failure_reason=NULL WHERE id=?",id);
        } catch(Exception e) {
            String reason=e instanceof IllegalStateException?e.getMessage():"文档向量化失败，请重试";
            db.update("UPDATE document_version SET process_status='FAILED',failure_reason=? WHERE id=?",reason==null?"处理失败":reason.substring(0,Math.min(reason.length(),500)),id);
        }
    }
    public synchronized void publish(long id) {
        tx.executeWithoutResult(s -> {
            var v=version(id);
            db.queryForList("SELECT id FROM knowledge_document WHERE id=? FOR UPDATE",number(v,"documentId"));
            if (!"READY".equals(v.get("processStatus")) || chunks(id).stream().anyMatch(c->!index.has(c.get("vectorId").toString())))
                throw conflict("必须完成向量化才能发布");
            if ("ARCHIVED".equals(v.get("publishStatus"))) throw conflict("归档版本不能重新发布，请上传新版本");
            db.update("UPDATE document_version SET publish_status='ARCHIVED' WHERE document_id=? AND publish_status='PUBLISHED' AND id<>?",number(v,"documentId"),id);
            db.update("UPDATE document_version SET publish_status='PUBLISHED',published_at=CURRENT_TIMESTAMP,effective_at=CURRENT_TIMESTAMP WHERE id=?",id);
            db.update("UPDATE knowledge_document SET current_version_id=? WHERE id=?",id,number(v,"documentId"));
        });
    }
    public synchronized void archive(long id) {
        var v=version(id);
        if (!"PUBLISHED".equals(v.get("publishStatus"))) throw conflict("只能归档已发布版本");
        tx.executeWithoutResult(s -> {
            db.update("UPDATE document_version SET publish_status='ARCHIVED' WHERE id=?",id);
            db.update("UPDATE knowledge_document SET current_version_id=NULL WHERE current_version_id=?",id);
        });
    }
    public synchronized void delete(long id) {
        var v=version(id);
        if (!"DRAFT".equals(v.get("publishStatus")) || "PROCESSING".equals(v.get("processStatus")))
            throw conflict("仅能删除未发布且未处理中的草稿");
        var ids=chunks(id).stream().map(c->c.get("vectorId").toString()).toList();
        if(ids.stream().anyMatch(index::has))index.delete(ids);
        try { Files.deleteIfExists(download(id,true)); }
        catch (KnowledgeException e) { if(!"FILE_MISSING".equals(e.code))throw e; }
        catch(Exception e){ throw conflict("文件删除失败，请重试"); }
        tx.executeWithoutResult(s -> {
            db.update("DELETE FROM document_chunk_ref WHERE version_id=?",id);
            db.update("DELETE FROM document_version WHERE id=?",id);
            db.update("DELETE FROM knowledge_document WHERE id=? AND NOT EXISTS(SELECT 1 FROM document_version WHERE document_id=?)",number(v,"documentId"),number(v,"documentId"));
        });
    }
    private Map<String,Object> accessible(long id, boolean admin) {
        var v=version(id); var d=doc(number(v,"documentId"));base(number(d,"kbId"));
        if(!admin && (!"PUBLISHED".equals(v.get("publishStatus")) || !"READY".equals(v.get("processStatus")) || numberOrZero(d.get("currentVersionId"))!=id))
            throw new KnowledgeException(404,"NOT_FOUND","文档不可访问");
        return v;
    }
    private void base(long id) { if(rows("SELECT id FROM knowledge_base WHERE id=? AND status='ACTIVE'",id).isEmpty())throw notFound(); }
    private Map<String,Object> doc(long id) { return one("SELECT * FROM knowledge_document WHERE id=?",id); }
    private Map<String,Object> version(long id) { return one("SELECT * FROM document_version WHERE id=?",id); }
    private List<Map<String,Object>> chunks(long id){ return rows("SELECT id,vector_id,chunk_index,page_number,content FROM document_chunk_ref WHERE version_id=? ORDER BY chunk_index",id); }
    private Map<String,Object> one(String sql,Object... args){var result=rows(sql,args);if(result.isEmpty())throw notFound();return result.getFirst();}
    public List<Map<String,Object>> rows(String sql,Object... args) {
        return db.query(sql,(rs,n)->{
            var row=new LinkedHashMap<String,Object>();var meta=rs.getMetaData();
            for(int i=1;i<=meta.getColumnCount();i++){
                String[] parts=meta.getColumnLabel(i).toLowerCase(Locale.ROOT).split("_");String key=parts[0];
                for(int k=1;k<parts.length;k++)key+=Character.toUpperCase(parts[k].charAt(0))+parts[k].substring(1);
                Object value=rs.getObject(i);
                if(value instanceof java.sql.Clob)value=rs.getString(i);
                row.put(key,value);
            }
            return row;
        },args);
    }
    private long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();
        db.update(c->{var ps=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private long numberOrZero(Object value){return value instanceof Number n?n.longValue():0;}
    private void require(String s,int max,String label){if(s==null||s.isBlank()||s.length()>max)throw bad(label+"不能为空且不能超过 "+max+" 字");}
    private KnowledgeException bad(String message){return new KnowledgeException(400,"INVALID_INPUT",message);}
    private KnowledgeException conflict(String message){return new KnowledgeException(409,"CONFLICT",message);}
    private KnowledgeException notFound(){return new KnowledgeException(404,"NOT_FOUND","知识库或文档不存在");}
}
