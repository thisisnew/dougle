package dev.dougle.transfer.domain;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record TransferManifest(int version, List<Document> documents) {
    public static final int VERSION = 1;

    public TransferManifest {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported manifest version");
        documents = List.copyOf(documents);
        if (documents.size() > TransferArchiveCodec.MAX_DOCUMENTS) throw new IllegalArgumentException("Too many documents");
        var identifiers = new HashSet<UUID>();
        long total = 0;
        for (var document : documents) {
            if (!identifiers.add(document.id())) throw new IllegalArgumentException("Duplicate document");
            total += document.size();
        }
        if (total > TransferArchiveCodec.MAX_TOTAL_BYTES) throw new IllegalArgumentException("Total document size exceeded");
    }

    public record Document(UUID id, int size, String sha256) {
        public Document {
            if (id == null || size < 0 || size > TransferArchiveCodec.MAX_DOCUMENT_BYTES
                    || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid document descriptor");
            }
        }

        public String path() { return "documents/" + id + ".bin"; }
    }
}
