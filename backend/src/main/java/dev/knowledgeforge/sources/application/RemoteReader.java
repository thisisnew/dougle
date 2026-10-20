package dev.knowledgeforge.sources.application;

import java.net.URI;

public interface RemoteReader {
    record Response(URI uri, String mediaType, byte[] content) {}
    Response read(URI uri) throws Exception;
}
