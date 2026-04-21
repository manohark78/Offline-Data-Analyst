package com.enterprise.dataanalyst.service.llm;

import com.enterprise.dataanalyst.model.ColumnMetadata;
import com.enterprise.dataanalyst.model.TableMetadata;
import com.enterprise.dataanalyst.service.registry.TableMetadataRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;

/**
 * Builds a DDL schema context to pass to the LLM.
 *
 * WHY WE PASS SCHEMA NOT DATA:
 * The model needs to know table structure to write correct SQL.
 * It does NOT need actual data values — that would be a security risk
 * and would make the context too large.
 *
 * WHAT WE PASS:
 *   CREATE TABLE employees (
 *       id BIGINT,
 *       name VARCHAR,
 *       salary DOUBLE,
 *       department VARCHAR
 *   );
 *
 * This is standard DDL — zero sensitive data, zero leakage.
 * Model uses this purely to understand column names and types.
 *
 * DuckDB-NSQL was trained on exactly this format — it knows how to
 * read DDL and generate correct DuckDB SQL from it.
 */
@Service
@RequiredArgsConstructor
public class SchemaContextBuilder {

    private final TableMetadataRegistry registry;

    /**
     * Build DDL for all uploaded tables.
     * Used when user doesn't specify a table — model gets full context.
     */
    public String buildFullSchema() {
        Collection<TableMetadata> tables = registry.getAllTables();

        if (tables.isEmpty()) {
            return "-- No tables loaded yet";
        }

        StringBuilder ddl = new StringBuilder();
        for (TableMetadata table : tables) {
            ddl.append(buildTableDDL(table)).append("\n\n");
        }
        return ddl.toString().trim();
    }

    /**
     * Build DDL for a specific table only.
     * Used when user clearly references a specific file/table.
     */
    public String buildTableSchema(String tableName) {
        return registry.findByTableName(tableName)
                .map(this::buildTableDDL)
                .orElse(buildFullSchema()); // fallback to full schema
    }

    /**
     * Build a CREATE TABLE DDL statement from TableMetadata.
     *
     * WHY CREATE TABLE FORMAT:
     * DuckDB-NSQL was fine-tuned on CREATE TABLE DDL as schema input.
     * Using this exact format maximises SQL accuracy.
     */
    private String buildTableDDL(TableMetadata table) {
        StringBuilder sb = new StringBuilder();

        // Add comment with original filename — helps model understand context
        sb.append("-- File: ").append(table.getOriginalFileName())
                .append(" (").append(table.getRowCount()).append(" rows)\n");

        sb.append("CREATE TABLE ").append(table.getTableName()).append(" (\n");

        var columns = table.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            ColumnMetadata col = columns.get(i);
            sb.append("    ")
                    .append(col.getColumnName())
                    .append(" ")
                    .append(col.getDataType());

            if (i < columns.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append(");");
        return sb.toString();
    }
}