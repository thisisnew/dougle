package dev.knowledgeforge.search.application;

public interface TextEmbedding {
    boolean ready();
    default boolean preparing() { return false; }
    float[] encode(String text, boolean question);
    default float relevance(String question, String text) { return Float.NaN; }
    TextEmbedding NONE = new TextEmbedding() {
        public boolean ready() { return false; }
        public float[] encode(String text, boolean question) { return null; }
    };
}
