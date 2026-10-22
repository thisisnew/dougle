package dev.knowledgeforge.sources.application;

import java.net.URI;
import dev.knowledgeforge.sources.domain.HttpValidators;

public interface RemoteReader {
    record Response(URI uri, String mediaType, byte[] content, boolean notModified, HttpValidators validators) {
        public Response(URI uri, String mediaType, byte[] content) { this(uri, mediaType, content, false, null); }
    }
    Response read(URI uri) throws Exception;
    default Response read(URI uri, HttpValidators validators) throws Exception { return read(uri); }
}
