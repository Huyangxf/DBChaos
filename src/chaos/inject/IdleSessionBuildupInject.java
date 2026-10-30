package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds idle database sessions to model leaked or overly retained pool connections.
 */
public class IdleSessionBuildupInject extends BaseFaultInject {
    private static final int MAX_CONNECTIONS = 200;
    private static final int MAX_CONNECT_WORKERS = 32;
    private static final long CONNECT_TIMEOUT_MS = 30000L;
    private static final long MAX_DURATION_MS = 86400000L;

    private final List<Connection> heldConnections = Collections.synchronizedList(new ArrayList<Connection>());
    private final Object lifecycleLock = new Object();
    private boolean acceptingConnections = true;

    public IdleSessionBuildupInject(String dbType) {
        super(dbType, "IDLE_SESSION_BUILDUP");
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        String mode = getArg(args, "-mode");
        if (mode == null) mode = "fixed";
        mode = mode.toLowerCase();
        if (!"fixed".equals(mode) && !"ramp".equals(mode) && !"random_hold".equals(mode)) {
            throw new IllegalArgumentException("-mode 仅支持 fixed、ramp、random_hold");
        }

        String countArg = getArg(args, "-count");
        String durationArg = getArg(args, "-duration");
        if (countArg == null || durationArg == null) {
            throw new IllegalArgumentException("缺少必填参数 -count 或 -duration");
        }

        int count = Integer.parseInt(countArg);
        long durationMs = Long.parseLong(durationArg);
        long minHoldMs = parseLongArg(args, "-min-hold", Math.max(1L, durationMs / 2));
        if (count < 1 || count > MAX_CONNECTIONS) {
            throw new IllegalArgumentException("-count 必须在 1 到 " + MAX_CONNECTIONS + " 之间");
        }
        if (durationMs < 1 || durationMs > MAX_DURATION_MS) {
            throw new IllegalArgumentException("-duration 必须在 1 到 86400000 毫秒之间");
        }
        if ("ramp".equals(mode) && durationMs < count) {
            throw new IllegalArgumentException("ramp 模式下 -duration 不能小于 -count，需为每个连接留出递增间隔");
        }
        if ("random_hold".equals(mode) && (minHoldMs < 1 || minHoldMs > durationMs)) {
            throw new IllegalArgumentException("random_hold 模式下 -min-hold 必须在 1 到 -duration 之间");
        }

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger earlyReleases = new AtomicInteger();
        CountDownLatch attemptsDone = new CountDownLatch(count);
        ScheduledExecutorService connectorPool = Executors.newScheduledThreadPool(Math.min(count, MAX_CONNECT_WORKERS));
        ScheduledExecutorService releasePool = Executors.newScheduledThreadPool(Math.min(count, MAX_CONNECT_WORKERS));

        System.out.println(">>> 启动空闲会话积压 | 模式: " + mode + " | 目标连接: " + count + " | 注入窗口: " + durationMs + " ms");
        try {
            if ("ramp".equals(mode)) {
                scheduleRampConnections(connectorPool, attemptsDone, count, durationMs, successes, failures);
                Thread.sleep(durationMs);
                stopAcceptingConnections();
                attemptsDone.await(5, TimeUnit.SECONDS);
            } else {
                scheduleConnections(connectorPool, attemptsDone, count, successes, failures);
                connectorPool.shutdown();
                if (!attemptsDone.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    stopAcceptingConnections();
                    connectorPool.shutdownNow();
                    System.err.println("部分连接尝试超过 30 秒；仅保持已建立的连接。");
                }

                System.out.println("连接建立结果: 成功 " + successes.get() + "，失败 " + failures.get());
                if ("random_hold".equals(mode)) {
                    scheduleRandomReleases(releasePool, minHoldMs, durationMs, earlyReleases);
                } else {
                    System.out.println("保持这些会话空闲；注入期间不会向数据库发送 SQL。");
                }
                Thread.sleep(durationMs);
            }

            System.out.println("连接建立结果: 成功 " + successes.get() + "，失败 " + failures.get());
            if ("ramp".equals(mode)) {
                System.out.println("连接按注入窗口逐步建立；注入结束时释放剩余会话。");
            } else if ("random_hold".equals(mode)) {
                System.out.println("随机提前释放: " + earlyReleases.get() + " 个会话");
            }
        } finally {
            stopAcceptingConnections();
            int remaining = releaseConnections();
            connectorPool.shutdownNow();
            releasePool.shutdownNow();
            connectorPool.awaitTermination(5, TimeUnit.SECONDS);
            releasePool.awaitTermination(5, TimeUnit.SECONDS);
            System.out.println("注入结束，已尝试释放剩余会话: " + remaining + " 个");
        }
    }

