package com.opsnexus.analytics;
import com.opsnexus.knowledge.KnowledgeException;
import java.util.*;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;
@Component public class SqlSafetyValidator{
 private static final Set<String> TABLES=Set.of("service_catalog","release_record","incident_record");
 private static final Set<String> COLUMNS=Set.of("id","service_name","display_name","owner_name","current_version","runtime_status","version_no","environment","status","released_at","summary","symptom","root_cause","resolution","occurred_at","resolved_at");
 private static final Set<String> FUNCTIONS=Set.of("COUNT","SUM","AVG","MIN","MAX","DATEADD","DATE_ADD");
 public String validate(String raw){
  try{
   String sql=raw==null?"":raw.replace("```sql","").replace("```","").strip();
   var statements=CCJSqlParserUtil.parseStatements(sql);if(statements.size()!=1)reject("只允许一条 SQL");
   Statement statement=statements.get(0);if(!(statement instanceof PlainSelect))reject("只允许单条 SELECT，禁止 UNION、CTE、子查询和写操作");
   PlainSelect select=(PlainSelect)statement;
   if(select.getWithItemsList()!=null&&!select.getWithItemsList().isEmpty())reject("禁止 CTE");
   if(select.getIntoTables()!=null&&!select.getIntoTables().isEmpty())reject("禁止 SELECT INTO");
   if(select.getForClause()!=null||select.getForMode()!=null)reject("禁止锁定查询");
   if(select.getJoins()!=null){if(select.getJoins().size()>2)reject("最多允许两个 JOIN");for(var j:select.getJoins())if(j.isCross()||j.getOnExpressions()==null||j.getOnExpressions().isEmpty())reject("禁止 CROSS JOIN 或无条件 JOIN");}
   var visitor=new InspectVisitor();Set<String> tables=visitor.getTables(statement);if(tables.isEmpty()||tables.stream().map(String::toLowerCase).anyMatch(t->!TABLES.contains(t)))reject("SQL 访问了非白名单表");
   if(visitor.nested)reject("禁止子查询、CTE 和集合查询");if(visitor.wildcard)reject("禁止 SELECT *，请明确选择字段");
   if(visitor.columns.stream().map(String::toLowerCase).anyMatch(c->!COLUMNS.contains(c)))reject("SQL 使用了非白名单字段");
   if(visitor.functions.stream().map(String::toUpperCase).anyMatch(f->!FUNCTIONS.contains(f)))reject("SQL 使用了非白名单函数");
   select.setLimit(new Limit().withRowCount(new net.sf.jsqlparser.expression.LongValue(100)));
   return select.toString();
  }catch(KnowledgeException e){throw e;}catch(Exception e){throw new KnowledgeException(400,"SQL_REJECTED","SQL 语法解析失败，查询未执行");}
 }
 private static void reject(String reason){throw new KnowledgeException(400,"SQL_REJECTED",reason+"，查询未执行");}
 private static class InspectVisitor extends TablesNamesFinder{
  final Set<String> columns=new HashSet<>(),functions=new HashSet<>();boolean nested,wildcard;int selects;
  @Override public void visit(Column c){columns.add(c.getColumnName());super.visit(c);}
  @Override public void visit(Function f){if(f.getName()!=null)functions.add(f.getName());super.visit(f);}
  @Override public void visit(AllColumns a){wildcard=true;super.visit(a);}
  @Override public void visit(AllTableColumns a){wildcard=true;super.visit(a);}
  @Override public void visit(PlainSelect s){if(++selects>1)nested=true;super.visit(s);}
  @Override public void visit(ParenthesedSelect s){nested=true;super.visit(s);}
  @Override public void visit(SetOperationList s){nested=true;super.visit(s);}
  @Override public void visit(WithItem s){nested=true;super.visit(s);}
 }
}
