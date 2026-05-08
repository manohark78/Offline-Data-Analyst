package com.enterprise.dataanalyst.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

@Configuration
@Slf4j
public class DuckDbConfig {

    @Value("${app.duckdb.path}")
    private String duckDbPath;

    @Bean(destroyMethod = "close")
    public Connection duckDbConnection() throws Exception {
        // Ensure parent directory exists
        File dbFile = new File(duckDbPath);
        dbFile.getParentFile().mkdirs();

        Class.forName("org.duckdb.DuckDBDriver");
        Connection conn = DriverManager.getConnection(
                "jdbc:duckdb:" + duckDbPath);

        log.info("DuckDB connected: {}", dbFile.getAbsolutePath());
        initSchema(conn);
        return conn;
    }

    private void initSchema(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {

            // Conversations — like Claude's chats
            s.execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id          VARCHAR PRIMARY KEY,
                    title       VARCHAR NOT NULL,
                    dataset_id  VARCHAR,
                    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            // Messages — user + assistant turns
            s.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id                  VARCHAR PRIMARY KEY,
                    conversation_id     VARCHAR NOT NULL,
                    role                VARCHAR NOT NULL,
                    content             TEXT    NOT NULL,
                    intent_type         VARCHAR,
                    sql_used            TEXT,
                    row_count           INTEGER,
                    execution_ms        BIGINT,
                    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            // Datasets — uploaded files
            s.execute("""
                CREATE TABLE IF NOT EXISTS datasets (
                    id           VARCHAR PRIMARY KEY,
                    name         VARCHAR NOT NULL,
                    file_name    VARCHAR NOT NULL,
                    file_type    VARCHAR NOT NULL,
                    table_name   VARCHAR NOT NULL,
                    row_count    BIGINT,
                    column_count INTEGER,
                    schema_json  JSON,
                    uploaded_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            // Column semantic profiles — LLM-discovered meanings
            s.execute("""
                CREATE TABLE IF NOT EXISTS column_profiles (
                    dataset_id      VARCHAR NOT NULL,
                    column_name     VARCHAR NOT NULL,
                    semantic_type   VARCHAR,
                    distinct_values JSON,
                    value_synonyms  JSON,
                    PRIMARY KEY (dataset_id, column_name)
                )
            """);

            log.debug("DuckDB schema initialized.");
        }
    }
}
