package com.enterprise.dataanalyst.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/**
 * DuckDB Configuration.
 *
 * WHY THIS APPROACH:
 * DuckDB is an embedded database — there is no separate server process.
 * The JDBC connection connects directly to a local file (or in-memory).
 *
 * THREADING NOTE:
 * DuckDB supports multiple simultaneous read connections but only ONE
 * write connection at a time. We manage this in DuckDBStorageService
 * using a ReentrantReadWriteLock. Here we expose the raw Connection
 * as a singleton bean — the service layer handles concurrency.
 *
 * PERSISTENCE:
 * Using a file-backed DB means uploaded data survives app restarts.
 * If you want in-memory only, set database-path to empty string.
 */
@Configuration
@Slf4j
public class DuckDBConfig {

    @Value("${app.duckdb.database-path}")
    private String databasePath;

    @Value("${app.upload.temp-dir}")
    private String uploadTempDir;

    @Bean(destroyMethod = "close")
    public Connection duckDbConnection() throws SQLException, IOException {
        // Ensure parent directories exist before DuckDB tries to create the file
        if (!databasePath.isBlank()) {
            Path dbPath = Paths.get(databasePath);
            Files.createDirectories(dbPath.getParent());
            log.info("DuckDB database file: {}", dbPath.toAbsolutePath());
        }

        // Ensure upload temp dir exists
        Files.createDirectories(Paths.get(uploadTempDir));

        // Load the DuckDB JDBC driver explicitly
        // Why: In a fat JAR, auto-detection of JDBC drivers can sometimes fail
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("DuckDB JDBC driver not found on classpath", e);
        }

        String jdbcUrl = databasePath.isBlank()
                ? "jdbc:duckdb:"
                : "jdbc:duckdb:" + databasePath;

        Connection connection = DriverManager.getConnection(jdbcUrl);
        log.info("DuckDB connection established. JDBC URL: {}", jdbcUrl);

        // Initialize internal metadata table
        // This stores our schema registry persistently
        initializeMetadataSchema(connection);

        return connection;
    }

    /**
     * Create the internal metadata table if it doesn't exist.
     *
     * WHY: We store schema info (column names, types, row counts) in DuckDB itself
     * so the registry survives application restarts. On startup, we reload this
     * into memory via TableMetadataRegistry.
     */
    private void initializeMetadataSchema(Connection conn)
            throws SQLException {
            List<String> queries = List.of(

                    // Sessions table
                    """
                    CREATE TABLE IF NOT EXISTS _sys_sessions (
                        session_id   VARCHAR PRIMARY KEY,
                        session_name VARCHAR NOT NULL,
                        created_at   TIMESTAMP DEFAULT current_timestamp,
                        last_active  TIMESTAMP DEFAULT current_timestamp
                    )
                    """,

                    // Session tables mapping
                    """
                    CREATE TABLE IF NOT EXISTS _sys_session_tables (
                        session_id VARCHAR NOT NULL,
                        table_name VARCHAR NOT NULL,
                        PRIMARY KEY (session_id, table_name)
                    )
                    """,

                    // Table registry
                    """
                    CREATE TABLE IF NOT EXISTS _sys_table_registry (
                        table_name    VARCHAR PRIMARY KEY,
                        original_file VARCHAR NOT NULL,
                        file_type     VARCHAR NOT NULL,
                        row_count     BIGINT,
                        uploaded_at   TIMESTAMP DEFAULT current_timestamp
                    )
                    """,

                    // Column registry
                    """
                    CREATE TABLE IF NOT EXISTS _sys_column_registry (
                        table_name  VARCHAR NOT NULL,
                        column_name VARCHAR NOT NULL,
                        data_type   VARCHAR NOT NULL,
                        ordinal_pos INTEGER NOT NULL,
                        PRIMARY KEY (table_name, column_name)
                    )
                    """,

                    // Column profiles — stores actual data intelligence
                    // Populated by DataProfilerService at upload time.
                    // Used by Pass 2 LLM prompts for data-aware reasoning.
                    """
                    CREATE TABLE IF NOT EXISTS _sys_column_profiles (
                        table_name       VARCHAR NOT NULL,
                        column_name      VARCHAR NOT NULL,
                        data_type        VARCHAR,
                        distinct_count   BIGINT DEFAULT 0,
                        null_count       BIGINT DEFAULT 0,
                        total_count      BIGINT DEFAULT 0,
                        min_value        VARCHAR,
                        max_value        VARCHAR,
                        dominant_pattern VARCHAR,
                        sample_values    VARCHAR,
                        distinct_values  VARCHAR,
                        PRIMARY KEY (table_name, column_name)
                    )
                    """,

                    // Cleanup old history
                    "DROP TABLE IF EXISTS _sys_query_history",
                    "DROP SEQUENCE IF EXISTS _sys_history_seq",

                    // Query history
                    """
                    CREATE TABLE IF NOT EXISTS _sys_query_history (
                        history_id    INTEGER PRIMARY KEY,
                        session_id    VARCHAR,
                        user_query    VARCHAR NOT NULL,
                        generated_sql VARCHAR,
                        status        VARCHAR,
                        row_count     INTEGER DEFAULT 0,
                        execution_ms  BIGINT DEFAULT 0,
                        queried_at    TIMESTAMP DEFAULT current_timestamp
                    )
                    """,

                    // Sequence
                    """
                    CREATE SEQUENCE IF NOT EXISTS _sys_history_seq START 1
                    """
            );

            try (var stmt = conn.createStatement()) {
                for (String query : queries) {
                    stmt.execute(query);
                }
                log.debug("Schema initialized.");
            } catch (SQLException e) {
                throw new RuntimeException("Failed to initialize schema", e);
            }
    }
}