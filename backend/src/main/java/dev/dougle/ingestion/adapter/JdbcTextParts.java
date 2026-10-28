package dev.dougle.ingestion.adapter;

import java.util.AbstractList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcTextParts {
    private JdbcTextParts() {}

    public static List<String> document(JdbcTemplate jdbc, String entryId) {
        return read(jdbc, "extracted_text_part", "entry_id=?", entryId);
    }

    public static List<String> checkpoint(JdbcTemplate jdbc, String sourceId, String path) {
        return read(jdbc, "source_analysis_text_part", "source_id=? AND path=?", sourceId, path);
    }

    private static List<String> read(JdbcTemplate jdbc, String table, String predicate, Object... keys) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Integer.class, keys);
        if (count == 0) return List.of();
        return new AbstractList<>() {
            @Override public int size() { return count; }
            @Override public String get(int index) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                java.util.Objects.checkIndex(index, count);
                Object[] arguments = java.util.Arrays.copyOf(keys, keys.length + 1);
                arguments[keys.length] = index;
                return jdbc.queryForObject("SELECT text FROM " + table + " WHERE " + predicate + " AND ordinal=?",
                        String.class, arguments);
            }
        };
    }
}
