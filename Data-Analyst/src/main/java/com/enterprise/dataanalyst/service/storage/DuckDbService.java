package com.enterprise.dataanalyst.duckdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * DuckDbService — Core database operations.
 *
 * Manages all interactions with DuckDB:
 * - Read-only queries (SELECT)
 * - Schema introspection
 * - Table creation from uploads
 * - Metadata persistence
 *
 * THREAD SAFETY:
 * DuckDB allows concurrent reads but single writer.
 * ReadWriteLock ensures correctness.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DuckDbService {

    private final Connection conn;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // ─── QUERY EXECUTION ─────────────────────────────────────

    /**
     * Execute a SELECT query. Returns structured QueryResult.
     */
    public QueryResult query(String sql) throws SQLException {
        lock.readLock().lock();
        long start = System.currentTimeMillis();
        try {
            String safeSql = sql.trim().replaceAll(";$", "");

            try (PreparedStatement ps = conn.prepareStatement(safeSql);
                 ResultSet rs = ps.executeQuery()) {

                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                List<String> columns = new ArrayList<>();
                List<String> types = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                    types.add(meta.getColumnTypeName(i));
                }

                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }

                long elapsed = System.currentTimeMillis() - start;
                log.debug("Query done in {}ms, {} rows", elapsed, rows.size());

                return QueryResult.builder()
                        .columns(columns)
                        .types(types)
                        .rows(rows)
                        .rowCount(rows.size())
                        .executionMs(elapsed)
                        .sql(safeSql)
                        .build();
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Execute query and return List of Maps (for simple use cases).
     */
    public List<Map<String, Object>> queryMaps(String sql) throws SQLException {
        QueryResult result = query(sql);
        List<Map<String, Object>> maps = new ArrayList<>();
        for (List<Object> row : result.getRows()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < result.getColumns().size(); i++) {
                map.put(result.getColumns().get(i), row.get(i));
            }
            maps.add(map);
        }
        return maps;
    }

    /**
     * Execute a write operation (INSERT/UPDATE/DELETE/CREATE/DROP).
     */
    public void execute(String sql) throws SQLException {
        lock.writeLock().lock();
        try (Statement s = conn.createStatement()) {
            s.execute(sql.trim());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Execute with PreparedStatement.
     */
    public void executePrepared(String sql, Object... params)
            throws SQLException {
        lock.writeLock().lock();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ─── SCHEMA INTROSPECTION ─────────────────────────────────

    /**
     * Get complete schema info for a table.
     */
    public SchemaInfo getSchema(String tableName) throws SQLException {
        String sql = String.format(
            "SELECT column_name, data_type, ordinal_position " +
            "FROM information_schema.columns " +
            "WHERE table_name = '%s' " +
            "ORDER BY ordinal_position",
            tableName.replace("'", "''")
        );

        List<SchemaInfo.ColumnInfo> columns = new ArrayList<>();
        List<Map<String, Object>> rows = queryMaps(sql);
        for (Map<String, Object> row : rows) {
            columns.add(SchemaInfo.ColumnInfo.builder()
                    .name(String.valueOf(row.get("column_name")))
                    .type(String.valueOf(row.get("data_type")))
                    .ordinal(((Number) row.get("ordinal_position")).intValue())
                    .build());
        }

        long rowCount = getRowCount(tableName);

        return SchemaInfo.builder()
                .tableName(tableName)
                .columns(columns)
                .rowCount(rowCount)
                .build();
    }

    /**
     * Get all user-uploaded table names (excluding system tables).
     */
    public List<String> getUserTableNames() throws SQLException {
        String sql =
            "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = 'main' " +
            "AND table_name NOT IN " +
            "('conversations','messages','datasets','column_profiles') " +
            "ORDER BY table_name";

        List<String> tables = new ArrayList<>();
        for (Map<String, Object> row : queryMaps(sql)) {
            tables.add(String.valueOf(row.get("table_name")));
        }
        return tables;
    }

    /**
     * Get distinct values for a column (for semantic profiling).
     */
    public List<String> getDistinctValues(String tableName,
                                           String columnName,
                                           int limit) throws SQLException {
        String sql = String.format(
            "SELECT DISTINCT \"%s\" FROM \"%s\" " +
            "WHERE \"%s\" IS NOT NULL " +
            "ORDER BY \"%s\" LIMIT %d",
            columnName, tableName, columnName, columnName, limit
        );

        List<String> values = new ArrayList<>();
        for (Map<String, Object> row : queryMaps(sql)) {
            Object val = row.get(columnName);
            if (val != null) values.add(String.valueOf(val));
        }
        return values;
    }

    /**
     * Get row count for a table.
     */
    public long getRowCount(String tableName) throws SQLException {
        String sql = String.format(
                "SELECT COUNT(*) as cnt FROM \"%s\"", tableName);
        List<Map<String, Object>> result = queryMaps(sql);
        if (result.isEmpty()) return 0;
        return ((Number) result.get(0).get("cnt")).longValue();
    }

    /**
     * Preview first N rows of a table.
     */
    public QueryResult preview(String tableName, int limit)
            throws SQLException {
        return query(String.format(
                "SELECT * FROM \"%s\" LIMIT %d", tableName, limit));
    }

    /**
     * Check if a table exists.
     */
    public boolean tableExists(String tableName) throws SQLException {
        String sql = String.format(
            "SELECT COUNT(*) as cnt FROM information_schema.tables " +
            "WHERE table_name = '%s'",
            tableName.replace("'", "''")
        );
        List<Map<String, Object>> result = queryMaps(sql);
        return !result.isEmpty() &&
               ((Number) result.get(0).get("cnt")).intValue() > 0;
    }

    /**
     * Drop table if exists.
     */
    public void dropTable(String tableName) throws SQLException {
        execute(String.format("DROP TABLE IF EXISTS \"%s\"", tableName));
    }
}
