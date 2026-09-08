package com.opsnexus.bootstrap;
import com.opsnexus.knowledge.*;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component @Order(20)
public class KnowledgeSamples implements CommandLineRunner {
    private final KnowledgeService service;
    private final JdbcTemplate db;
    @Value("${ops.seed-samples:true}") private boolean enabled;
    public KnowledgeSamples(KnowledgeService service,JdbcTemplate db){this.service=service;this.db=db;}
    public void run(String... args) throws Exception {
        if(!enabled || db.queryForObject("SELECT COUNT(*) FROM app_seed WHERE seed_key='knowledge-v1'",Integer.class)>0)return;
        var found=db.queryForList("SELECT id FROM knowledge_base WHERE name='星云技术知识库'");
        long kb=found.isEmpty()?service.createBase("星云技术知识库","部署手册、排障 SOP 与发布规范。全部为虚构演示资料。",1):((Number)found.getFirst().get("ID")).longValue();
        sample(kb,null,"订单服务部署手册","v1.0","order-deploy-v1.md");
        var docs=db.queryForList("SELECT id FROM knowledge_document WHERE kb_id=? AND title='订单服务部署手册' ORDER BY id",kb);
        long doc=((Number)docs.getFirst().get("ID")).longValue();
        sample(kb,doc,"订单服务部署手册","v2.0","order-deploy-v2.md");
        sample(kb,null,"Redis 连接池耗尽排障 SOP","v1.0","redis-sop.md");
        sample(kb,null,"服务发布与回滚规范","v1.0","release-guide.md");
        db.update("INSERT INTO app_seed(seed_key) VALUES('knowledge-v1')");
    }
    private void sample(long kb,Long doc,String title,String version,String filename)throws Exception {
        try(var stream=getClass().getResourceAsStream("/samples/"+filename)){
            if(stream==null)throw new IllegalStateException("样例资料缺失");
            try {service.upload(kb,doc,title,version,stream.readAllBytes(),filename,1);}
            catch(KnowledgeException e){if(!e.code.equals("CONFLICT"))throw e;}
        }
    }
}
