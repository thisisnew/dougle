package dev.dougle.search.application;

import java.util.List;

public interface RelationCatalog {
    record Related(String kind, String id, String title, Integer version, String path,
                   List<String> domains, String relation, String detail) {}
    boolean exists(String workspaceId, String kind, String id, Integer version);
    List<Related> related(String workspaceId, String kind, String id, Integer version, List<String> domains);
}
