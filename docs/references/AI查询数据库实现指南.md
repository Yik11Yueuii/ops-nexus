# AI 查询数据库实现指南（毕设复用版）

> 来源：Spring AI 实训案例 16「数据库查询 Agent」
> 核心思想：**让不懂 SQL 的人，用大白话查数据库**（NL2SQL）
> 技术栈：Spring Boot 3.4 + Spring AI 1.0 + 通义千问 qwen-plus + H2 内存库 + JPA

---

## 一、原理一句话

```
用户自然语言提问 → AI 现场生成 SQL → 工具执行（过 4 道安全锁）→ AI 用人话总结结果
```

## 二、调用流程图

```
用户: "技术部工资最高的员工是谁？"
        │
        ▼
┌─────────────────────────────────┐
│ DatabaseAgentController         │
│  system("你是一个数据库查询助手…表结构…") │  ← 人设告诉 AI 表长什么样
│  user(question)                 │
│  .tools(databaseQueryTool)      │  ← 把查库工具交给 AI
└─────────────────────────────────┘
        │ AI 自主决定调用工具
        ▼
┌─────────────────────────────────┐
│ DatabaseQueryTool.executeQuery  │
│  ① 只允许 SELECT                │  ← 第1道锁：防破坏
│  ② 禁危险关键字(DELETE/DROP等)    │  ← 第2道锁：黑名单兜底
│  ③ 禁分号(防SQL注入)             │  ← 第3道锁
│  ④ 自动加 LIMIT 100             │  ← 第4道锁：防资源耗尽
│  → 执行查询，返回文本结果          │
└─────────────────────────────────┘
        │
        ▼
AI 收到结果 → 用人话总结 → 返回给用户
```

---

## 三、需要加的依赖（pom.xml）

```xml
<!-- Spring AI Alibaba（通义千问） -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
</dependency>

<!-- JPA：根据实体类自动建表 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- H2 内存数据库（不用安装，重启数据清空） -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

> 若 Spring AI 依赖需要显式版本号，参考项目根 pom 中统一管理的 `1.0.0` 版本。

## 四、需要加的配置（application.yml）

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:<your-dashscope-key>}   # 阿里云百炼平台申请
      chat:
        options:
          model: qwen-plus       # 通义千问
          temperature: 0.3       # 生成SQL要精确，温度越低越准（重要！）
  datasource:
    url: jdbc:h2:mem:aidb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: update           # 启动时按实体类自动建表
    show-sql: true
  h2:
    console:
      enabled: true              # 访问 http://localhost:8080/h2-console 可视化看表
      path: /h2-console
```

**注意**：SQL 生成不需要创意，`temperature` 一定要调低（0.1~0.3），这是减少 AI 写错 SQL 的重要一招。

---

## 五、完整代码（直接复制）

### 1. 实体类：员工表

`model/Employee.java`

```java
package com.xxx.demo.model;

import jakarta.persistence.*;

@Entity
@Table(name = "emp")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;        // 员工姓名
    private String department;  // 所属部门（技术部、市场部、人事部、财务部）
    private Double salary;      // 工资
    private String position;    // 职位
    private Integer hireYear;   // 入职年份

    public Employee() {}

    // getter / setter（省略，IDE 自动生成）
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public Double getSalary() { return salary; }
    public void setSalary(Double salary) { this.salary = salary; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public Integer getHireYear() { return hireYear; }
    public void setHireYear(Integer hireYear) { this.hireYear = hireYear; }
}
```

### 2. 实体类：部门表（课堂扩展）

`model/Department.java`

```java
package com.xxx.demo.model;

import jakarta.persistence.*;

@Entity
@Table(name = "dept")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;       // 部门名称（与 emp.department 对应）
    private String manager;    // 部门负责人
    private String location;   // 办公地点

    public Department() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getManager() { return manager; }
    public void setManager(String manager) { this.manager = manager; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
```

### 3. 数据初始化（建表 + 插入数据）

`config/DataInitializer.java`

