package dev.dougle.knowledge.application;

import dev.dougle.library.application.LibraryException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class CliExecutionGate {
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock(true);
    private CliExecutionGate() {}

    public interface Lease extends AutoCloseable { @Override void close(); }

    public static Lease read() { return acquire(LOCK.readLock()); }
    public static Lease change() { return acquire(LOCK.writeLock()); }

    private static Lease acquire(Lock lock) {
        if (!lock.tryLock()) throw new LibraryException(409, "AI 실행 또는 연결 확인 중입니다. 작업이 끝난 뒤 다시 연결하세요.");
        return lock::unlock;
    }
}
