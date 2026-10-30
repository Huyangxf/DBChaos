package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Generates bounded authentication failures using a run-specific nonexistent username. */
public class ConnectionAuthFailureInject extends BaseFaultInject {
    private static final int DEFAULT_COUNT = 20;
    private static final int MAX_ATTEMPTS = 200;
    private static final int MAX_CONCURRENCY = 32;
    private static final int DEFAULT_LOGIN_TIMEOUT_SEC = 10;
    private volatile long metricStartNanos;

    public ConnectionAuthFailureInject(String dbType) {
        super(dbType, "CONNECTION_AUTH_FAILURE");
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

        int count = parseIntArg(args, "-count", DEFAULT_COUNT);
        int intervalMs = parseIntArg(args, "-interval", 500);
        int concurrency = parseIntArg(args, "-concurrency", Math.min(count, 10));
        int loginTimeoutSec = parseIntArg(args, "-login-timeout", DEFAULT_LOGIN_TIMEOUT_SEC);
        if (count < 1 || count > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("-count 必须在 1 到 " + MAX_ATTEMPTS + " 之间");
        }
        if (intervalMs < 0 || intervalMs > 60000) {
            throw new IllegalArgumentException("-interval 必须在 0 到 60000 毫秒之间");
        }
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalArgumentException("-concurrency 必须在 1 到 " + MAX_CONCURRENCY + " 之间");
        }
        if (loginTimeoutSec < 1 || loginTimeoutSec > 60) {
            throw new IllegalArgumentException("-login-timeout 必须在 1 到 60 秒之间");
        }

        String rejectedUser = createSyntheticUsername();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger authRejections = new AtomicInteger();
        AtomicInteger otherFailures = new AtomicInteger();
        AtomicInteger unexpectedSuccesses = new AtomicInteger();
        AtomicReference<String> firstAuthFailure = new AtomicReference<String>();
        AtomicReference<String> firstOtherFailure = new AtomicReference<String>();
        int oldLoginTimeout = DriverManager.getLoginTimeout();
        metricStartNanos = System.nanoTime();

        System.out.println(">>> 启动连接认证失败 | 模式: " + mode + " | 目标数据库: " + dbType
                + " | 尝试次数: " + count + " | 测试用户名: " + rejectedUser);
        System.out.println("使用本次运行生成的虚构用户名；不会修改或锁定配置中的真实账号。");
        try {
            DriverManager.setLoginTimeout(loginTimeoutSec);
            if ("serial".equals(mode)) {
                runSerial(rejectedUser, count, intervalMs, attempts, authRejections,
                        otherFailures, unexpectedSuccesses, firstAuthFailure, firstOtherFailure);
            } else {
                runBurst(rejectedUser, count, concurrency, loginTimeoutSec, attempts,
                        authRejections, otherFailures, unexpectedSuccesses,
                        firstAuthFailure, firstOtherFailure);
            }
        } finally {
            DriverManager.setLoginTimeout(oldLoginTimeout);
        }

