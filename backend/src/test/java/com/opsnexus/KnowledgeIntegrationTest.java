package com.opsnexus;

import com.opsnexus.ingestion.DashScopeEmbedding;
import com.opsnexus.ingestion.DocumentParser;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:knowledge-test;DB_CLOSE_DELAY=-1","ops.seed-samples=false"})
@AutoConfigureMockMvc
class KnowledgeIntegrationTest {
    static Path data;
    static { try { data=Files.createTempDirectory("ops-knowledge-test-"); } catch(Exception e){throw new RuntimeException(e);} }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("ops.data-dir",()->data.toString());}
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired DocumentParser parser;
    @MockitoBean DashScopeEmbedding embedding;
    @BeforeEach void setup(){
        when(embedding.configured()).thenReturn(true);
        when(embedding.embed(any(org.springframework.ai.document.Document.class))).thenReturn(new float[]{1,0,0});
    }
    SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin(){return SecurityMockMvcRequestPostProcessors.jwt().jwt(j->j.subject("1").claim("role","ADMIN")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));}
    SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor user(){return SecurityMockMvcRequestPostProcessors.jwt().jwt(j->j.subject("2").claim("role","USER")).authorities(new SimpleGrantedAuthority("ROLE_USER"));}
    long base()throws Exception{
        var res=mvc.perform(post("/api/knowledge-bases").with(admin()).contentType("application/json").content(json.writeValueAsString(Map.of("name","测试-"+UUID.randomUUID(),"description","test")))).andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).at("/data/id").asLong();
    }
    long upload(long kb,Long doc,String version,String text)throws Exception{
        var req=multipart("/api/knowledge-bases/"+kb+"/documents").file(new MockMultipartFile("file","test.md","text/markdown",text.getBytes(StandardCharsets.UTF_8))).param("title","测试部署手册").param("versionNo",version).with(admin());
        if(doc!=null)req.param("documentId",doc.toString());
        var res=mvc.perform(req).andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).at("/data/versionId").asLong();
    }
    void ready(long id)throws Exception{
        mvc.perform(post("/api/document-versions/"+id+"/process").with(admin())).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).until(()->"READY".equals(db.queryForObject("SELECT process_status FROM document_version WHERE id=?",String.class,id)));
    }
    @Test void lifecycleVersionReplacementAndDraftAccess()throws Exception{
        long kb=base(),v1=upload(kb,null,"v1","Redis pool capacity eight.");
        long doc=db.queryForObject("SELECT document_id FROM document_version WHERE id=?",Long.class,v1);
        mvc.perform(get("/api/document-versions/"+v1+"/chunks").with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].content").value("Redis pool capacity eight."));
        mvc.perform(get("/api/document-versions/"+v1+"/download").with(user())).andExpect(status().isNotFound());
        mvc.perform(post("/api/document-versions/"+v1+"/publish").with(admin())).andExpect(status().isConflict());
        ready(v1);
        mvc.perform(post("/api/document-versions/"+v1+"/publish").with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/document-versions/"+v1+"/chunks").with(user())).andExpect(status().isOk());
        long v2=upload(kb,doc,"v2","Redis pool capacity sixteen.");
        ready(v2);
        mvc.perform(post("/api/document-versions/"+v2+"/publish").with(admin())).andExpect(status().isOk());
        assertEquals("ARCHIVED",db.queryForObject("SELECT publish_status FROM document_version WHERE id=?",String.class,v1));
        mvc.perform(get("/api/document-versions/"+v1+"/chunks").with(user())).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/"+doc+"/versions").with(user())).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(v2));
        mvc.perform(delete("/api/document-versions/"+v2).with(admin())).andExpect(status().isConflict());
        assertTrue(Files.size(data.resolve("vectors/store.json"))>0);
    }
    @Test void permissionsDuplicatesAndDeletion()throws Exception{
        long kb=base(),v=upload(kb,null,"v1","Unique knowledge content.");
        mvc.perform(post("/api/knowledge-bases").with(user()).contentType("application/json").content("{\"name\":\"bad\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/document-versions/"+v+"/process").with(user())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/knowledge-bases/"+kb+"/documents").file(new MockMultipartFile("file","test.txt","text/plain","Unique knowledge content.".getBytes())).param("title","重复").param("versionNo","v2").with(admin())).andExpect(status().isConflict());
        mvc.perform(delete("/api/document-versions/"+v).with(user())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/document-versions/"+v).with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/document-versions/"+v+"/chunks").with(admin())).andExpect(status().isNotFound());
    }
    @Test void missingModelAndFailureAreHonestAndRetryable()throws Exception{
        long kb=base(),v=upload(kb,null,"v1","Failure retry sample.");
        when(embedding.configured()).thenReturn(false);
        mvc.perform(post("/api/document-versions/"+v+"/process").with(admin())).andExpect(status().isServiceUnavailable());
        assertEquals("PARSED",db.queryForObject("SELECT process_status FROM document_version WHERE id=?",String.class,v));
        when(embedding.configured()).thenReturn(true);
        when(embedding.embed(any(org.springframework.ai.document.Document.class))).thenThrow(new IllegalStateException("测试服务超时"));
        mvc.perform(post("/api/document-versions/"+v+"/process").with(admin())).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).until(()->"FAILED".equals(db.queryForObject("SELECT process_status FROM document_version WHERE id=?",String.class,v)));
        when(embedding.embed(any(org.springframework.ai.document.Document.class))).thenReturn(new float[]{1,0,0});
        ready(v);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM document_chunk_ref WHERE version_id=?",Integer.class,v));
    }
    @Test void analyticsEndpointsRequireAdministratorRole()throws Exception{
        mvc.perform(post("/api/admin/analytics/query").with(user()).contentType("application/json").content("{\"question\":\"查询服务版本\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/analytics/audits").with(user())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/analytics/audits")).andExpect(status().isUnauthorized());
    }
    @Test void versionCompareApiKeepsFactsWhenSemanticProviderIsUnavailable()throws Exception{
        long kb=base(),oldVersion=upload(kb,null,"v1","timeout is 5 seconds");
        long doc=db.queryForObject("SELECT document_id FROM document_version WHERE id=?",Long.class,oldVersion);
        long newVersion=upload(kb,doc,"v2","timeout is 30 seconds");
        mvc.perform(post("/api/admin/document-version-compare").with(user()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("oldVersionId",oldVersion,"newVersionId",newVersion)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/document-version-compare").with(admin()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("oldVersionId",oldVersion,"newVersionId",newVersion))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.changedBlocks[0].id").value("CHANGE-001"))
            .andExpect(jsonPath("$.data.changes[0].oldText").value("timeout is 5 seconds"))
            .andExpect(jsonPath("$.data.changes[0].newText").value("timeout is 30 seconds"))
            .andExpect(jsonPath("$.data.semanticComparison.analysisStatus").value("SEMANTIC_ANALYSIS_UNAVAILABLE"));
    }
    @Test void parsesFourFormatsAndPreservesPdfPages()throws Exception{
        assertEquals("hello",parser.parse("hello".getBytes(),"MD").getFirst().content());
        assertEquals("中文正文",parser.parse("中文正文".getBytes(StandardCharsets.UTF_8),"TXT").getFirst().content());
        try(var doc=new XWPFDocument();var bytes=new ByteArrayOutputStream()){
            doc.createParagraph().createRun().setText("Deployment document");
            doc.write(bytes);
            assertTrue(parser.parse(bytes.toByteArray(),"DOCX").getFirst().content().contains("Deployment document"));
        }
        try(var pdf=new PDDocument();var bytes=new ByteArrayOutputStream()){
            for(int i=0;i<2;i++){
                var page=new PDPage();pdf.addPage(page);
                try(var stream=new PDPageContentStream(pdf,page)){
                    stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    stream.newLineAtOffset(50,700);stream.showText("Page "+(i+1));stream.endText();
                }
            }
            pdf.save(bytes);var chunks=parser.parse(bytes.toByteArray(),"PDF");
            assertEquals(1,chunks.get(0).page());assertEquals(2,chunks.get(1).page());
        }
        assertThrows(com.opsnexus.knowledge.KnowledgeException.class,()->parser.parse(new byte[]{0,1,2},"PDF"));
        assertThrows(com.opsnexus.knowledge.KnowledgeException.class,()->parser.parse("  ".getBytes(),"TXT"));
    }
}
