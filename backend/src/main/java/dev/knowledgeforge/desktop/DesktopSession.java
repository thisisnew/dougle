package dev.knowledgeforge.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class DesktopSession {
    private final byte[] token;
    private volatile boolean ready;
    private boolean parentEnded;

    private DesktopSession(byte[] token) {
        this.token = token;
    }

    public static DesktopSession disabled() {
        return new DesktopSession(null);
    }

    public static DesktopSession read(InputStream input) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon().name("desktop-bootstrap").start(() -> {
            try {
                result.complete(readLine(input));
            } catch (Exception exception) {
                result.completeExceptionally(new IllegalStateException("Invalid desktop bootstrap"));
            }
        });
        try {
            return new DesktopSession(result.get(10, TimeUnit.SECONDS));
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Desktop stdin bootstrap failed");
        }
    }

    private static byte[] readLine(InputStream input) throws IOException {
        byte[] value = new byte[64];
        for (int index = 0; index < value.length; index++) {
            int character = input.read();
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F'))) {
                throw new IOException("Invalid desktop bootstrap");
            }
            value[index] = (byte) character;
        }
        int ending = input.read();
        if (ending == '\r') ending = input.read();
        if (ending != '\n') throw new IOException("Invalid desktop bootstrap");
        return value;
    }

    public boolean enabled() {
        return token != null;
    }

    public boolean authenticates(String candidate) {
        return candidate != null && candidate.length() == 64 && token != null
                && MessageDigest.isEqual(token, candidate.getBytes(StandardCharsets.US_ASCII));
    }

    public boolean ready() {
        return ready;
    }

    synchronized boolean markReady() {
        if (parentEnded) return false;
        ready = true;
        return true;
    }

    public Thread watchParent(InputStream input, Runnable shutdown) {
        return Thread.ofPlatform().daemon().name("desktop-parent-watch").start(() -> {
            try {
                while (input.read() != -1) { }
            } catch (IOException ignored) {
            } finally {
                synchronized (this) {
                    parentEnded = true;
                    ready = false;
                }
                shutdown.run();
            }
        });
    }
}
