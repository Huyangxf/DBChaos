package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates target sessions and terminates them from a separate control connection.
 * This tests server-side session cleanup; it does not implement client reconnects.
 */
public class SessionForcedTerminationInject extends BaseFaultInject {
    private static final int MAX_SESSIONS = 100;
    private static final int MAX_CONCURRENCY = 32;
    private static final long CONNECT_TIMEOUT_MS = 30000L;

    private final List<TargetSession> targets = Collections.synchronizedList(new ArrayList<TargetSession>());
    private final AtomicInteger establishedCount = new AtomicInteger();
    private final AtomicInteger terminatedCount = new AtomicInteger();
    private final AtomicInteger failedTerminationCount = new AtomicInteger();
    private volatile long metricStartNanos;

    public SessionForcedTerminationInject(String dbType) {
        super(dbType, "SESSION_FORCED_TERMINATION");
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        String mode = getArg(args, "-mode");
        if (mode == null) mode = "serial";
        mode = mode.toLowerCase();
        if (!"serial".equals(mode) && !"burst".equals(mode)) {
            throw new IllegalArgumentException("-mode 仅支持 serial、burst");
        }

        int count = parseIntArg(args, "-count", 10);
        int intervalMs = parseIntArg(args, "-interval", 500);
        int concurrency = parseIntArg(args, "-concurrency", Math.min(count, 10));
        if (count < 1 || count > MAX_SESSIONS) {
            throw new IllegalArgumentException("-count 必须在 1 到 " + MAX_SESSIONS + " 之间");
        }
        if (intervalMs < 0 || intervalMs > 60000) {
            throw new IllegalArgumentException("-interval 必须在 0 到 60000 毫秒之间");
        }
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalArgumentException("-concurrency 必须在 1 到 " + MAX_CONCURRENCY + " 之间");
        }

        String controlUser = getArg(args, "-control-user");
        String controlPassword = getArg(args, "-control-password");
        if (controlUser == null) controlUser = user;
        if (controlPassword == null) controlPassword = password;

        metricStartNanos = System.nanoTime();
        publishMetric("start");
        System.out.println(">>> 启动会话强制终止 | 模式: " + mode + " | 目标会话: " + count
                + " | 目标数据库: " + dbType);
        System.out.println("目标会话由 DBChaos 建立；控制连接将尝试终止这些会话，不执行应用重连。");

