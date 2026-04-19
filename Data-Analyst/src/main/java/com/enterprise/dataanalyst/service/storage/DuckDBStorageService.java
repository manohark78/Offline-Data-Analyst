// service/storage/DuckDBStorageService.java
package com.enterprise.dataanalyst.service.storage;

import com.enterprise.dataanalyst.model.ColumnMetadata;
import com.enterprise.dataanalyst.model.ParsedFileData;
import com.enterprise.dataanalyst.model.TableMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Manages all DuckDB interactions: table creation, data loading, querying.
 *
 * CONCURRENCY STRATEGY:
 * DuckDB allows multiple readers but only ONE writer at a time.
 * We enforce this with a ReentrantReadWriteLock:
 *   - writeLock: acquired during table creation + data insertion
 *   - readLock:  acquired during SELECT queries
 *
 * WHY NOT A CONNECTION POOL:
 * For the write path, multiple connections trying to write concurrently
 * would cause "conflicting lock" errors in DuckDB. A single write-locked
 * connection avoids this entirely. Read queries from DuckDB are fast
 * enough that a brief read lock is acceptable for this use case.
 *
 * BATCH INSERTS:
 * We use PreparedStatement.addBatch() + executeBatch() for data loading.
 * This is ~100x faster than individual INSERT statements. For 100k rows,
 * individual inserts take ~30s; batch inserts take ~300ms.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DuckDBStorageService {

    private final Connection duckDbConnection;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private static final int BATCH_SIZE = 1000;

    /**
     * Load a parsed file into DuckDB as a new table.
     *
     * If a table with the same name already exists, it is REPLACED.
     * This handles re-uploads of the same file with updated data.
     */
    public void loadTable(String tableName, ParsedFileData fileData) throws SQLException {
        rwLock.writeLock().lock();
        try {
            dropTableIfExists(tableName);
            createTable(tableName, fileData.getColumns());
            insertRows(tableName, fileData.getColumns(), fileData.getRows());
            persistMetadata(tableName, fileData);
            log.info("Table '{}' loaded with {} rows.", tableName, fileData.getRows().size());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Execute a read-only SQL query and return results as a list of maps.
     * Each map = one row. Key = column name, Value = string representation.
     *
     * WHY Map<String, Object>:
     * We preserve the actual Java types (Long, Double, LocalDate) in the result
     * so ResultFormatterService can format them appropriately.
     */
    public List<Map<String, Object>> executeQuery(String sql) throws SQLException {
        log.debug("Executing SQL: {}", sql);
        rwLock.readLock().lock();
        try (Statement stmt = duckDbConnection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            List<Map<String, Object>> results = new ArrayList<>();
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                results.add(row);
            }

            log.debug("Query returned {} rows.", results.size());
            return results;

        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Load all previously registered tables from DuckDB system metadata.
     * Called on application startup to restore registry state.
     */
    public List<TableMetadata> loadAllTableMetadata() throws SQLException {
        rwLock.readLock().lock();
        try {
            List<TableMetadata> tables = new ArrayList<>();

            String tablesSql = "SELECT table_name, original_file, file_type, row_count, uploaded_at " +
                               "FROM _sys_table_registry ORDER BY uploaded_at";

            try (Statement stmt = duckDbConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(tablesSql)) {

                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    List<ColumnMetadata> columns = loadColumnMetadata(tableName);

                    TableMetadata meta = TableMetadata.builder()
                            .tableName(tableName)
                            .originalFileName(rs.getString("original_file"))
                            .fileType(rs.getString("file_type"))
                            .rowCount(rs.getLong("row_count"))
                            .columns(columns)
                            .uploadedAt(rs.getTimestamp("uploaded_at").toLocalDateTime())
                            .build();

                    tables.add(meta);
                }
            }

            return tables;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────

    private void dropTableIfExists(String tableName) throws SQLException {
        try (Statement stmt = duckDbConnection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS \"" + tableName + "\"");
        }
    }

    /**
     * Build a CREATE TABLE statement from detected column types.
     *
     * WHY QUOTED IDENTIFIERS:
     * Column names like "first name" (with space) or "class" (SQL keyword)
     * would break unquoted. Double-quoting makes any identifier safe in DuckDB.
     */
    private void createTable(String tableName, List<ColumnMetadata> columns) throws SQLException {
        StringBuilder ddl = new StringBuilder("CREATE TABLE \"")
                .append(tableName)
                .append("\" (");

        for (int i = 0; i < columns.size(); i++) {
            ColumnMetadata col = columns.get(i);
            ddl.append("\"").append(col.getColumnName()).append("\" ").append(col.getDataType());
            if (i < columns.size() - 1) ddl.append(", ");
        }
        ddl.append(")");

        log.debug("DDL: {}", ddl);
        try (Statement stmt = duckDbConnection.createStatement()) {
            stmt.execute(ddl.toString());
        }
    }

    /**
     * Batch-insert all rows into the table.
     *
     * WHY PREPARED STATEMENT + BATCH:
     * PreparedStatement pre-compiles the INSERT. Each addBatch() call binds
     * parameters without re-parsing SQL. executeBatch() sends all rows in one
     * round trip (even though DuckDB is local, this still avoids per-row overhead).
     *
     * WHY WE CAST NULL EXPLICITLY:
     * setNull(i, Types.VARCHAR) is necessary because JDBC needs the SQL type
     * to properly encode the null. Calling setObject(i, null) can cause type
     * mismatch errors with some JDBC drivers.
     */
    private void insertRows(String tableName,
                            List<ColumnMetadata> columns,
                            List<Map<String, String>> rows) throws SQLException {

        if (rows.isEmpty()) return;

        String placeholders = columns.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));

        String columnList = columns.stream()
                .map(c -> "\"" + c.getColumnName() + "\"")
                .collect(Collectors.joining(", "));

        String insertSql = String.format("INSERT INTO \"%s\" (%s) VALUES (%s)",
                tableName, columnList, placeholders);

        try (PreparedStatement pstmt = duckDbConnection.prepareStatement(insertSql)) {
            int batchCount = 0;

            for (Map<String, String> row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    ColumnMetadata col = columns.get(i);
                    String rawValue = row.get(col.getColumnName());

                    if (rawValue == null) {
                        pstmt.setNull(i + 1, Types.VARCHAR);
                    } else {
                        bindTypedValue(pstmt, i + 1, rawValue, col.getDataType());
                    }
                }
                pstmt.addBatch();
                batchCount++;

                if (batchCount % BATCH_SIZE == 0) {
                    pstmt.executeBatch();
                    log.debug("Inserted batch of {} rows into '{}'", BATCH_SIZE, tableName);
                }
            }

            // Execute remaining rows
            if (batchCount % BATCH_SIZE != 0) {
                pstmt.executeBatch();
            }
        }
    }

    private void bindTypedValue(PreparedStatement pstmt, int paramIndex,
                                 String value, String sqlType) throws SQLException {
        try {
            switch (sqlType) {
                case "BIGINT":
                    pstmt.setLong(paramIndex, Long.parseLong(value.replace(",", "")));
                    break;
                case "DOUBLE":
                    pstmt.setDouble(paramIndex, Double.parseDouble(value.replace(",", "")));
                    break;
                case "BOOLEAN":
                    pstmt.setBoolean(paramIndex,
                            value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes"));
                    break;
                case "DATE":
                    pstmt.setObject(paramIndex, java.time.LocalDate.parse(value));
                    break;
                default: // VARCHAR
                    pstmt.setString(paramIndex, value);
            }
        } catch (Exception e) {
            // Type mismatch in data — fallback to string insertion
            // This is safe: DuckDB will attempt an implicit cast
            log.warn("Type cast failed for value '{}' as {}. Inserting as string.", value, sqlType);
            pstmt.setString(paramIndex, value);
        }
    }

    private void persistMetadata(String tableName, ParsedFileData fileData) throws SQLException {
        // Upsert into system tables
        String upsertTable = "INSERT OR REPLACE INTO _sys_table_registry " +
                "(table_name, original_file, file_type, row_count) VALUES (?, ?, ?, ?)";

        try (PreparedStatement pstmt = duckDbConnection.prepareStatement(upsertTable)) {
            pstmt.setString(1, tableName);
            pstmt.setString(2, fileData.getOriginalFileName());
            pstmt.setString(3, fileData.getDetectedFileType());
            pstmt.setLong(4, fileData.getRows().size());
            pstmt.executeUpdate();
        }

        // Clear existing column registry for this table
        try (PreparedStatement del = duckDbConnection.prepareStatement(
                "DELETE FROM _sys_column_registry WHERE table_name = ?")) {
            del.setString(1, tableName);
            del.executeUpdate();
        }

        String insertCol = "INSERT INTO _sys_column_registry " +
                "(table_name, column_name, data_type, ordinal_pos) VALUES (?, ?, ?, ?)";

        try (PreparedStatement pstmt = duckDbConnection.prepareStatement(insertCol)) {
            for (ColumnMetadata col : fileData.getColumns()) {
                pstmt.setString(1, tableName);
                pstmt.setString(2, col.getColumnName());
                pstmt.setString(3, col.getDataType());
                pstmt.setInt(4, col.getOrdinalPosition());
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    private List<ColumnMetadata> loadColumnMetadata(String tableName) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        String sql = "SELECT column_name, data_type, ordinal_pos FROM _sys_column_registry " +
                     "WHERE table_name = ? ORDER BY ordinal_pos";

        try (PreparedStatement pstmt = duckDbConnection.prepareStatement(sql)) {
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    columns.add(ColumnMetadata.builder()
                            .columnName(rs.getString("column_name"))
                            .originalName(rs.getString("column_name"))
                            .dataType(rs.getString("data_type"))
                            .ordinalPosition(rs.getInt("ordinal_pos"))
                            .build());
                }
            }
        }
        return columns;
    }
}