package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.sources.application.Collector;
import dev.knowledgeforge.sources.application.RemoteReader;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class UrlCollector implements Collector {
    private final RemoteReader remote;

    public UrlCollector(RemoteReader remote) { this.remote = remote; }
    public String kind() { return "URL"; }
    public void validate(String location) { PublicHttpReader.validate(location); }

    public CollectedSnapshot collect(String location) throws Exception {
        var response = remote.read(PublicHttpReader.validate(location));
        String name = response.uri().getPath();
        name = name.substring(name.lastIndexOf('/') + 1);
        if (response.mediaType() != null && response.mediaType().contains("html")) name = "page.html";
        else if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}")) name = "document";
        return CollectionLimits.snapshot(List.of(new CollectedSnapshot.File(name, response.content())));
    }
}
