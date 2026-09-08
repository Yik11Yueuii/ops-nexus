package com.opsnexus;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class InfrastructureTest {
 @TempDir Path temp;
 @Test void fileDatabaseSurvivesReopenAndReadOnlyAccountCannotWrite() throws Exception {
  String url="jdbc:h2:file:"+temp.resolve("db").toAbsolutePath().toString().replace('\\','/');
  try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
   s.execute("CREATE TABLE service_catalog(id INT PRIMARY KEY, name VARCHAR(40))");
   s.execute("CREATE TABLE private_data(secret VARCHAR(30))");
   s.execute("INSERT INTO service_catalog VALUES(1,'order-service')");
   s.execute("CREATE USER analyst PASSWORD 'test-only'");
   s.execute("GRANT SELECT ON service_catalog TO analyst");
  }
  try(var c=DriverManager.getConnection(url,"analyst","test-only");var s=c.createStatement()){
   try(var rows=s.executeQuery("SELECT name FROM service_catalog")){assertTrue(rows.next());assertEquals("order-service",rows.getString(1));}
   assertThrows(SQLException.class,()->s.execute("DELETE FROM service_catalog"));
   assertThrows(SQLException.class,()->s.executeQuery("SELECT * FROM private_data"));
   assertThrows(SQLException.class,()->s.execute("CREATE TABLE bad(id INT)"));
  }
 }
 @Test void parserDistinguishesMultipleStatementsAndWrites() throws Exception {
  assertInstanceOf(Select.class,CCJSqlParserUtil.parse("SELECT service_name FROM service_catalog"));
  assertEquals(2,CCJSqlParserUtil.parseStatements("SELECT 1; DELETE FROM service_catalog").size());
  assertFalse(CCJSqlParserUtil.parse("DELETE FROM service_catalog") instanceof Select);
 }
 @Test void vectorsSaveAndLoadWithoutCloudModel() {
  EmbeddingModel embedding=mock(EmbeddingModel.class);
  when(embedding.embed(any(Document.class))).thenReturn(new float[]{1f,0f,0f});
  when(embedding.embed(anyString())).thenReturn(new float[]{1f,0f,0f});
  var first=SimpleVectorStore.builder(embedding).build();
  first.add(List.of(new Document("Redis connection SOP")));
  var snapshot=temp.resolve("vectors.json").toFile(); first.save(snapshot);
  var second=SimpleVectorStore.builder(embedding).build(); second.load(snapshot);
  assertEquals("Redis connection SOP",second.similaritySearch("Redis").getFirst().getText());
 }
}