    @Override
    public void printHelp() {
        System.out.println("\n不利 Case 用法: session idle_session_buildup");
        System.out.println("建立空闲数据库连接，模拟连接池泄漏或会话长期闲置。");
        System.out.println("参数:");
        System.out.println("  -mode      选填。fixed | ramp | random_hold (默认 fixed)");
        System.out.println("  -count     必填。目标连接数 (1-" + MAX_CONNECTIONS + ")");
        System.out.println("  -duration  必填。注入窗口 (1-86400000 ms)");
        System.out.println("  -min-hold  random_hold 模式选填。单个会话最短保持时间 (ms，默认 duration/2)");
        System.out.println("模式说明:");
        System.out.println("  fixed       并发建立连接，保持整个注入窗口，到期统一释放");
        System.out.println("  ramp        在注入窗口内逐步建立连接，窗口结束时释放剩余连接");
        System.out.println("  random_hold 并发建立连接，每个连接随机保持 min-hold 到 duration 后释放");
        System.out.println("示例:");
        System.out.println("  java -jar DBChaos-0.0.1.jar --db opengauss session idle_session_buildup -mode fixed -count 20 -duration 60000");
        System.out.println("  java -jar DBChaos-0.0.1.jar --db opengauss session idle_session_buildup -mode ramp -count 20 -duration 60000");
        System.out.println("  java -jar DBChaos-0.0.1.jar --db opengauss session idle_session_buildup -mode random_hold -count 20 -duration 60000 -min-hold 10000");
    }

    private void scheduleConnections(
            ScheduledExecutorService executor,
            CountDownLatch attemptsDone,
            int count,
            AtomicInteger successes,
            AtomicInteger failures
    ) {
        for (int i = 0; i < count; i++) {
            executor.execute(() -> openAndRetain(attemptsDone, successes, failures));
        }
    }

    private void scheduleRampConnections(
            ScheduledExecutorService executor,
            CountDownLatch attemptsDone,
            int count,
            long durationMs,
            AtomicInteger successes,
            AtomicInteger failures
    ) {
        for (int i = 0; i < count; i++) {
            long delayMs = durationMs * i / count;
            executor.schedule(
                    () -> openAndRetain(attemptsDone, successes, failures),
                    delayMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void openAndRetain(CountDownLatch attemptsDone, AtomicInteger successes, AtomicInteger failures) {
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(true);
            if (retainConnection(conn)) {
                successes.incrementAndGet();
                conn = null;
            } else {
                failures.incrementAndGet();
            }
        } catch (SQLException e) {
            failures.incrementAndGet();
        } finally {
            closeQuietly(conn);
            attemptsDone.countDown();
        }
    }

    private void scheduleRandomReleases(
            ScheduledExecutorService executor,
            long minHoldMs,
            long durationMs,
            AtomicInteger earlyReleases
    ) {
        List<Connection> snapshot;
        synchronized (heldConnections) {
            snapshot = new ArrayList<Connection>(heldConnections);
        }
        for (Connection conn : snapshot) {
            long holdMs = minHoldMs == durationMs
                    ? durationMs
                    : ThreadLocalRandom.current().nextLong(minHoldMs, durationMs + 1);
            executor.schedule(() -> {
                if (closeTrackedConnection(conn)) {
                    earlyReleases.incrementAndGet();
                }
            }, holdMs, TimeUnit.MILLISECONDS);
        }
    }

    private boolean retainConnection(Connection conn) {
        synchronized (lifecycleLock) {
            if (!acceptingConnections) return false;
            heldConnections.add(conn);
            return true;
        }
    }

    private void stopAcceptingConnections() {
        synchronized (lifecycleLock) {
            acceptingConnections = false;
        }
    }

    private boolean closeTrackedConnection(Connection conn) {
        synchronized (lifecycleLock) {
            if (!heldConnections.remove(conn)) return false;
            closeQuietly(conn);
            return true;
        }
    }

    private int releaseConnections() {
        synchronized (lifecycleLock) {
            synchronized (heldConnections) {
                int released = heldConnections.size();
                for (Connection conn : heldConnections) {
                    closeQuietly(conn);
                }
                heldConnections.clear();
                return released;
            }
        }
    }

    private long parseLongArg(String[] args, String key, long defaultValue) {
        String raw = getArg(args, key);
        return raw == null ? defaultValue : Long.parseLong(raw);
    }

    private void closeQuietly(Connection conn) {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException ignored) {
            // Continue closing the remaining held sessions.
        }
    }
}