        System.out.println("认证拒绝: " + authRejections.get() + "，其他连接错误: " + otherFailures.get()
                + "，意外连接成功: " + unexpectedSuccesses.get() + "，完成尝试: " + attempts.get());
        if (firstAuthFailure.get() != null) {
            System.out.println("认证拒绝示例: " + firstAuthFailure.get());
        }
        if (firstOtherFailure.get() != null) {
            System.err.println("其他连接错误示例: " + firstOtherFailure.get());
        }
        if (unexpectedSuccesses.get() > 0) {
            System.err.println("警告：虚构用户名仍成功建立了连接，请检查数据库是否存在匿名用户或特殊认证映射。");
        }
        if (authRejections.get() == 0) {
            throw new SQLException("没有观察到 SQLState 28 类认证拒绝；请检查连接配置、驱动和数据库错误信息");
        }
    }

    @Override
    public void printHelp() {
        System.out.println("\n不利 Case 用法: session connection_auth_failure");
        System.out.println("使用每次运行生成的虚构用户名发起连接，模拟数据库认证拒绝；不会改动真实账号。");
        System.out.println("参数:");
        System.out.println("  -mode           选填。serial | burst (默认 serial)");
        System.out.println("  -count          选填。登录尝试次数 (1-" + MAX_ATTEMPTS + ", 默认 " + DEFAULT_COUNT + ")");
        System.out.println("  -interval       serial 模式选填。尝试间隔 (0-60000 ms, 默认 500)");
        System.out.println("  -concurrency    burst 模式选填。并发数 (1-" + MAX_CONCURRENCY + ", 默认 min(count, 10))");
        System.out.println("  -login-timeout  选填。单次连接登录超时 (1-60 秒, 默认 " + DEFAULT_LOGIN_TIMEOUT_SEC + ")");
        System.out.println("模式:");
        System.out.println("  serial  按间隔逐个尝试登录，模拟持续出现的认证失败");
        System.out.println("  burst   以有限并发集中尝试登录，模拟短时认证失败突增");
        System.out.println("示例:");
        System.out.println("  java -jar DBChaos-0.0.1.jar --db mysql session connection_auth_failure -mode serial -count 20 -interval 500");
        System.out.println("  java -jar DBChaos-0.0.1.jar --db postgresql session connection_auth_failure -mode burst -count 20 -concurrency 10");
    }

    protected Connection attemptConnection(String attemptedUser) throws SQLException {
        return DriverManager.getConnection(url, attemptedUser, password);
    }

    protected String createSyntheticUsername() {
        int suffix = ThreadLocalRandom.current().nextInt(36 * 36 * 36 * 36);
        return "dbchaos_x_" + Long.toString(System.currentTimeMillis(), 36) + "_"
                + Integer.toString(suffix, 36);
    }

    private void runSerial(String rejectedUser, int count, int intervalMs,
                           AtomicInteger attempts, AtomicInteger authRejections,
                           AtomicInteger otherFailures, AtomicInteger unexpectedSuccesses,
                           AtomicReference<String> firstAuthFailure,
                           AtomicReference<String> firstOtherFailure) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            runAttempt(rejectedUser, attempts, authRejections, otherFailures,
                    unexpectedSuccesses, firstAuthFailure, firstOtherFailure);
            if (intervalMs > 0 && i + 1 < count) Thread.sleep(intervalMs);
        }
    }

    private void runBurst(String rejectedUser, int count, int concurrency, int loginTimeoutSec,
                         AtomicInteger attempts, AtomicInteger authRejections,
                         AtomicInteger otherFailures, AtomicInteger unexpectedSuccesses,
                         AtomicReference<String> firstAuthFailure,
                         AtomicReference<String> firstOtherFailure) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, concurrency));
        CountDownLatch done = new CountDownLatch(count);
        try {
            for (int i = 0; i < count; i++) {
                pool.execute(() -> {
                    try {
                        runAttempt(rejectedUser, attempts, authRejections, otherFailures,
                                unexpectedSuccesses, firstAuthFailure, firstOtherFailure);
                    } finally {
                        done.countDown();
                    }
                });
            }
            pool.shutdown();
            long batches = (count + Math.min(count, concurrency) - 1) / Math.min(count, concurrency);
            if (!done.await((long) loginTimeoutSec * batches + 5L, TimeUnit.SECONDS)) {
                throw new SQLException("并发登录尝试超时；已请求停止剩余任务");
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void runAttempt(String attemptedUser, AtomicInteger attempts,
                            AtomicInteger authRejections, AtomicInteger otherFailures,
                            AtomicInteger unexpectedSuccesses,
                            AtomicReference<String> firstAuthFailure,
                            AtomicReference<String> firstOtherFailure) {
        int outcome = 0;
        String summary = null;
        try (Connection conn = attemptConnection(attemptedUser)) {
            outcome = 3;
        } catch (SQLException e) {
            summary = summarize(e);
            outcome = isAuthenticationRejection(e) ? 1 : 2;
        } finally {
            synchronized (System.out) {
                if (outcome == 1) {
                    authRejections.incrementAndGet();
                    firstAuthFailure.compareAndSet(null, summary);
                } else if (outcome == 2) {
                    otherFailures.incrementAndGet();
                    firstOtherFailure.compareAndSet(null, summary);
                } else if (outcome == 3) {
                    unexpectedSuccesses.incrementAndGet();
                }
                attempts.incrementAndGet();
                publishMetricLocked(attempts, authRejections, otherFailures, unexpectedSuccesses);
            }
        }
    }

    private void publishMetricLocked(AtomicInteger attempts, AtomicInteger authRejections,
                                     AtomicInteger otherFailures, AtomicInteger unexpectedSuccesses) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - metricStartNanos);
        System.out.println("DBCHAOS_AUTH_METRIC elapsed_ms=" + elapsedMs
                + " attempts=" + attempts.get()
                + " auth_rejections=" + authRejections.get()
                + " other_failures=" + otherFailures.get()
                + " unexpected_successes=" + unexpectedSuccesses.get());
        System.out.flush();
    }

    private boolean isAuthenticationRejection(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("28")) return true;
        }
        return false;
    }

    private String summarize(SQLException error) {
        SQLException detail = error;
        for (SQLException current = error; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("28")) {
                detail = current;
                break;
            }
        }
        String state = detail.getSQLState();
        return "SQLState=" + (state == null ? "unknown" : state)
                + ", errorCode=" + detail.getErrorCode() + ": " + detail.getMessage();
    }

    private int parseIntArg(String[] args, String key, int defaultValue) {
        String value = getArg(args, key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }
}
