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
    private void initializeMetadataSchema(Connection conn) throws SQLException {
        String createTableRegistry = """
                CREATE TABLE IF NOT EXISTS _sys_table_registry (
                    table_name     VARCHAR PRIMARY KEY,
                    original_file  VARCHAR NOT NULL,
                    file_type      VARCHAR NOT NULL,
                    row_count      BIGINT,
                    uploaded_at    TIMESTAMP DEFAULT current_timestamp
                )
                """;

        String createColumnRegistry = """
                CREATE TABLE IF NOT EXISTS _sys_column_registry (
                    table_name   VARCHAR NOT NULL,
                    column_name  VARCHAR NOT NULL,
                    data_type    VARCHAR NOT NULL,
                    ordinal_pos  INTEGER NOT NULL,
                    PRIMARY KEY (table_name, column_name)
                )
                """;

        try (var stmt = conn.createStatement()) {
            stmt.execute(createTableRegistry);
            stmt.execute(createColumnRegistry);
            log.debug("System metadata tables verified/created.");
        }
    }
}