```java
package com.xxx.demo.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时建表并插入测试数据
 * 用 JdbcTemplate 显式 CREATE TABLE，不依赖 JPA 建表时机，简单可靠
 */
@Component
public class DataInitializer {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        initEmployees();
        initDepartments();
    }

    private void initEmployees() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS emp (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "name VARCHAR(50), department VARCHAR(50), " +
                "salary INT, position VARCHAR(50), hire_year INT)");
        jdbcTemplate.execute("DELETE FROM emp");
        jdbcTemplate.execute("INSERT INTO emp (name, department, salary, position, hire_year) VALUES " +
                "('张伟','技术部',18000,'高级工程师',2019)," +
                "('王芳','技术部',15000,'工程师',2020)," +
                "('李强','技术部',16000,'工程师',2021)," +
                "('赵敏','技术部',19000,'技术总监',2018)," +
                "('刘洋','市场部',12000,'市场专员',2020)," +
                "('陈静','市场部',13000,'市场经理',2019)," +
                "('杨光','人事部',11000,'人事专员',2021)," +
                "('周梅','人事部',12500,'人事经理',2018)," +
                "('吴磊','财务部',14000,'财务主管',2019)," +
                "('孙琳','财务部',13500,'会计',2022)");
    }

    private void initDepartments() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS dept (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "name VARCHAR(50), manager VARCHAR(50), location VARCHAR(50))");
        jdbcTemplate.execute("DELETE FROM dept");
        jdbcTemplate.execute("INSERT INTO dept (name, manager, location) VALUES " +
                "('技术部','赵敏','A座3层')," +
                "('市场部','陈静','B座2层')," +
                "('人事部','周梅','C座1层')," +
                "('财务部','吴磊','C座2层')");
    }
}
```

> 备选方案：也可以不用 JdbcTemplate，改用 JPA Repository `save()` 插入。关键点：**JPA 用 `ddl-auto: update` 会先按实体类建表，再执行插入**（可加 `@DependsOn("entityManagerFactory")` 保证顺序）。

### 4. 数据库查询工具（核心，带 4 道安全锁）

`tool/DatabaseQueryTool.java`

```java
package com.xxx.demo.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AI 根据用户问题生成 SQL，通过本工具执行
 * 4 道安全锁：只允许 SELECT / 禁危险关键字 / 禁分号 / 自动 LIMIT
 * 【重要】@Tool 描述里写清楚表结构，AI 才知道怎么查
 */
@Component
public class DatabaseQueryTool {

    private final JdbcTemplate jdbcTemplate;
    private static final int MAX_RESULTS = 100;

    public DatabaseQueryTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "执行SQL查询语句，可查询员工表(emp)和部门表(dept)的数据。"
            + "表结构：emp(id主键, name姓名, department部门, salary工资, position职位, hire_year入职年份)；"
            + "dept(id主键, name部门名称, manager负责人, location办公地点)，两张表通过部门名称关联(emp.department = dept.name)。"
            + "只允许SELECT查询。可用部门：技术部、市场部、人事部、财务部。")
    public String executeQuery(
            @ToolParam(description = "要执行的SELECT SQL语句，只能查不能改") String sql
    ) {
        System.out.println("========== 【AI生成的SQL】 ==========");
        System.out.println("SQL: " + sql);
        System.out.println("====================================");

        String upperSql = sql.trim().toUpperCase();

        // 第1道锁：只允许 SELECT
        if (!upperSql.startsWith("SELECT")) {
            return "安全拒绝：只允许SELECT查询语句，不允许修改或删除数据！";
        }

        // 第2道锁：危险关键字黑名单
        String[] dangerousKeywords = {"DELETE", "DROP", "UPDATE", "INSERT",
                "ALTER", "TRUNCATE", "CREATE", "GRANT", "REVOKE"};
        for (String keyword : dangerousKeywords) {
            if (upperSql.contains(keyword)) {
                return "安全拒绝：SQL中包含危险关键字 " + keyword + "，不允许执行！";
            }
        }

        // 第3道锁：分号检查（防 SQL 注入拼接）
        String trimmedSql = sql.trim();
        if (trimmedSql.endsWith(";")) {
            trimmedSql = trimmedSql.substring(0, trimmedSql.length() - 1);
        }
        if (trimmedSql.contains(";")) {
            return "安全拒绝：SQL中包含分号，可能存在SQL注入风险！";
        }

        // 第4道锁：自动加 LIMIT，防资源耗尽
        if (!upperSql.contains("LIMIT")) {
            trimmedSql = trimmedSql + " LIMIT " + MAX_RESULTS;
        }

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(trimmedSql);
            if (results.isEmpty()) {
                return "查询完成，但没有找到匹配的数据。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("查询成功，共返回 ").append(results.size()).append(" 条记录：\n");
            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> row = results.get(i);
                sb.append("第").append(i + 1).append("条：");
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    sb.append(entry.getKey()).append("=").append(entry.getValue()).append("，");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "SQL执行失败：" + e.getMessage() + "。请检查SQL语法是否正确。";
        }
    }
}
```

