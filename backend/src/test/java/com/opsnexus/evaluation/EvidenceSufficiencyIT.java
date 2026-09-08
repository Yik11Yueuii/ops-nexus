package com.opsnexus.evaluation;
import com.fasterxml.jackson.databind.*;import com.opsnexus.ingestion.*;import java.io.*;import java.nio.charset.*;import java.nio.file.*;import java.time.*;import java.util.*;import org.junit.jupiter.api.*;import org.springframework.ai.document.Document;import org.springframework.ai.vectorstore.*;
/** Opt-in deterministic evidence-sufficiency experiment; it never changes production policy. */
class EvidenceSufficiencyIT {
 static final ObjectMapper J=new ObjectMapper();
 @Test void evaluatesDeterministicSufficiencyRule() throws Exception {
  String key=Objects.requireNonNullElse(System.getenv("DASHSCOPE_API_KEY"),"");Assumptions.assumeTrue(!key.isBlank(),"SKIPPED: DASHSCOPE_API_KEY unavailable");
  var store=SimpleVectorStore.builder(new DashScopeEmbedding(key,"https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding","text-embedding-v2",J)).build();var base=new VectorSensitivityIT();var docs=VectorSensitivityIT.DOCS;store.add(docs.stream().flatMap(d->base.chunks(d).stream()).toList());var cases=base.cases();var corpus=new HashMap<String,String>();for(var d:docs)if(d.current())corpus.put(d.title(),base.text(d.res()));
  int tp=0,tn=0,fp=0,fn=0;var details=new ArrayList<Map<String,Object>>();for(var c:cases){var raw=store.similaritySearch(SearchRequest.builder().query(c.q()).topK(3).similarityThreshold(.25).build());var hits=raw.stream().filter(x->Boolean.TRUE.equals(x.getMetadata().get("current"))).toList();double top=hits.isEmpty()?0:hits.getFirst().getScore();double coverage=hits.isEmpty()?0:coverage(c.q(),String.join(" ",hits.stream().map(x->corpus.get(x.getMetadata().get("title").toString())).toList()));boolean sufficient=!hits.isEmpty()&&coverage>=.20&&top>=.25;boolean expected=c.answer();if(sufficient&&expected)tp++;else if(!sufficient&&!expected)tn++;else if(sufficient)fp++;else fn++;details.add(Map.of("id",c.id(),"expectedSufficient",expected,"ruleSufficient",sufficient,"top1Score",top,"keywordCoverage",coverage,"hits",hits.stream().map(x->Map.of("document",x.getMetadata().get("title"),"score",x.getScore())).toList()));}
  double precision=tp+fp==0?0:tp/(double)(tp+fp),recall=tp+fn==0?0:tp/(double)(tp+fn),f1=precision+recall==0?0:2*precision*recall/(precision+recall);
  var strategyA=map("threshold",.25,"topK",3,"rule","top1>=0.25 AND Chinese-bigram evidence coverage>=0.20","positiveAnswerRecall",recall,"refusalRecall",tn/5d,"falseAnswerRate",fp/5d,"falseRefusalRate",fn/27d,"accuracy",(tp+tn)/32d,"precision",precision,"recall",recall,"f1",f1,"confusion",Map.of("tp",tp,"tn",tn,"fp",fp,"fn",fn));
  var report=map("kind","evidence-sufficiency","executedAt",Instant.now().toString(),"strategyA",strategyA,"strategyB",Map.of("status","SKIPPED","reason","DEEPSEEK_API_KEY not supplied"),"details",details);
  Path p=Path.of("target","rag-evaluation");Files.createDirectories(p);J.writerWithDefaultPrettyPrinter().writeValue(p.resolve("evidence-sufficiency-report.json").toFile(),report);System.out.println(report.get("strategyA"));
 }
 static Map<String,Object> map(Object... pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
 static double coverage(String q,String e){Set<String> a=grams(q);Set<String> b=grams(e);return a.isEmpty()?0:a.stream().filter(b::contains).count()/(double)a.size();}static Set<String> grams(String s){var x=new HashSet<String>();var a=s.replaceAll("\\s+","").codePoints().filter(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN).toArray();for(int i=0;i+1<a.length;i++)x.add(new String(a,i,2));return x;}
}


