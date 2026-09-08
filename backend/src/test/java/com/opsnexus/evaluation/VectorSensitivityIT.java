package com.opsnexus.evaluation;

import com.fasterxml.jackson.databind.*;
import com.opsnexus.ingestion.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;

/** Opt-in diagnostic experiment; production settings remain untouched. */
class VectorSensitivityIT {
 private static final ObjectMapper JSON=new ObjectMapper();
 record C(String id,String q,String doc,String ver,boolean answer,boolean refuse){}
 record D(String id,String title,String ver,String res,boolean current){}
 static final List<D> DOCS=List.of(new D("deploy-v2","订单服务部署手册","v2.0","/samples/order-deploy-v2.md",true),new D("redis","Redis 连接池耗尽排障 SOP","v1.0","/samples/redis-sop.md",true),new D("release","服务发布与回滚规范","v1.0","/samples/release-guide.md",true),new D("deploy-v1","订单服务部署手册","v1.0","/samples/order-deploy-v1.md",false));
 @Test void measuresThresholdAndTopKSensitivity() throws Exception {
  String key=Objects.requireNonNullElse(System.getenv("DASHSCOPE_API_KEY"),""); Assumptions.assumeTrue(!key.isBlank(),"SKIPPED: DASHSCOPE_API_KEY unavailable");
  String endpoint=Objects.requireNonNullElse(System.getenv("EMBEDDING_URL"),"https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding"); String model=Objects.requireNonNullElse(System.getenv("EMBEDDING_MODEL"),"text-embedding-v2");
  var embedding=new DashScopeEmbedding(key,endpoint,model,JSON); var store=SimpleVectorStore.builder(embedding).build(); store.add(DOCS.stream().flatMap(d->chunks(d).stream()).toList()); var cases=cases();
  var thresholdRows=new ArrayList<Map<String,Object>>(); for(double t:new double[]{.20,.25,.30,.35,.40,.45}) thresholdRows.add(row(cases,searchAll(store,cases,t,8),t,8));
  var topKRows=new ArrayList<Map<String,Object>>(); for(int k:new int[]{1,3,5,10}) topKRows.add(row(cases,searchAll(store,cases,.20,k),.20,k));
  var baseline=searchAll(store,cases,.45,8); var report=new LinkedHashMap<String,Object>(); report.put("kind","vector-sensitivity");report.put("executedAt",Instant.now().toString());report.put("datasetVersion","v1");report.put("embeddingProvider","DashScope");report.put("embeddingModel",model);report.put("vectorStore","SimpleVectorStore");report.put("thresholdExperiments",thresholdRows);report.put("topKExperiments",topKRows);report.put("baselineDetails",baseline);report.put("failureClassification",classify(baseline));
  Path out=Path.of("target","rag-evaluation");Files.createDirectories(out);JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("vector-sensitivity-report.json").toFile(),report);
  System.out.println("Vector sensitivity completed: "+thresholdRows);
 }
 List<Map<String,Object>> searchAll(SimpleVectorStore s,List<C> cs,double threshold,int k){var out=new ArrayList<Map<String,Object>>();for(var c:cs){long start=System.nanoTime();var raw=s.similaritySearch(SearchRequest.builder().query(c.q()).topK(k).similarityThreshold(threshold).build());long elapsed=System.nanoTime()-start;var hits=raw.stream().filter(x->Boolean.TRUE.equals(x.getMetadata().get("current"))).map(x->Map.<String,Object>of("document",x.getMetadata().get("title"),"version",x.getMetadata().get("version"),"score",x.getScore())).toList();out.add(new LinkedHashMap<>(Map.of("id",c.id(),"question",c.q(),"expectedDocument",c.doc(),"expectedVersion",c.ver(),"shouldAnswer",c.answer(),"shouldRefuse",c.refuse(),"hits",hits,"rawCount",raw.size(),"latencyNanos",elapsed)));}return out;}
 Map<String,Object> row(List<C> cs,List<Map<String,Object>> rows,double threshold,int k){var rs=new ArrayList<RagEvaluationMetrics.Result>();int noHit=0,fp=0;for(int i=0;i<cs.size();i++){var c=cs.get(i);var row=rows.get(i);var hs=((List<Map<String,Object>>)row.get("hits")).stream().map(h->new RagEvaluationMetrics.Hit(h.get("document").toString(),h.get("version").toString())).toList();if(hs.isEmpty())noHit++;if(c.refuse()&&!hs.isEmpty())fp++;rs.add(new RagEvaluationMetrics.Result(c.id(),c.answer(),c.refuse(),c.doc(),c.ver(),hs,(long)row.get("latencyNanos")));}return new LinkedHashMap<>(Map.of("threshold",threshold,"topK",k,"metrics",RagEvaluationMetrics.summarize(rs),"noHitCount",noHit,"falsePositiveCount",fp));}
 List<Map<String,Object>> classify(List<Map<String,Object>> rows){var out=new ArrayList<Map<String,Object>>();for(var r:rows){boolean answer=(boolean)r.get("shouldAnswer");var hs=(List<Map<String,Object>>)r.get("hits");String cat;if(!answer)cat=hs.isEmpty()?"PASS_REFUSAL":"REFUSAL_FALSE_POSITIVE";else if(hs.isEmpty())cat="NO_HIT";else if(!hs.getFirst().get("document").equals(r.get("expectedDocument")))cat="WRONG_DOC_HIGHER_SCORE";else cat="PASS";if(!"PASS".equals(cat)&&!"PASS_REFUSAL".equals(cat))out.add(Map.of("id",r.get("id"),"category",cat,"actual",hs));}return out;}
 List<Document> chunks(D d){var parts=new DocumentParser().parse(text(d.res()).getBytes(StandardCharsets.UTF_8),"MD");return parts.stream().map(p->Document.builder().id(d.id()+"-"+p.index()).text(p.content()).metadata(Map.of("title",d.title(),"version",d.ver(),"current",d.current())).build()).toList();}
 List<C> cases() throws Exception{try(var in=getClass().getResourceAsStream("/evaluation/rag-evaluation.json")){var a=new ArrayList<C>();for(var r:JSON.readTree(Objects.requireNonNull(in)).path("cases"))a.add(new C(r.path("id").asText(),r.path("question").asText(),r.path("expectedDocument").asText(),r.path("expectedVersion").asText(),r.path("shouldAnswer").asBoolean(),r.path("shouldRefuse").asBoolean()));return a;}}
 String text(String r){try(var in=getClass().getResourceAsStream(r)){return new String(Objects.requireNonNull(in).readAllBytes());}catch(IOException e){throw new UncheckedIOException(e);}}
}