### 5. 控制器（人设 + 工具调用）

`controller/DatabaseAgentController.java`

```java
package com.xxx.demo.controller;

import com.xxx.demo.tool.DatabaseQueryTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatabaseAgentController {

    private final ChatClient chatClient;
    private final DatabaseQueryTool databaseQueryTool;

    public DatabaseAgentController(ChatClient chatClient, DatabaseQueryTool databaseQueryTool) {
        this.chatClient = chatClient;
        this.databaseQueryTool = databaseQueryTool;
    }

    @GetMapping(value = "/ai/db-query", produces = "text/plain; charset=UTF-8")
    public String queryDatabase(@RequestParam(defaultValue = "技术部工资最高的员工是谁") String question) {

        // ===== 人设：把表结构、字段、规则告诉 AI，这是生成 SQL 质量的命根子 =====
        String systemPrompt = """
                你是一个数据库查询助手。你可以通过 executeQuery 工具执行SQL查询。

                数据库有两张表：

                【员工表 emp】
                - id: 主键
                - name: 员工姓名
                - department: 部门（技术部、市场部、人事部、财务部）
                - salary: 工资
                - position: 职位
                - hire_year: 入职年份

                【部门表 dept】
                - id: 主键
                - name: 部门名称（技术部、市场部、人事部、财务部）
                - manager: 部门负责人
                - location: 办公地点

                【表关联】两张表通过部门名称关联：emp.department = dept.name
                例如查"市场部在哪里办公"：SELECT location FROM dept WHERE name = '市场部'
                查"各部门员工人数"：SELECT department, COUNT(*) FROM emp GROUP BY department

                规则：
                1. 只能生成SELECT语句
                2. 不要生成DELETE、UPDATE、INSERT等修改数据的语句
                3. 查询结果要用人话总结，不要直接输出原始SQL结果
                """;

        return chatClient
                .prompt()
                .system(systemPrompt)
                .user(question)
                .tools(databaseQueryTool)
                .call()
                .content();
    }
}
```

### 6. 启动类（标准 Spring Boot）

```java
package com.xxx.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

---

## 六、换成你自己的表（毕设适配，3 步）

1. **改实体类**：把 `Employee`/`Department` 的字段改成你毕设业务的表（如：学生表、订单表、商品表），改 `@Table(name="xxx")` 表名。
2. **改初始化数据**：`DataInitializer` 里换成你的 INSERT 语句。
3. **改两处描述（最重要）**：
   - `DatabaseQueryTool` 的 `@Tool(description=...)` —— 把新表结构写进去
   - `DatabaseAgentController` 的 `systemPrompt` —— 把新表结构写进去，附上 2~3 个查询示例

> 人设里**必须**写明：表名、每个字段名（下划线风格）、字段含义、可选值。AI 生成 SQL 的质量 90% 取决于这两处描述写得多清楚。

---

## 七、测试方法

启动应用后访问（浏览器直接打开，中文会自动编码）：

```
http://localhost:8080/ai/db-query?question=技术部工资最高的员工是谁
http://localhost:8080/ai/db-query?question=财务部负责人是谁，在哪里办公
http://localhost:8080/ai/db-query?question=公司一共有几个部门
http://localhost:8080/ai/db-query?question=各部门平均工资
http://localhost:8080/ai/db-query?question=删除所有员工数据   ← 应被安全拦截
```

观察控制台日志中的 **AI生成的SQL**，确认 AI 确实自己写了 SQL。

---

## 八、常见坑 & 进阶优化

| 问题 | 解决方案 |
|---|---|
| `Table "EMP" not found` | JPA 还没建表就插入 → 用本文的 `CREATE TABLE IF NOT EXISTS` 方式，或在初始化类加 `@DependsOn("entityManagerFactory")` |
| `Column "HIRE_YEAR" not found` | Java 字段 `hireYear` 被映射为 `hire_year`，但 AI 写的 SQL 用了 `hireYear` → 人设里写明下划线字段名 |
| AI 不调用工具、自己编数据 | 人设里写"必须使用 executeQuery 工具"；`temperature` 调低到 0.3 以下 |
| AI 写的 SQL 答非所问 | 降低温度 + 人设里给 SQL 示例（few-shot） |
| **AI 生成不存在的列名（幻觉）** | **列名白名单校验（已实现，第5道防线）**：见下方"十、列名白名单校验"完整代码 |
| 用户看不到 AI 查了啥 | 把生成的 SQL 一并返回给用户，实现"可解释"（答辩加分点） |

---

## 十、列名白名单校验（第 5 道防线，已实测）

**问题**：前 4 道锁只防"破坏"，不防"查错"。AI 可能编造不存在的列（如 `hireYear`、`join_date`），导致 SQL 报错。

**方案**：执行前把 SQL 中出现的所有标识符与白名单（表名 + 全部列名 + SQL 关键字）比对，出现白名单外的标识符 → 拒绝执行并把正确表结构返回给 AI → **AI 自动修正 SQL 重新调用**（工具返回错误信息即重试指令）。

```java
// DatabaseQueryTool 中新增（完整可编译版本已在项目中）

