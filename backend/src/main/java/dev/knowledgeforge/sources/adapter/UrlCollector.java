package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.sources.application.Collector;
import dev.knowledgeforge.sources.application.RemoteReader;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.plugins.domain.PluginProvider;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class UrlCollector implements Collector {
    private final RemoteReader remote;
    private final dev.knowledgeforge.plugins.adapter.NotionClient notion;
    private final dev.knowledgeforge.plugins.adapter.PluginConnections connections;
    private final dev.knowledgeforge.plugins.adapter.BrowserPluginReader browser;

    public UrlCollector(RemoteReader remote, dev.knowledgeforge.plugins.adapter.NotionClient notion,
                        dev.knowledgeforge.plugins.adapter.PluginConnections connections,
                        dev.knowledgeforge.plugins.adapter.BrowserPluginReader browser) {
        this.remote = remote; this.notion = notion; this.connections = connections; this.browser = browser;
    }
    public String kind() { return "URL"; }
    public boolean usesPreviousSnapshot() { return true; }
    public void validate(String location) { PublicHttpReader.validate(location); }

    public CollectedSnapshot collect(String location) throws Exception {
        return collect(location, null);
    }

    public CollectedSnapshot collect(String location, CollectedSnapshot previous) throws Exception {
        var uri = PublicHttpReader.validate(location);
        var provider = PluginProvider.forWebLink(uri).orElse(null);
        if (provider == PluginProvider.ATLASSIAN || (provider == PluginProvider.NOTION && browser.connected(dev.knowledgeforge.plugins.adapter.BrowserProvider.NOTION))) {
            return browser.collect(dev.knowledgeforge.plugins.adapter.BrowserProvider.valueOf(provider.name()), location);
        }
        if (PluginProvider.forWebLink(uri).orElse(null) == PluginProvider.NOTION) {
            return notion.collect(location, connections.notionToken());
        }
        var response = previous != null && previous.validators() != null
                ? remote.read(uri, previous.validators()) : remote.read(uri);
        if (response.notModified()) {
            if (previous == null || previous.validators() == null || !response.uri().toString().equals(previous.validators().uri())) {
                throw new LibraryException(502, "304 응답에 대응하는 저장 원본이 없습니다.");
            }
            return new CollectedSnapshot(previous.revision(), previous.files(), response.validators(), previous.failures());
        }
        String name = response.uri().getPath();
        name = name.substring(name.lastIndexOf('/') + 1);
        if (response.mediaType() != null && response.mediaType().contains("html")) name = "page.html";
        else if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}")) name = "document";
        var snapshot = CollectionLimits.snapshot(List.of(new CollectedSnapshot.File(name, response.content())));
        return new CollectedSnapshot(snapshot.revision(), snapshot.files(), response.validators());
    }
}
