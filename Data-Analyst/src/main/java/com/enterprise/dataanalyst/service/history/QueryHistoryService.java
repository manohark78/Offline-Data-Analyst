package com.enterprise.dataanalyst.service.history;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueryHistoryService {

    private final Connection duckDbConnection;
    private static final int MAX_HISTORY = 500;

    private final List<Map<String, Object>> sessionHistory
            = new ArrayList<>();
    // save() method update
    public void save(String userQuery, String generatedSql,
                     String status, int rowCount,
                     long ms, String sessionId) {

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("userQuery",    userQuery);
        entry.put("generatedSql", generatedSql);
        entry.put("status",       status);
        entry.put("rowCount",     rowCount);
        entry.put("executionMs",  ms);
        entry.put("sessionId",    sessionId);
        entry.put("queriedAt",
                LocalDateTime.now().toString());
        sessionHistory.add(0, entry);

        saveToDB(userQuery, generatedSql, status,
                rowCount, ms, sessionId);
    }

    private void saveToDB(String userQuery, String generatedSql,
                          String status, int rowCount,
                          long ms, String sessionId) {
        String sql =
                "INSERT INTO _sys_query_history " +
                "(history_id, session_id, user_query, generated_sql, " +
                " status, row_count, execution_ms) " +
                "VALUES (nextval('_sys_history_seq'), ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps =
                     duckDbConnection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, userQuery);
            ps.setString(3, generatedSql != null ? generatedSql : "");
            ps.setString(4, status);
            ps.setInt(5, rowCount);
            ps.setLong(6, ms);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("History save failed: {}", e.getMessage());
        }
    }

    // Get history for specific session


    /**
     * Load recent history — survives restart.
     */
    public List<Map<String, Object>> getRecent(int limit) {
        String sql =
                "SELECT id, user_query, generated_sql, status, " +
                "row_count, execution_ms, queried_at " +
                "FROM _sys_query_history " +
                "ORDER BY queried_at DESC LIMIT ?";
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = duckDbConnection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",           rs.getInt("id"));
                row.put("userQuery",    rs.getString("user_query"));
                row.put("generatedSql", rs.getString("generated_sql"));
                row.put("status",       rs.getString("status"));
                row.put("rowCount",     rs.getInt("row_count"));
                row.put("executionMs",  rs.getLong("execution_ms"));
                row.put("queriedAt",    rs.getTimestamp("queried_at").toString());
                results.add(row);
            }
        } catch (SQLException e) {
            log.warn("Failed to load history: {}", e.getMessage());
        }
        return results;
    }

    /**
     * Search history by keyword.
     */
    public List<Map<String, Object>> search(String keyword) {
        String sql =
                "SELECT id, user_query, generated_sql, status, " +
                "row_count, execution_ms, queried_at " +
                "FROM _sys_query_history " +
                "WHERE lower(user_query) LIKE ? " +
                "ORDER BY queried_at DESC LIMIT 50";
        List<Map<String, Object>> results = new ArrayList<>();
        try (PreparedStatement ps = duckDbConnection.prepareStatement(sql)) {
            ps.setString(1, "%" + keyword.toLowerCase() + "%");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("userQuery",    rs.getString("user_query"));
                row.put("generatedSql", rs.getString("generated_sql"));
                row.put("status",       rs.getString("status"));
                row.put("queriedAt",    rs.getTimestamp("queried_at").toString());
                results.add(row);
            }
        } catch (SQLException e) {
            log.warn("History search failed: {}", e.getMessage());
        }
        return results;
    }

    public List<Map<String, Object>> getSessionHistory() {
        return Collections.unmodifiableList(sessionHistory);
    }

    /**
     * Get full persistent history from DuckDB.
     * Includes all previous sessions.
     */
    public List<Map<String, Object>> getFullHistory(int limit) {
        return getRecent(limit); // from DuckDB
    }

    /**
     * Clear session history only — not DuckDB.
     */
    public void clearSessionHistory() {
        sessionHistory.clear();
        log.info("Session history cleared.");
    }

    /**
     * Clear everything — both session and DuckDB.
     */
    public void clearAllHistory() {
        sessionHistory.clear();
        clear(); // DuckDB clear
        log.info("All history cleared.");
    }
    public void clear() {
        try (var stmt = duckDbConnection.createStatement()) {
            stmt.execute("DELETE FROM _sys_query_history");
            log.info("Query history cleared.");
        } catch (SQLException e) {
            log.warn("Failed to clear history: {}", e.getMessage());
        }
    }
}