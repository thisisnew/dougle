package dev.dougle.library.application;

public class LibraryException extends RuntimeException {
    private final int status;

    public LibraryException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
