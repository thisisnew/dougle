package dev.knowledgeforge.plugins.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PluginConnections {
    private final PluginVault vault;
    private final NotionClient notion;
    private final MysqlClient mysql;
    private final JsonMapper json = new JsonMapper();
    private final java.util.concurrent.atomic.AtomicLong mysqlEpoch = new java.util.concurrent.atomic.AtomicLong();
    private final String mysqlSession = java.util.UUID.randomUUID().toString();
    public record MysqlScopeMetadata(String revision, String generation) {}
    public record Status(boolean notionConnected, boolean mysqlConnected, MysqlClient.Config mysql) {}
    public PluginConnections(PluginVault vault, NotionClient notion, MysqlClient mysql) {
        this.vault = vault; this.notion = notion; this.mysql = mysql;
    }
    public Status status() {
        var config = mysqlConfig();
        return new Status(notionToken() != null, config != null, config == null ? null : config.withoutPassword());
    }
    public String notionToken() { return vault.get("notion"); }
    public synchronized MysqlClient.Config mysqlConfig() {
        String saved = vault.get("mysql");
        return saved == null ? null : json.readValue(saved, MysqlClient.Config.class);
    }
    public void notion(String token, boolean save) {
        notion.test(token);
        if (save) vault.put("notion", token);
    }
    public void mysql(MysqlClient.Config config, boolean save) {
        mysql.test(config);
        if (save) {
            synchronized (this) {
                mysqlEpoch.incrementAndGet();
                try {
                    vault.put("mysql-revision", java.util.UUID.randomUUID().toString());
                    vault.put("mysql", json.writeValueAsString(config));
                } finally { mysqlEpoch.incrementAndGet(); }
            }
        }
    }
    public synchronized String mysqlRevision() { return vault.get("mysql-revision"); }
    public long mysqlEpoch() { return mysqlEpoch.get(); }
    public synchronized MysqlScopeMetadata mysqlScopeMetadata() {
        MysqlClient.Config config;
        String revision;
        try {
            config = mysqlConfig();
            revision = config == null ? null : mysqlRevision();
        } catch (RuntimeException failure) {
            mysqlEpoch.incrementAndGet();
            throw new LibraryException(500, "MySQL 연결 정보를 읽지 못했습니다.");
        }
        if (config != null && revision == null) {
            mysqlEpoch.incrementAndGet();
            throw new LibraryException(409, "MySQL 연결을 다시 저장하세요.");
        }
        return new MysqlScopeMetadata(revision, mysqlSession + "_" + mysqlEpoch.get());
    }
    public synchronized void disconnect(String provider) {
        if (!provider.equals("notion") && !provider.equals("mysql")) throw new LibraryException(404, "플러그인을 찾을 수 없습니다.");
        if (provider.equals("mysql")) {
            mysqlEpoch.incrementAndGet();
            try {
                vault.put("mysql-revision", java.util.UUID.randomUUID().toString());
                vault.put(provider, null);
            } finally { mysqlEpoch.incrementAndGet(); }
        } else vault.put(provider, null);
    }
}
