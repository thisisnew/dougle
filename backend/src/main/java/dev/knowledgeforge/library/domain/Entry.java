package dev.knowledgeforge.library.domain;

public record Entry(String id, String workspaceId, String parentId, String name,
                    String kind, String area, long size, String createdAt) {}
