package dev.dougle.investigation.adapter;

import dev.dougle.library.application.LibraryException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ConversationAuthorityChecks implements AutoCloseable {
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon(true).name("conversation-authority-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    ConversationAuthorityChecks() { workers.allowCoreThreadTimeOut(true); }

    static final class Budget {
        private long remaining;
        private final long deadline;
        Budget(Duration maximumChecks, Duration maximumRequest) {
            remaining = maximumChecks.toNanos();
            deadline = System.nanoTime() + maximumRequest.toNanos();
        }
    }

    <Value> Value call(Budget budget, Callable<Value> action) {
        synchronized (budget) {
            long started = System.nanoTime();
            long allowance = Math.min(budget.remaining, budget.deadline - started);
            if (allowance <= 0) throw timeout();
            java.util.concurrent.Future<Value> future;
            try { future = workers.submit(action); }
            catch (RejectedExecutionException busy) { throw new LibraryException(503, "대화 범위를 확인 중입니다. 잠시 후 다시 시도하세요."); }
            try {
                Value value = future.get(allowance, TimeUnit.NANOSECONDS);
                if (System.nanoTime() - started >= allowance) throw timeout();
                return value;
            } catch (TimeoutException failure) {
                throw timeout();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LibraryException(503, "대화 범위 확인이 중단되었습니다.");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new LibraryException(503, "대화 범위를 확인하지 못했습니다.");
            } finally {
                budget.remaining -= System.nanoTime() - started;
                future.cancel(true);
            }
        }
    }

    private static LibraryException timeout() { return new LibraryException(504, "대화 범위 확인 제한 시간을 초과했습니다."); }
    @Override public void close() { workers.shutdownNow(); }
}
