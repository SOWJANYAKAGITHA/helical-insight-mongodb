package com.helical.mongodb;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a constrained SQL subset against MongoDB collections.
 * Does not evaluate JavaScript or arbitrary expressions.
 */
final class MongoSqlExecutor {

    /**
     * When a SELECT omits {@code LIMIT}, at most this many rows are returned.
     * This is an explicit safety cap — not a guarantee of a full collection scan.
     * Prefer {@code LIMIT n} for predictable result sizes. Overridable via the
     * JDBC connection property {@code defaultQueryLimit}.
     */
    static final int DEFAULT_QUERY_ROW_CAP = 200;

    private static final Pattern SELECT_ONE = Pattern.compile(
            "^\\s*SELECT\\s+1(?:\\s+AS\\s+([A-Za-z_][A-Za-z0-9_]*))?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_FROM = Pattern.compile(
            "^\\s*SELECT\\s+(.+?)\\s+FROM\\s+([A-Za-z0-9_\\.\"`]+)(?:\\s+WHERE\\s+(.+?))?(?:\\s+LIMIT\\s+(\\d+))?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /**
     * Helical Insight Views / adhoc preview wraps user SQL as:
     * {@code select * from (<user sql>) <alias> limit <n>}
     */
    private static final Pattern HI_SUBQUERY_WRAP = Pattern.compile(
            "^\\s*SELECT\\s+\\*\\s+FROM\\s*\\((.*)\\)\\s*(?:[A-Za-z_][A-Za-z0-9_]*)?\\s*(?:LIMIT\\s+(\\d+))?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private MongoSqlExecutor() {
    }

    static MongoResultSet execute(MongoConnection connection, String sql) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) {
            throw new SQLException("SQL is empty");
        }
        String trimmed = stripTrailingSemicolon(sql.trim());
        Integer outerLimit = null;
        UnwrappedSql unwrapped = unwrapHelicalViewWrapper(trimmed);
        trimmed = unwrapped.sql;
        outerLimit = unwrapped.outerLimit;

        Matcher one = SELECT_ONE.matcher(trimmed);
        if (one.matches()) {
            String alias = one.group(1) == null ? "1" : one.group(1);
            Map<String, Integer> columns = new LinkedHashMap<>();
            columns.put(alias, Types.INTEGER);
            List<List<Object>> rows = Collections.singletonList(Collections.singletonList(1));
            return new MongoResultSet(new ArrayList<>(columns.keySet()), columns, rows);
        }

        Matcher select = SELECT_FROM.matcher(trimmed);
        if (!select.matches()) {
            throw new SQLException("Unsupported SQL for MongoDB adapter. Supported forms: "
                    + "SELECT 1; SELECT *|columns FROM collection [WHERE field = value] [LIMIT n]. "
                    + "Arbitrary SQL joins/functions and JavaScript are not supported.");
        }

        String projection = select.group(1).trim();
        String collectionName = unquote(select.group(2).trim());
        String whereClause = select.group(3);
        Integer limit = select.group(4) == null ? null : Integer.parseInt(select.group(4));
        limit = mergeLimits(limit, outerLimit);

        if (collectionName.contains(".")) {
            // catalog.schema.table or schema.table — take the final segment as collection.
            String[] parts = collectionName.split("\\.");
            collectionName = parts[parts.length - 1];
        }

        MongoDatabase database = connection.getMongoDatabase();
        if (!collectionExists(database, collectionName)) {
            throw new SQLException("Unknown MongoDB collection: " + collectionName);
        }

        MongoCollection<Document> collection = database.getCollection(collectionName);
        Document filter = parseSimpleWhere(whereClause);

        boolean appliedDefaultCap = false;
        int fetchLimit;
        if (limit == null) {
            int configured = connection.getDefaultQueryLimit();
            fetchLimit = configured > 0 ? configured : DEFAULT_QUERY_ROW_CAP;
            appliedDefaultCap = true;
        } else {
            fetchLimit = limit;
        }
        if (fetchLimit <= 0) {
            throw new SQLException("LIMIT must be a positive integer");
        }

        FindIterable<Document> iterable = collection.find(filter).limit(fetchLimit);
        List<Document> documents = new ArrayList<>();
        for (Document document : iterable) {
            documents.add(document);
        }

        Map<String, Integer> discovered = MongoDocumentConverter.discoverColumns(documents);
        List<String> selectedColumns = resolveProjection(projection, discovered);
        Map<String, Integer> columnTypes = new LinkedHashMap<>();
        for (String column : selectedColumns) {
            columnTypes.put(column, discovered.getOrDefault(column, Types.VARCHAR));
        }

