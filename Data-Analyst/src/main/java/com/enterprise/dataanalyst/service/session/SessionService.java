package com.enterprise.dataanalyst.service.session;

import com.enterprise.dataanalyst.dto.SessionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages user sessions — each session is an isolated workspace.
 *
 * SESSION CONCEPT:
 * - Each session = unique UUID + name + its own tables + history
 * - New session = fresh context (like Claude's new chat)
 * - Old session click = restore its files and history
 * - Sessions persist across app restarts (stored in DuckDB)
 * - Tables in DuckDB persist — only the association changes
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    private final Connection duckDbConnection;

    // ===================== PUBLIC API =====================

    public SessionDTO createSession() throws SQLException {
        String sessionId = UUID.randomUUID().toString();
        String sessionName = "Session " + (getSessionCount() + 1);

        String sql = """
            INSERT INTO _sys_sessions (session_id, session_name)
            VALUES (?, ?)
            """;

        executeUpdate(sql, ps -> {
            ps.setString(1, sessionId);
            ps.setString(2, sessionName);
        });

        log.info("Created session: {} ({})", sessionName, sessionId);

        return SessionDTO.builder()
                .sessionId(sessionId)
                .sessionName(sessionName)
                .tableCount(0)
                .queryCount(0)
                .tables(new ArrayList<>())
                .build();
    }

    public List<SessionDTO> getAllSessions() throws SQLException {
        String sql = """
            SELECT s.session_id, s.session_name, s.created_at,
                   s.last_active,
                   COUNT(DISTINCT st.table_name) AS table_count,
                   COUNT(DISTINCT h.history_id) AS query_count
            FROM _sys_sessions s
            LEFT JOIN _sys_session_tables st
                   ON s.session_id = st.session_id
            LEFT JOIN _sys_query_history h
                   ON s.session_id = h.session_id
            GROUP BY s.session_id, s.session_name,
                     s.created_at, s.last_active
            ORDER BY s.last_active DESC
            """;

        List<SessionDTO> sessions = new ArrayList<>();

        try (Statement stmt = duckDbConnection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                sessions.add(mapSession(rs));
            }
        }
        return sessions;
    }

    public SessionDTO getSessionDetail(String sessionId) throws SQLException {
        SessionDTO session = getSessionById(sessionId);
        if (session == null) return null;

        List<SessionDTO.TableInfo> tables = getSessionTables(sessionId);
        session.setTables(tables);
        session.setTableCount(tables.size());

        updateLastActive(sessionId);
        return session;
    }

    public void addTableToSession(String sessionId, String tableName) throws SQLException {
        String sql = """
            INSERT INTO _sys_session_tables (session_id, table_name)
            VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """;

        executeUpdate(sql, ps -> {
            ps.setString(1, sessionId);
            ps.setString(2, tableName);
        });

        updateLastActive(sessionId);
        log.debug("Table '{}' added to session '{}'", tableName, sessionId);
    }

    public void removeTableFromSession(String sessionId, String tableName) throws SQLException {
        String sql = """
            DELETE FROM _sys_session_tables
            WHERE session_id = ? AND table_name = ?
            """;

        executeUpdate(sql, ps -> {
            ps.setString(1, sessionId);
            ps.setString(2, tableName);
        });
    }

    public List<String> getSessionTableNames(String sessionId) throws SQLException {
        String sql = """
            SELECT table_name FROM _sys_session_tables
            WHERE session_id = ?
            """;

        List<String> tables = new ArrayList<>();

        try (PreparedStatement ps = duckDbConnection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                tables.add(rs.getString("table_name"));
            }
        }
        return tables;
    }

    public void deleteSession(String sessionId) throws SQLException {
        executeUpdate("""
            DELETE FROM _sys_session_tables WHERE session_id = ?
            """, ps -> ps.setString(1, sessionId));

        executeUpdate("""
            DELETE FROM _sys_sessions WHERE session_id = ?
            """, ps -> ps.setString(1, sessionId));

        log.info("Session deleted: {}", sessionId);
    }

    // ===================== PRIVATE HELPERS =====================

    private SessionDTO getSessionById(String sessionId) throws SQLException {
        String sql = """
            SELECT session_id, session_name, created_at, last_active
            FROM _sys_sessions
            WHERE session_id = ?
            """;

        try (PreparedStatement ps = duckDbConnection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) return mapSession(rs);
        }
        return null;
    }

    private List<SessionDTO.TableInfo> getSessionTables(String sessionId) throws SQLException {
        String sql = """
            SELECT r.table_name, r.original_file,
                   r.file_type, r.row_count, r.uploaded_at
            FROM _sys_table_registry r
            JOIN _sys_session_tables st
                 ON r.table_name = st.table_name
            WHERE st.session_id = ?
            ORDER BY r.uploaded_at ASC
            """;

        List<SessionDTO.TableInfo> tables = new ArrayList<>();

        try (PreparedStatement ps = duckDbConnection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                tables.add(SessionDTO.TableInfo.builder()
                        .tableName(rs.getString("table_name"))
                        .originalFile(rs.getString("original_file"))
                        .fileType(rs.getString("file_type"))
                        .rowCount(rs.getLong("row_count"))
                        .uploadedAt(rs.getTimestamp("uploaded_at").toString())
                        .build());
            }
        }
        return tables;
    }

    private void updateLastActive(String sessionId) throws SQLException {
        String sql = """
            UPDATE _sys_sessions
            SET last_active = current_timestamp
            WHERE session_id = ?
            """;

        executeUpdate(sql, ps -> ps.setString(1, sessionId));
    }

    private int getSessionCount() throws SQLException {
        try (Statement stmt = duckDbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM _sys_sessions")) {

            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private SessionDTO mapSession(ResultSet rs) throws SQLException {
        return SessionDTO.builder()
                .sessionId(rs.getString("session_id"))
                .sessionName(rs.getString("session_name"))
                .createdAt(rs.getTimestamp("created_at").toString())
                .lastActive(rs.getTimestamp("last_active").toString())
                .tableCount(rs.getInt("table_count"))
                .queryCount(rs.getInt("query_count"))
                .build();
    }

    private void executeUpdate(String sql, SQLConsumer<PreparedStatement> consumer)
            throws SQLException {
        try (PreparedStatement ps = duckDbConnection.prepareStatement(sql)) {
            consumer.accept(ps);
            ps.executeUpdate();
        }
    }

    @FunctionalInterface
    interface SQLConsumer<T> {
        void accept(T t) throws SQLException;
    }
}