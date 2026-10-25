package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.plugins.adapter.PluginConnections;
import dev.knowledgeforge.plugins.adapter.MysqlClient;
import dev.knowledgeforge.sources.application.Collector;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import org.springframework.stereotype.Component;

@Component
public class MysqlCollector implements Collector {
    private final PluginConnections connections;
    private final MysqlClient mysql;
    public MysqlCollector(PluginConnections connections, MysqlClient mysql) { this.connections = connections; this.mysql = mysql; }
    public String kind() { return "MYSQL"; }
    public void validate(String location) {
        var config = connections.mysqlConfig();
        if (config == null || !config.location().equals(location)) throw new LibraryException(409, "MySQL 연결이 필요합니다. 플러그인에서 해당 데이터베이스를 연결하세요.");
    }
    public CollectedSnapshot collect(String location) {
        var config = connections.mysqlConfig();
        if (config == null || !config.location().equals(location)) throw new LibraryException(409, "MySQL 연결이 필요합니다. 플러그인에서 해당 데이터베이스를 연결하세요.");
        return mysql.schema(config);
    }
}