private static final Set<String> ALLOWED_IDENTIFIERS = Set.of(
        "emp", "dept",                                        // 表名
        "id", "name", "department", "salary", "position", "hire_year",  // emp 列
        "manager", "location",                                // dept 列
        "select", "from", "where", "group", "by", "order", "having", "limit",
        "and", "or", "not", "in", "like", "between", "is", "null", "as", "distinct",
        "count", "sum", "avg", "max", "min", "asc", "desc",
        "join", "left", "right", "inner", "on", "union", "all",
        "case", "when", "then", "else", "end", "offset", "top"  // 关键字/函数
);

private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("'[^']*'");

private String validateColumnNames(String sql) {
    // 1. 剔除字符串字面量（'技术部' 不参与校验）
    String cleaned = STRING_LITERAL_PATTERN.matcher(sql).replaceAll("''");
    // 2. 提取标识符比对白名单；1~2 字符的短标识符视为表别名放行
    Matcher m = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*").matcher(cleaned);
    Set<String> unknown = new TreeSet<>();
    while (m.find()) {
        String token = m.group();
        if (ALLOWED_IDENTIFIERS.contains(token.toLowerCase())) continue;
        if (token.length() <= 2) continue;  // 表别名 e/d 等
        unknown.add(token);
    }
    // 3. 有幻觉列名 → 拒绝执行，返回正确表结构引导 AI 修正
    if (!unknown.isEmpty()) {
        return "列名校验失败：SQL中出现了表中不存在的字段 " + unknown
             + "。emp表的合法列：id, name, department, salary, position, hire_year；"
             + "dept表的合法列：id, name, manager, location。请修正SQL后重新执行。";
    }
    return null;
}
// executeQuery 中调用：分号检查之后、执行之前
// String err = validateColumnNames(trimmedSql);
// if (err != null) return err;
```

**实测效果**（杭州项目 run.log）：

```
SQL: SELECT COUNT(*) AS count, AVG(salary) AS avg_salary FROM emp WHERE department = '技术部'
【案例16 列名白名单拦截】幻觉列名：[avg_salary]
SQL: SELECT COUNT(*) AS count, AVG(salary) FROM emp WHERE department = '技术部'   ← AI 自动修正
查询返回 1 条记录                                                                    ← 重试成功
```

> 注意：换表时记得同步更新 `ALLOWED_IDENTIFIERS` 中的列名集合。

---

## 九、答辩怎么讲（2 分钟话术）

> "本系统的数据查询分两层：底层是传统的管理系统，负责数据维护与固定查询；上层是 AI 智能查询——用户用自然语言提问，大模型根据数据库表结构现场生成 SQL，通过内置 5 道安全锁（只允许 SELECT、危险关键字拦截、分号防注入、自动 LIMIT、列名白名单校验——AI 编造不存在的列名时拒绝执行并引导其自动修正重试）校验后执行，再用自然语言总结结果。相比传统管理系统，业务人员无需懂 SQL 即可自助查询数据，这是本系统的核心创新点。"
