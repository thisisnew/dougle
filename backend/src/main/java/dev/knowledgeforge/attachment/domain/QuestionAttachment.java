package dev.knowledgeforge.attachment.domain;

public record QuestionAttachment(String id, String workspaceId, String bundleId, String name, String mediaType,
                                 byte[] content, String contentHash, String text, boolean truncated, String createdAt) {
    public QuestionAttachment { content = content.clone(); }
    @Override public byte[] content() { return content.clone(); }
    public boolean image() { return mediaType.equals("image/png") || mediaType.equals("image/jpeg"); }
    public Summary summary() { return new Summary(id, name, mediaType, content.length, text.length(), truncated); }
    public record Summary(String id, String name, String mediaType, int size, int characters, boolean truncated) {}
}
