package dev.knowledgeforge.attachment.application;

public interface AttachmentReader {
    record Parsed(String mediaType, byte[] content, String text, boolean truncated) {}
    Parsed read(String name, byte[] content);
}
