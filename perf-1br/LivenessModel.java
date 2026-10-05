import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
// Owner.release/requireLive: original (always borrow), flag-after-lock (patch 2 v2) and
// request counter before the lock (v3), under (a) a free in progress, (b) a free queued
// behind a held borrow, (c) a failing free.
public class LivenessModel {
    static final class Owner {
        final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock(true);
        boolean closed; volatile boolean releasing; final AtomicInteger requests = new AtomicInteger();
        final boolean freeFails; final String version;
        final CountDownLatch inFree = new CountDownLatch(1), proceed = new CountDownLatch(1);
        Owner(boolean freeFails, String version) { this.freeFails = freeFails; this.version = version; }
        void borrowCheck() { lifetime.readLock().lock(); try { if (closed) throw new IllegalStateException("freed"); } finally { lifetime.readLock().unlock(); } }
        void release() throws Exception {
            requests.incrementAndGet();
            lifetime.writeLock().lock();
            try {
                if (closed) return;
                releasing = true;
                try { inFree.countDown(); proceed.await(); if (freeFails) throw new IllegalStateException("LocalFree failed"); }
                catch (IllegalStateException failure) { releasing = false; throw failure; }
                closed = true;
            } finally { if (!closed) requests.decrementAndGet(); lifetime.writeLock().unlock(); }
        }
        void requireLive() {
            switch (version) {
                case "original" -> borrowCheck();
                case "flag" -> { if (releasing) borrowCheck(); }
                default -> { if (requests.get() != 0) borrowCheck(); }
            }
        }
    }
    static String outcome(Future<String> check) throws Exception {
        try { return check.get(); } catch (ExecutionException e) { return "fault"; }
    }
    static String run(String version, String scenario) throws Exception {
        var owner = new Owner(scenario.equals("failing free"), version); var pool = Executors.newCachedThreadPool();
        Future<?> releaser;
        Future<String> check;
        if (scenario.equals("queued free")) {
            owner.lifetime.readLock().lock();                       // a held borrow
            releaser = pool.submit(() -> { owner.release(); return null; });
            while (!owner.lifetime.hasQueuedThreads()) Thread.onSpinWait();
            check = pool.submit(() -> { owner.requireLive(); return "LIVE"; });
            String early; try { early = check.get(300, TimeUnit.MILLISECONDS) + " while the free was queued"; } catch (TimeoutException blocked) { early = null; }
            owner.lifetime.readLock().unlock(); owner.proceed.countDown(); releaser.get();
            pool.shutdown(); return early != null ? early : "blocked, then " + outcome(check);
        }
        releaser = pool.submit(() -> { try { owner.release(); } catch (IllegalStateException e) { } return null; });
        owner.inFree.await();
        check = pool.submit(() -> { owner.requireLive(); return "LIVE"; });
        String early; try { early = check.get(300, TimeUnit.MILLISECONDS) + " during the free"; } catch (TimeoutException blocked) { early = null; }
        owner.proceed.countDown(); releaser.get();
        pool.shutdown(); return early != null ? early : "blocked, then " + outcome(check);
    }
    public static void main(String[] a) throws Exception {
        for (var scenario : new String[] {"free in progress", "queued free", "failing free"})
            for (var version : new String[] {"original", "flag", "counter"})
                System.out.printf("%-17s %-9s %s%n", scenario, version, run(version, scenario));
    }
}