        try {
            establishTargets(mode, count, intervalMs, concurrency);
            System.out.println("目标会话建立结果: 成功 " + targets.size() + "，失败 " + (count - targets.size()));
            if (targets.isEmpty()) {
                throw new SQLException("没有建立任何目标会话，无法执行终止注入");
            }

            int terminated;
            terminated = terminateTargets(mode, intervalMs, concurrency, controlUser, controlPassword);
            int alive = countAliveTargets();
            System.out.println("终止结果: 成功 " + terminated + "，仍可用目标会话: " + alive);
            if (terminated == 0) {
                throw new SQLException("没有成功终止目标会话；请确认控制账号具有终止其他会话的权限");
            }
        } finally {
            closeTargets();
            System.out.println("注入结束，已清理 DBChaos 创建的目标连接");
        }
    }

    @Override
    public void printHelp() {
        System.out.println("\n不利 Case 用法: session session_forced_termination");
        System.out.println("由 DBChaos 建立目标会话，再通过独立控制连接终止它们；不实现应用或连接池重连。");
        System.out.println("参数:");
        System.out.println("  -mode             选填。serial | burst (默认 serial)");
        System.out.println("  -count            选填。目标会话数 (1-" + MAX_SESSIONS + ", 默认 10)");
        System.out.println("  -interval         serial 模式选填。终止间隔 (0-60000 ms, 默认 500)");
        System.out.println("  -concurrency      burst 模式选填。并发终止数 (1-" + MAX_CONCURRENCY + ", 默认 min(count, 10))");
        System.out.println("  -control-user     选填。控制连接用户名，默认使用 db.properties 用户");
        System.out.println("  -control-password 选填。控制连接密码，默认使用 db.properties 密码");
        System.out.println("模式:");
        System.out.println("  serial  逐个终止目标会话，模拟会话陆续被清理");
        System.out.println("  burst   并发终止目标会话，模拟短时大量会话断开");
        System.out.println("权限:");
        System.out.println("  PostgreSQL/openGauss 需要控制账号有终止其他会话的权限；MySQL/MariaDB 需要 KILL 权限。");
        System.out.println("示例:");
        System.out.println("  java -jar DBChaos-0.0.1.jar --db postgresql session session_forced_termination -mode serial -count 10 -interval 1000");
        System.out.println("  java -jar DBChaos-0.0.1.jar --db mysql session session_forced_termination -mode burst -count 20 -concurrency 10");
    }

    private void establishTargets(String mode, int count, int intervalMs, int concurrency) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, concurrency));
        CountDownLatch done = new CountDownLatch(count);
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                long delay = "serial".equals(mode) ? (long) i * intervalMs : 0L;
                pool.execute(new DelayedTargetTask(index, delay, done));
            }
            if (!done.await(CONNECT_TIMEOUT_MS + (long) intervalMs * count, TimeUnit.MILLISECONDS)) {
                throw new SQLException("目标会话建立超时");
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private int terminateTargets(String mode, int intervalMs, int concurrency,
                                 String controlUser, String controlPassword) throws Exception {
        List<TargetSession> snapshot;
        synchronized (targets) {
            snapshot = new ArrayList<TargetSession>(targets);
        }
        if ("serial".equals(mode)) {
            int terminated = 0;
            for (TargetSession target : snapshot) {
                try (Connection control = java.sql.DriverManager.getConnection(url, controlUser, controlPassword)) {
                    if (terminateOne(control, target)) {
                        terminated++;
                        recordTermination(true);
                    } else {
                        recordTermination(false);
                    }
                } catch (SQLException e) {
                    recordTermination(false);
                    throw e;
                }
                if (intervalMs > 0) Thread.sleep(intervalMs);
            }
            return terminated;
        }

        final AtomicInteger terminated = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(snapshot.size(), concurrency));
        try {
            for (final TargetSession target : snapshot) {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            try (Connection control = java.sql.DriverManager.getConnection(url, controlUser, controlPassword)) {
                                if (terminateOne(control, target)) {
                                    terminated.incrementAndGet();
                                    recordTermination(true);
                                } else {
                                    recordTermination(false);
                                }
                            }
                        } catch (SQLException ignored) {
                            // Counted as an unsuccessful termination and reported below.
                            recordTermination(false);
                        }
                    }
                });
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
        return terminated.get();
    }

    private boolean terminateOne(Connection control, TargetSession target) throws SQLException {
        if ("mysql".equals(getStandardDbType())) {
            if (target.id < 0) return false;
            try (Statement stmt = control.createStatement()) {
                stmt.execute("KILL CONNECTION " + target.id);
                return true;
            }
        }

        String terminateSql = "opengauss".equalsIgnoreCase(dbType)
                ? "SELECT pg_terminate_backend(?::bigint)"
                : "SELECT pg_terminate_backend(?::integer)";
        try (PreparedStatement stmt = control.prepareStatement(terminateSql)) {
            stmt.setLong(1, target.id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private int countAliveTargets() {
        int alive = 0;
        synchronized (targets) {
            for (TargetSession target : targets) {
                try {
                    if (!target.connection.isClosed() && target.connection.isValid(2)) alive++;
                } catch (SQLException ignored) {
                    // Treat an unusable target as terminated.
                }
            }
        }
        return alive;
    }

    private void openTarget() throws SQLException {
        Connection connection = getConnection();
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(
                "mysql".equals(getStandardDbType()) ? "SELECT CONNECTION_ID()" : "SELECT pg_backend_pid()")) {
            if (!rs.next()) throw new SQLException("无法获取目标会话 ID");
            long id = rs.getLong(1);
            targets.add(new TargetSession(id, connection));
            establishedCount.incrementAndGet();
            connection = null;
            publishMetric("target_open");
        } finally {
            if (connection != null) closeQuietly(connection);
        }
    }

    private void closeTargets() {
        synchronized (targets) {
            for (TargetSession target : targets) closeQuietly(target.connection);
            targets.clear();
        }
        publishMetric("cleanup");
    }

    private void recordTermination(boolean successful) {
        if (successful) {
            terminatedCount.incrementAndGet();
        } else {
            failedTerminationCount.incrementAndGet();
        }
        publishMetric("termination");
    }

    private void publishMetric(String event) {
        synchronized (System.out) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - metricStartNanos);
            int established = establishedCount.get();
            int terminated = terminatedCount.get();
            int active = "cleanup".equals(event) ? 0 : Math.max(0, established - terminated);
            System.out.println("DBCHAOS_SESSION_METRIC elapsed_ms=" + elapsedMs
                    + " event=" + event
                    + " established=" + established
                    + " terminated=" + terminated
                    + " active=" + active
                    + " failed_terminations=" + failedTerminationCount.get());
            System.out.flush();
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection == null) return;
        try { connection.close(); } catch (SQLException ignored) { }
    }

    private int parseIntArg(String[] args, String key, int defaultValue) {
        String value = getArg(args, key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private final class DelayedTargetTask implements Runnable {
        private final int index;
        private final long delay;
        private final CountDownLatch done;

        private DelayedTargetTask(int index, long delay, CountDownLatch done) {
            this.index = index;
            this.delay = delay;
            this.done = done;
        }

        @Override
        public void run() {
            try {
                if (delay > 0) Thread.sleep(delay);
                openTarget();
            } catch (Exception e) {
                System.err.println("目标会话 " + index + " 建立失败: " + e.getMessage());
            } finally {
                done.countDown();
            }
        }
    }

    private static final class TargetSession {
        private final long id;
        private final Connection connection;

        private TargetSession(long id, Connection connection) {
            this.id = id;
            this.connection = connection;
        }
    }

}
