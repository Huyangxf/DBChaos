package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Holds read-only transactions open while their database sessions are idle. */
public class IdleTransactionBuildupInject extends BaseFaultInject {
    private static final int MAX_TRANSACTIONS = 50;
    private static final int MAX_WORKERS = 32;
    private static final long MAX_DURATION_MS = 600000L;
    private static final long CONNECT_TIMEOUT_MS = 30000L;

    private final List<Connection> heldTransactions = Collections.synchronizedList(new ArrayList<Connection>());
    private final Object lifecycleLock = new Object();
    private boolean acceptingTransactions = true;

    public IdleTransactionBuildupInject(String dbType) {
        super(dbType, "IDLE_TRANSACTION_BUILDUP");
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

        String tableName = getArg(args, "-table");
        String countArg = getArg(args, "-count");
        String durationArg = getArg(args, "-duration");
        if (tableName == null || countArg == null || durationArg == null) {
            throw new IllegalArgumentException("缺少必填参数 -table、-count 或 -duration");
        }
        if (!tableName.matches("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?")) {
            throw new IllegalArgumentException("-table 仅支持普通表名或 schema.table 格式");
        }

        int count = Integer.parseInt(countArg);
        long durationMs = Long.parseLong(durationArg);
        long minHoldMs = parseLongArg(args, "-min-hold", Math.max(1L, durationMs / 2));
        if (count < 1 || count > MAX_TRANSACTIONS) {
            throw new IllegalArgumentException("-count 必须在 1 到 " + MAX_TRANSACTIONS + " 之间");
        }
        if (durationMs < 1 || durationMs > MAX_DURATION_MS) {
            throw new IllegalArgumentException("-duration 必须在 1 到 " + MAX_DURATION_MS + " 毫秒之间");
        }
        if ("ramp".equals(mode) && durationMs < count) {
            throw new IllegalArgumentException("ramp 模式下 -duration 不能小于 -count");
        }
        if ("random_hold".equals(mode) && (minHoldMs < 1 || minHoldMs > durationMs)) {
            throw new IllegalArgumentException("random_hold 模式下 -min-hold 必须在 1 到 -duration 之间");
        }

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger earlyReleases = new AtomicInteger();
        CountDownLatch attemptsDone = new CountDownLatch(count);
        ScheduledExecutorService openerPool = Executors.newScheduledThreadPool(Math.min(count, MAX_WORKERS));
        ScheduledExecutorService releasePool = Executors.newScheduledThreadPool(Math.min(count, MAX_WORKERS));

        System.out.println(">>> 启动空闲事务积压 | 模式: " + mode + " | 目标事务: " + count
                + " | 注入窗口: " + durationMs + " ms | 实验表: " + tableName);
        try {
            if ("ramp".equals(mode)) {
                scheduleRampTransactions(openerPool, attemptsDone, tableName, count, durationMs, successes, failures);
                Thread.sleep(durationMs);
                stopAcceptingTransactions();
                attemptsDone.await(5, TimeUnit.SECONDS);
                System.out.println("事务按注入窗口逐步建立；注入结束时回滚并释放剩余事务。");
            } else {
                scheduleTransactions(openerPool, attemptsDone, tableName, count, successes, failures);
                openerPool.shutdown();
                if (!attemptsDone.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    stopAcceptingTransactions();
                    openerPool.shutdownNow();
                    System.err.println("部分事务建立尝试超过 30 秒；仅保持已建立的事务。");
                }
                stopAcceptingTransactions();

                System.out.println("事务建立结果: 成功 " + successes.get() + "，失败 " + failures.get());
                if (successes.get() == 0) {
                    throw new SQLException("没有成功建立空闲事务，注入未生效；请检查上方错误信息");
                }
                if ("random_hold".equals(mode)) {
                    scheduleRandomReleases(releasePool, minHoldMs, durationMs, earlyReleases);
                } else {
                    System.out.println("事务已读取实验表并保持打开；等待期间不会继续执行 SQL。");
                }
                Thread.sleep(durationMs);
                if ("random_hold".equals(mode)) {
                    System.out.println("随机提前释放: " + earlyReleases.get() + " 个事务");
                }
            }
            System.out.println("事务建立结果: 成功 " + successes.get() + "，失败 " + failures.get());
            if (successes.get() == 0) {
                throw new SQLException("没有成功建立空闲事务，注入未生效；请检查上方错误信息");
            }
        } finally {
            stopAcceptingTransactions();
            int remaining = rollbackAndCloseTransactions();
            openerPool.shutdownNow();
            releasePool.shutdownNow();
            openerPool.awaitTermination(5, TimeUnit.SECONDS);
            releasePool.awaitTermination(5, TimeUnit.SECONDS);
            System.out.println("注入结束，已尝试回滚并释放剩余事务: " + remaining + " 个");
        }
    }

