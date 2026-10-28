package dev.dougle.knowledge.application;

import dev.dougle.knowledge.domain.Publication.*;
import java.util.Optional;

public interface PublicationStore {
    Optional<Review> review(String cardId, int version);
    Optional<Release> publication(String cardId);
    void review(String cardId, Review review);
    boolean publish(String cardId, int expectedVersion, int expectedRevision, String publishedAt);
}
