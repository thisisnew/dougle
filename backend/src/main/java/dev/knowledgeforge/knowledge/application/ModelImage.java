package dev.knowledgeforge.knowledge.application;

public record ModelImage(String label, byte[] content) {
    public ModelImage {
        if (label == null || !label.matches("R[1-9][0-9]*") || content == null || content.length == 0 || content.length > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid image input");
        }
        content = content.clone();
    }
    @Override public byte[] content() { return content.clone(); }
}
