package com.opsnexus.analytics;

import com.opsnexus.knowledge.KnowledgeException;
import java.util.*;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * Validates untrusted model SQL against the small, intentionally fixed analytics schema.
 * This is a policy boundary, so unsupported AST shapes are rejected instead of guessed at.
 */
@Component
public class SqlSafetyValidator {
    private static final int MAX_ROWS = 100;
    private static final int MAX_SELECT_ITEMS = 12;
    private static final int MAX_ORDER_BY_ITEMS = 3;
    private static final int MAX_GROUP_BY_ITEMS = 3;
    private static final int MAX_FUNCTIONS = 5;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,62}");
    private static final Map<String, Set<String>> COLUMNS_BY_TABLE = Map.of(
        "service_catalog", Set.of("id", "service_name", "display_name", "owner_name", "current_version", "runtime_status"),
        "release_record", Set.of("id", "service_name", "version_no", "environment", "status", "released_at", "summary"),
        "incident_record", Set.of("id", "service_name", "symptom", "root_cause", "resolution", "status", "occurred_at", "resolved_at")
    );
    private static final Set<String> FUNCTIONS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "DATEADD", "DATE_ADD");

    public String validate(String raw) {
        try {
            String sql = normalize(raw);
            var statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.size() != 1) {
                reject("SQL_MULTIPLE_STATEMENTS", "只允许一条 SQL");
            }
            Statement statement = statements.get(0);
            if (!(statement instanceof PlainSelect)) {
                reject("SQL_STATEMENT_NOT_ALLOWED", "只允许单条 SELECT");
            }
            PlainSelect select = (PlainSelect) statement;
            validateSelectShape(select);
            InspectVisitor visitor = new InspectVisitor();
            visitor.getTables(statement);
            if (visitor.nested) {
                reject("SQL_SUBQUERY_NOT_ALLOWED", "禁止子查询、CTE 和集合查询");
            }
            if (visitor.wildcard) {
                reject("SQL_WILDCARD_NOT_ALLOWED", "禁止 SELECT *，请明确选择字段");
            }
            Map<String, String> sources = collectSources(select);
            Set<String> projectionAliases = collectProjectionAliases(select, sources.keySet());
            validateJoins(select, sources);
            if (visitor.functions.size() > MAX_FUNCTIONS) {
                reject("SQL_COMPLEXITY_EXCEEDED", "函数数量超过安全上限");
            }
            for (Function function : visitor.functions) {
                if (function.getName() == null || !FUNCTIONS.contains(function.getName().toUpperCase(Locale.ROOT))) {
                    reject("SQL_FUNCTION_NOT_ALLOWED", "SQL 使用了非白名单函数");
                }
            }
            for (Column column : visitor.columns) {
                validateColumn(column, sources, projectionAliases);
            }
            enforceLimit(select);
            return select.toString();
        } catch (KnowledgeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new KnowledgeException(400, "SQL_PARSE_ERROR", "SQL 语法解析失败，查询未执行");
        }
    }

    private void validateSelectShape(PlainSelect select) {
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            reject("SQL_CTE_NOT_ALLOWED", "禁止 CTE");
        }
        if (select.getIntoTables() != null && !select.getIntoTables().isEmpty()) {
            reject("SQL_INTO_NOT_ALLOWED", "禁止 SELECT INTO");
        }
        if (select.getForClause() != null || select.getForMode() != null || select.isSkipLocked() || select.isNoWait()) {
            reject("SQL_LOCK_NOT_ALLOWED", "禁止锁定查询");
        }
        if (select.getFromItem() == null || select.getLateralViews() != null && !select.getLateralViews().isEmpty()
            || select.getTop() != null || select.getSkip() != null || select.getFetch() != null
            || select.getLimitBy() != null || select.getQualify() != null
            || select.getWindowDefinitions() != null && !select.getWindowDefinitions().isEmpty()) {
            reject("SQL_FEATURE_NOT_ALLOWED", "SQL 使用了不支持的查询结构");
        }
        if (select.getSelectItems() == null || select.getSelectItems().isEmpty() || select.getSelectItems().size() > MAX_SELECT_ITEMS) {
            reject("SQL_COMPLEXITY_EXCEEDED", "选择字段数量超过安全上限");
        }
        if (select.getOrderByElements() != null && select.getOrderByElements().size() > MAX_ORDER_BY_ITEMS) {
            reject("SQL_COMPLEXITY_EXCEEDED", "排序字段数量超过安全上限");
        }
        if (select.getGroupBy() != null) {
            var expressions = select.getGroupBy().getGroupByExpressionList();
            if (expressions == null || expressions.size() > MAX_GROUP_BY_ITEMS
                || select.getGroupBy().getGroupingSets() != null && !select.getGroupBy().getGroupingSets().isEmpty()) {
                reject("SQL_COMPLEXITY_EXCEEDED", "分组结构超过安全上限");
            }
        }
    }

    private Map<String, String> collectSources(PlainSelect select) {
        Map<String, String> sources = new LinkedHashMap<>();
        addSource(select.getFromItem(), sources);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                addSource(join.getRightItem(), sources);
            }
        }
        return sources;
    }

    private void addSource(FromItem item, Map<String, String> sources) {
        if (!(item instanceof Table)) {
            reject("SQL_TABLE_NOT_ALLOWED", "只允许白名单业务表");
        }
        Table table = (Table) item;
        if (table.getNameParts().size() != 1) {
            reject("SQL_TABLE_NOT_ALLOWED", "只允许白名单业务表");
        }
        String tableName = normalized(table.getName());
        if (!COLUMNS_BY_TABLE.containsKey(tableName)) {
            reject("SQL_TABLE_NOT_ALLOWED", "SQL 访问了非白名单表");
        }
        String alias = table.getAlias() == null ? tableName : normalized(table.getAlias().getName());
        if (!IDENTIFIER.matcher(alias).matches() || sources.putIfAbsent(alias, tableName) != null) {
            reject("SQL_ALIAS_INVALID", "表别名无效或重复");
        }
        if (sources.values().stream().filter(tableName::equals).count() > 1) {
            reject("SQL_TABLE_NOT_ALLOWED", "禁止重复引用同一业务表");
        }
    }

    private Set<String> collectProjectionAliases(PlainSelect select, Set<String> tableReferences) {
        Set<String> aliases = new HashSet<>();
        for (SelectItem<?> item : select.getSelectItems()) {
            if (item.getAlias() == null) {
                continue;
            }
            String alias = normalized(item.getAlias().getName());
            if (!IDENTIFIER.matcher(alias).matches() || tableReferences.contains(alias) || !aliases.add(alias)) {
                reject("SQL_ALIAS_INVALID", "列别名无效、冲突或重复");
            }
        }
        return aliases;
    }

    private void validateJoins(PlainSelect select, Map<String, String> sources) {
        List<Join> joins = select.getJoins() == null ? List.of() : select.getJoins();
        if (joins.size() > 2) {
            reject("SQL_COMPLEXITY_EXCEEDED", "最多允许两个 JOIN");
        }
        for (Join join : joins) {
            if (join.isCross() || join.isNatural() || join.isFull() || join.isRight() || join.isApply() || join.isStraight()
                || join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()
                || join.getOnExpressions() == null || join.getOnExpressions().size() != 1) {
                reject("SQL_JOIN_NOT_ALLOWED", "JOIN 类型或关联条件不安全");
            }
            Expression expression = join.getOnExpressions().iterator().next();
            if (!(expression instanceof EqualsTo equalsTo)
                || !(equalsTo.getLeftExpression() instanceof Column left)
                || !(equalsTo.getRightExpression() instanceof Column right)
                || !isServiceCatalogJoin(left, right, sources)) {
                reject("SQL_JOIN_NOT_ALLOWED", "JOIN 只能按 service_catalog.service_name 关联业务记录");
            }
        }
    }

    private boolean isServiceCatalogJoin(Column left, Column right, Map<String, String> sources) {
        String leftTable = referencedTable(left, sources);
        String rightTable = referencedTable(right, sources);
        return "service_name".equals(normalized(left.getColumnName()))
            && "service_name".equals(normalized(right.getColumnName()))
            && ("service_catalog".equals(leftTable) || "service_catalog".equals(rightTable))
            && ("release_record".equals(leftTable) || "incident_record".equals(leftTable)
                || "release_record".equals(rightTable) || "incident_record".equals(rightTable));
    }

    private void validateColumn(Column column, Map<String, String> sources, Set<String> projectionAliases) {
        String name = normalized(column.getColumnName());
        String reference = column.getTable() == null ? "" : normalized(column.getTable().getName());
        if (reference.isEmpty()) {
            if (projectionAliases.contains(name)) {
                return;
            }
            long matches = sources.values().stream().filter(table -> COLUMNS_BY_TABLE.get(table).contains(name)).count();
            if (matches != 1) {
                reject("SQL_COLUMN_NOT_ALLOWED", "未限定或非白名单字段不安全");
            }
            return;
        }
        String table = sources.get(reference);
        if (table == null || !COLUMNS_BY_TABLE.get(table).contains(name)) {
            reject("SQL_COLUMN_NOT_ALLOWED", "SQL 使用了非白名单字段");
        }
    }

    private String referencedTable(Column column, Map<String, String> sources) {
        String reference = column.getTable() == null ? "" : normalized(column.getTable().getName());
        String table = sources.get(reference);
        if (reference.isEmpty() || table == null) {
            reject("SQL_JOIN_NOT_ALLOWED", "JOIN 字段必须使用受控表别名");
        }
        return table;
    }

    private void enforceLimit(PlainSelect select) {
        Limit limit = select.getLimit();
        if (limit == null) {
            select.setLimit(new Limit().withRowCount(new LongValue(MAX_ROWS)));
            return;
        }
        if (select.getOffset() != null || limit.getOffset() != null || limit.isLimitAll() || limit.isLimitNull() || !(limit.getRowCount() instanceof LongValue)) {
            reject("SQL_LIMIT_NOT_ALLOWED", "LIMIT 必须是无偏移的整数");
        }
        LongValue rowCount = (LongValue) limit.getRowCount();
        if (rowCount.getValue() < 1) {
            reject("SQL_LIMIT_NOT_ALLOWED", "LIMIT 必须大于零");
        }
        if (rowCount.getValue() > MAX_ROWS) {
            limit.setRowCount(new LongValue(MAX_ROWS));
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("```sql", "").replace("```", "").strip();
    }

    private String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static void reject(String code, String reason) {
        throw new KnowledgeException(400, code, reason + "，查询未执行");
    }

    private static class InspectVisitor extends TablesNamesFinder {
        final List<Column> columns = new ArrayList<>();
        final List<Function> functions = new ArrayList<>();
        boolean nested;
        boolean wildcard;
        int selects;

        @Override public void visit(Column column) { columns.add(column); super.visit(column); }
        @Override public void visit(Function function) { functions.add(function); super.visit(function); }
        @Override public void visit(AllColumns columns) { wildcard = true; super.visit(columns); }
        @Override public void visit(AllTableColumns columns) { wildcard = true; super.visit(columns); }
        @Override public void visit(PlainSelect select) { if (++selects > 1) nested = true; super.visit(select); }
        @Override public void visit(ParenthesedSelect select) { nested = true; super.visit(select); }
        @Override public void visit(SetOperationList select) { nested = true; super.visit(select); }
        @Override public void visit(WithItem select) { nested = true; super.visit(select); }
    }
}