        List<List<Object>> rows = new ArrayList<>();
        for (Document document : documents) {
            rows.add(MongoDocumentConverter.projectRow(document, selectedColumns));
        }
        MongoResultSet resultSet = new MongoResultSet(selectedColumns, columnTypes, rows);
        if (appliedDefaultCap) {
            resultSet.setWarningMessage(
                    "MongoDB adapter applied default row cap of " + fetchLimit
                            + " because LIMIT was omitted. Results may be incomplete; "
                            + "add LIMIT n for an explicit bound (property defaultQueryLimit).");
        }
        return resultSet;
    }

    private static boolean collectionExists(MongoDatabase database, String collectionName) {
        for (String name : database.listCollectionNames()) {
            if (name.equals(collectionName)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> resolveProjection(String projection, Map<String, Integer> discovered)
            throws SQLException {
        if ("*".equals(projection.trim())) {
            return new ArrayList<>(discovered.keySet());
        }
        String[] parts = projection.split(",");
        List<String> columns = new ArrayList<>();
        for (String part : parts) {
            String column = unquote(part.trim());
            if (column.isEmpty()) {
                continue;
            }
            // Strip simple aliases: col AS alias / col alias
            String upper = column.toUpperCase(Locale.ROOT);
            int asIdx = upper.lastIndexOf(" AS ");
            if (asIdx > 0) {
                column = column.substring(0, asIdx).trim();
            } else {
                String[] tokens = column.split("\\s+");
                if (tokens.length == 2) {
                    column = tokens[0];
                }
            }
            column = unquote(column);
            if (column.contains("(") || column.contains(")")) {
                throw new SQLException("SQL functions are not supported by the MongoDB adapter: " + column);
            }
            columns.add(column);
            discovered.putIfAbsent(column, Types.VARCHAR);
        }
        if (columns.isEmpty()) {
            throw new SQLException("No columns selected");
        }
        return columns;
    }

    private static Document parseSimpleWhere(String whereClause) throws SQLException {
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return new Document();
        }
        String where = whereClause.trim();
        if (where.toUpperCase(Locale.ROOT).contains(" AND ") || where.toUpperCase(Locale.ROOT).contains(" OR ")) {
            throw new SQLException("Only a single equality predicate is supported in WHERE clauses");
        }
        Matcher matcher = Pattern.compile(
                "^([A-Za-z0-9_\\.\"`]+)\\s*=\\s*(.+)$", Pattern.CASE_INSENSITIVE).matcher(where);
        if (!matcher.matches()) {
            throw new SQLException("Unsupported WHERE clause. Use: field = literal");
        }
        String field = unquote(matcher.group(1).trim());
        Object value = parseLiteral(matcher.group(2).trim());
        return new Document(field, value);
    }

    private static Object parseLiteral(String literal) throws SQLException {
        if (literal.equalsIgnoreCase("null")) {
            return null;
        }
        if ((literal.startsWith("'") && literal.endsWith("'"))
                || (literal.startsWith("\"") && literal.endsWith("\""))) {
            return literal.substring(1, literal.length() - 1);
        }
        if ("true".equalsIgnoreCase(literal) || "false".equalsIgnoreCase(literal)) {
            return Boolean.parseBoolean(literal);
        }
        try {
            if (literal.contains(".")) {
                return Double.parseDouble(literal);
            }
            return Long.parseLong(literal);
        } catch (NumberFormatException e) {
            throw new SQLException("Unsupported literal in WHERE clause: " + literal);
        }
    }

    private static String unquote(String identifier) {
        String value = identifier.trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("`") && value.endsWith("`"))
                || (value.startsWith("[") && value.endsWith("]"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String stripTrailingSemicolon(String sql) {
        if (sql.endsWith(";")) {
            return sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    /**
     * Peel Helical Insight's preview wrapper so the constrained SELECT subset can run.
     * Nested wrappers are unwrapped repeatedly; outer LIMIT values are combined with min().
     */
    private static UnwrappedSql unwrapHelicalViewWrapper(String sql) {
        String current = sql;
        Integer outerLimit = null;
        while (true) {
            Matcher wrap = HI_SUBQUERY_WRAP.matcher(current);
            if (!wrap.matches()) {
                return new UnwrappedSql(current, outerLimit);
            }
            String inner = wrap.group(1).trim();
            if (wrap.group(2) != null) {
                int parsed = Integer.parseInt(wrap.group(2));
                outerLimit = mergeLimits(outerLimit, parsed);
            }
            current = stripTrailingSemicolon(inner);
        }
    }

    private static Integer mergeLimits(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }

    private static final class UnwrappedSql {
        private final String sql;
        private final Integer outerLimit;

        private UnwrappedSql(String sql, Integer outerLimit) {
            this.sql = sql;
            this.outerLimit = outerLimit;
        }
    }
}
