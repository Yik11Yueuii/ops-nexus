package com.opsnexus.knowledge;
import java.util.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;
@Service public class VersionCompareService{
 public record Change(String type,Integer oldLine,Integer newLine,String content){} public record Result(long documentId,String title,String oldVersion,String newVersion,int added,int deleted,List<Change> changes,List<String> riskHints){}
 private final JdbcTemplate db;public VersionCompareService(JdbcTemplate db){this.db=db;}
 public Result compare(long oldId,long newId){
  if(oldId==newId)throw new KnowledgeException(400,"INVALID_INPUT","请选择两个不同版本");
  var rows=db.queryForList("SELECT v.id,v.document_id,v.version_no,d.title FROM document_version v JOIN knowledge_document d ON d.id=v.document_id WHERE v.id IN (?,?)",oldId,newId);
  if(rows.size()!=2)throw new KnowledgeException(404,"NOT_FOUND","文档版本不存在");long doc=((Number)rows.getFirst().get("DOCUMENT_ID")).longValue();
  if(rows.stream().anyMatch(r->((Number)r.get("DOCUMENT_ID")).longValue()!=doc))throw new KnowledgeException(400,"VERSION_MISMATCH","只能比较同一文档的版本");
  var a=lines(oldId);var b=lines(newId);if(a.size()>1200||b.size()>1200)throw new KnowledgeException(413,"COMPARE_TOO_LARGE","首版最多比较 1200 行");
  int[][] lcs=new int[a.size()+1][b.size()+1];for(int i=a.size()-1;i>=0;i--)for(int j=b.size()-1;j>=0;j--)lcs[i][j]=a.get(i).equals(b.get(j))?lcs[i+1][j+1]+1:Math.max(lcs[i+1][j],lcs[i][j+1]);
  var changes=new ArrayList<Change>();int i=0,j=0,add=0,del=0;while(i<a.size()||j<b.size()){if(i<a.size()&&j<b.size()&&a.get(i).equals(b.get(j))){i++;j++;}else if(j<b.size()&&(i==a.size()||lcs[i][j+1]>=lcs[i+1][j])){changes.add(new Change("ADDED",null,j+1,b.get(j++)));add++;}else{changes.add(new Change("DELETED",i+1,null,a.get(i++)));del++;}}
  var hints=new LinkedHashSet<String>();String joined=changes.stream().map(Change::content).reduce("",(x,y)->x+" "+y);if(joined.matches(".*(端口|连接池|超时|线程|内存|CPU|配置).*"))hints.add("检测到运行参数变化：上线前应核对环境配置并进行容量验证。");if(joined.matches(".*(数据库|Redis|消息|缓存).*"))hints.add("检测到外部依赖变化：建议验证连接、降级和回滚路径。");if(joined.matches(".*(发布|灰度|回滚|审批).*"))hints.add("检测到发布流程变化：建议复核灰度范围、观察指标和回滚条件。");if(hints.isEmpty()&&!changes.isEmpty())hints.add("存在正文变化：建议由文档负责人确认业务影响。");if(changes.isEmpty())hints.add("未检测到正文行级差异。");
  return new Result(doc,rows.getFirst().get("TITLE").toString(),version(rows,oldId),version(rows,newId),add,del,changes,List.copyOf(hints));
 }
 private String version(List<Map<String,Object>> r,long id){return r.stream().filter(x->((Number)x.get("ID")).longValue()==id).findFirst().orElseThrow().get("VERSION_NO").toString();}
 private List<String> lines(long id){String text=db.queryForList("SELECT content FROM document_chunk_ref WHERE version_id=? ORDER BY chunk_index",id).stream().map(r->r.get("CONTENT").toString()).reduce("",(x,y)->x+"\n"+y);return Arrays.stream(text.split("\\R")).map(String::stripTrailing).filter(s->!s.isBlank()).toList();}
}