    @Override
    public void printHelp() {
        System.out.println("\n不利 Case 用法: session idle_transaction_buildup");
        System.out.println("读取专用实验表的一行后保持事务打开且会话空闲，模拟应用事务未及时结束。");
        System.out.println("使用 REPEATABLE READ 只读事务，不执行 FOR UPDATE；普通查询仍会持有表级读锁/元数据锁。");
        System.out.println("等待期间不写入数据；注入结束时回滚并关闭连接。MySQL/MariaDB 实验表必须使用 InnoDB。");
        System.out.println("参数:");
        System.out.println("  -mode      选填。fixed | ramp | random_hold (默认 fixed)");
        System.out.println("  -table     必填。已有且至少有一行的实验表，可用 schema.table 格式");
        System.out.println("  -count     必填。事务数 (1-" + MAX_TRANSACTIONS + ")");
        System.out.println("  -duration  必填。注入窗口 (1-" + MAX_DURATION_MS + " ms)");
        System.out.println("  -min-hold  random_hold 选填。事务最短保持时间 (ms，默认 duration/2)");
        System.out.println("模式:");
        System.out.println("  fixed       并发建立事务，保持整个注入窗口，到期统一回滚");
        System.out.println("  ramp        在注入窗口内逐步建立事务，窗口结束时回滚剩余事务");
        System.out.println("  random_hold 并发建立事务，每个事务随机保持 min-hold 到 duration 后回滚");
        System.out.println("示例:");
        System.out.println("  java -jar DBChaos-0.0.1.jar --db postgresql session idle_transaction_buildup -table dbchaos_txn_demo -mode fixed -count 5 -duration 30000");
    }

    private void scheduleTransactions(ScheduledExecutorService executor, CountDownLatch done, String table,
                                     int count, AtomicInteger successes, AtomicInteger failures) {
        for (int i = 0; i < count; i++) {
            executor.execute(() -> openAndRetain(done, table, successes, failures));
        }
    }

    private void scheduleRampTransactions(ScheduledExecutorService executor, CountDownLatch done, String table,
                                          int count, long durationMs, AtomicInteger successes, AtomicInteger failures) {
        for (int i = 0; i < count; i++) {
            long delayMs = durationMs * i / count;
            executor.schedule(() -> openAndRetain(done, table, successes, failures), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void openAndRetain(CountDownLatch done, String table, AtomicInteger successes, AtomicInteger failures) {
        Connection conn = null;
        try {
            conn = getConnection();
            // Pin a data snapshot rather than merely keeping an empty transaction open.
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(10);
                try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + table + " LIMIT 1")) {
                    if (!rs.next()) {
                        throw new SQLException("实验表为空，至少需要一行数据");
                    }
                }
            }
            if (retainTransaction(conn)) {
                successes.incrementAndGet();
                conn = null;
            } else {
                failures.incrementAndGet();
            }
        } catch (SQLException e) {
            failures.incrementAndGet();
            System.err.println("事务建立失败 [SQLState=" + e.getSQLState() + "]: " + e.getMessage());
        } finally {
            rollbackAndClose(conn);
            done.countDown();
        }
    }

    private void scheduleRandomReleases(ScheduledExecutorService executor, long minHoldMs, long durationMs,
                                        AtomicInteger earlyReleases) {
        List<Connection> snapshot;
        synchronized (heldTransactions) {
            snapshot = new ArrayList<Connection>(heldTransactions);
        }
        for (Connection conn : snapshot) {
            long holdMs = minHoldMs == durationMs
                    ? durationMs
                    : ThreadLocalRandom.current().nextLong(minHoldMs, durationMs + 1);
            executor.schedule(() -> {
                if (closeTrackedTransaction(conn)) earlyReleases.incrementAndGet();
            }, holdMs, TimeUnit.MILLISECONDS);
        }
    }

    private boolean retainTransaction(Connection conn) {
        synchronized (lifecycleLock) {
            if (!acceptingTransactions) return false;
            heldTransactions.add(conn);
            return true;
        }
    }

    private void stopAcceptingTransactions() {
        synchronized (lifecycleLock) {
            acceptingTransactions = false;
        }
    }

    private boolean closeTrackedTransaction(Connection conn) {
        synchronized (lifecycleLock) {
            if (!heldTransactions.remove(conn)) return false;
            rollbackAndClose(conn);
            return true;
        }
    }

    private int rollbackAndCloseTransactions() {
        synchronized (lifecycleLock) {
            synchronized (heldTransactions) {
                int released = heldTransactions.size();
                for (Connection conn : heldTransactions) rollbackAndClose(conn);
                heldTransactions.clear();
                return released;
            }
        }
    }

    private void rollbackAndClose(Connection conn) {
        if (conn == null) return;
        try {
            if (!conn.getAutoCommit()) conn.rollback();
        } catch (SQLException e) {
            System.err.println("事务回滚失败: " + e.getMessage());
        }
        try {
            conn.close();
        } catch (SQLException e) {
            System.err.println("事务连接关闭失败: " + e.getMessage());
        }
    }

    private long parseLongArg(String[] args, String key, long defaultValue) {
        String raw = getArg(args, key);
        return raw == null ? defaultValue : Long.parseLong(raw);
    }
}
