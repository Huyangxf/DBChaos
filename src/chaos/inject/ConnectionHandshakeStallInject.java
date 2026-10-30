package chaos.inject;

import chaos.core.BaseFaultInject;
import chaos.protocol.HandshakePackets;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Holds pre-authentication TCP connections; never authenticates or executes SQL. */
public class ConnectionHandshakeStallInject extends BaseFaultInject {
    private final Object lifecycle = new Object();
    private final Set<Socket> sockets = new HashSet<Socket>();
    private boolean stopping;

    public ConnectionHandshakeStallInject(String dbType) {
        super(dbType, "CONNECTION_HANDSHAKE_STALL");
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }
        String mode = getArg(args, "-mode");
        mode = mode == null ? "silent" : mode.toLowerCase(Locale.ROOT);
        if (!"silent".equals(mode) && !"partial".equals(mode)) {
            throw new IllegalArgumentException("-mode 仅支持 silent、partial");
        }
        int count = number(args, "-count", 10, 1, 200);
        int duration = number(args, "-duration", 60000, 1, 3600000);
        int connectTimeout = number(args, "-connect-timeout", 10000, 1, 60000);
        int greetingTimeout = number(args, "-greeting-timeout", 10000, 1, 60000);
        Endpoint endpoint = Endpoint.parse(url, dbType);
        final byte[] startup = "partial".equals(mode) && !endpoint.mysql
                ? HandshakePackets.startupPrefix(endpoint.openGauss, user, endpoint.database) : null;
        final boolean partial = "partial".equals(mode);
        final Stats stats = new Stats();
        synchronized (lifecycle) {
            if (!sockets.isEmpty()) throw new IllegalStateException("上次注入尚未结束");
            stopping = false;
        }
        System.out.println(">>> 启动连接握手停滞 | 数据库: " + dbType + " | 模式: " + mode
                + " | 连接数: " + count + " | 单连接观察窗口: " + duration + " ms");
        System.out.println("使用非 TLS TCP Socket；不会完成认证，不发送密码或执行 SQL。");
        System.out.println("连接进入停滞后的 EOF、读错误和到期关闭分别统计；不自动判定数据库认证超时。");

        ExecutorService pool = Executors.newFixedThreadPool(count, new ThreadFactory() {
            private final AtomicInteger ids = new AtomicInteger();
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "handshake-stall-" + ids.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
        Thread cleanupHook = new Thread(new Runnable() {
            @Override public void run() { stopSockets(); }
        }, "handshake-stall-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanupHook);
        try {
            for (int i = 1; i <= count; i++) {
                final int id = i;
                pool.execute(new Runnable() {
                    @Override public void run() {
                        runConnection(id, endpoint, partial, startup, duration, connectTimeout, greetingTimeout, stats);
                    }
                });
            }
            pool.shutdown();
            if (!pool.awaitTermination((long) duration + connectTimeout + greetingTimeout + 5000L,
                    TimeUnit.MILLISECONDS)) {
                throw new IOException("注入任务未按时结束，已请求关闭所有 Socket；请检查 DNS 或网络状态");
            }
        } finally {
            stopSockets();
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(cleanupHook); }
                catch (IllegalStateException ignored) { /* JVM is already shutting down. */ }
                System.out.println("汇总 | TCP 建立: " + stats.connected.get()
                        + " | 进入停滞: " + stats.prepared.get()
                        + " | 停滞后对端 EOF: " + stats.eof.get()
                        + " | 停滞后传输错误: " + stats.transportErrors.get()
                        + " | 实验到期主动关闭: " + stats.expired.get()
                        + " | 建连/准备失败: " + stats.failures.get()
                        + " | 取消: " + stats.cancelled.get());
            }
        }
        if (stats.prepared.get() == 0) throw new IOException("没有连接进入停滞阶段，请检查地址、端口和准备阶段错误");
        if (stats.failures.get() > 0 || stats.transportErrors.get() > 0) {
            System.out.println("部分连接出现错误，请结合逐连接输出和数据库日志分析；不能计为已确认的超时回收。");
        }
    }

    private void runConnection(int id, Endpoint endpoint, boolean partial, byte[] startup,
                               int duration, int connectTimeout, int greetingTimeout, Stats stats) {
        Socket socket = new Socket();
        boolean prepared = false;
        String phase = "TCP 建连";
        long started = 0L;
        long received = 0L;
        try {
            synchronized (lifecycle) {
                if (stopping) { stats.cancelled.incrementAndGet(); return; }
                sockets.add(socket);
            }
            socket.connect(new InetSocketAddress(endpoint.host, endpoint.port), connectTimeout);
            socket.setTcpNoDelay(true);
            stats.connected.incrementAndGet();
            phase = "协议准备";
            if (partial) {
                byte[] prefix = startup;
                if (endpoint.mysql) {
                    prefix = HandshakePackets.mysqlResponsePrefix(readMysqlGreeting(socket, greetingTimeout));
                }
                socket.getOutputStream().write(prefix);
                socket.getOutputStream().flush();
            }
            started = System.nanoTime();
            prepared = true;
            stats.prepared.incrementAndGet();
            System.out.println("连接 " + id + " | 本地端口 " + socket.getLocalPort() + " | 已进入停滞");
            long deadline = started + TimeUnit.MILLISECONDS.toNanos(duration);
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[1024];
            while (!Thread.currentThread().isInterrupted()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    stats.expired.incrementAndGet();
                    report(id, "实验到期主动关闭", started, received);
                    return;
                }
                socket.setSoTimeout((int) Math.max(1L, Math.min(250L,
                        TimeUnit.NANOSECONDS.toMillis(remaining))));
                try {
                    int read = input.read(buffer);
                    if (read == -1) {
                        stats.eof.incrementAndGet();
                        report(id, "对端 EOF（关闭原因需查服务端日志）", started, received);
                        return;
                    }
                    received += read; // Reading a server greeting/error never advances client handshake.
                } catch (SocketTimeoutException expected) {
                    // This short read timeout only polls the experiment deadline.
                }
            }
            stats.cancelled.incrementAndGet();
        } catch (IOException e) {
            synchronized (lifecycle) {
                if (stopping || Thread.currentThread().isInterrupted()) {
                    stats.cancelled.incrementAndGet();
                    return;
                }
            }
            if (prepared) {
                stats.transportErrors.incrementAndGet();
                report(id, "传输错误: " + e.getClass().getSimpleName() + ": " + e.getMessage(), started, received);
            } else {
                stats.failures.incrementAndGet();
                System.err.println("连接 " + id + " | " + phase + "失败: " + e.getMessage());
            }
        } finally {
            close(socket);
            synchronized (lifecycle) { sockets.remove(socket); }
        }
    }

    private static byte[] readMysqlGreeting(Socket socket, int timeout) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        byte[] header = readExactly(socket, 4, deadline);
        int length = (header[0] & 255) | ((header[1] & 255) << 8) | ((header[2] & 255) << 16);
        if (header[3] != 0 || length < 1 || length > 65536) {
            throw new IOException("MySQL 问候长度或序号不符合第一版支持范围");
        }
        return readExactly(socket, length, deadline);
    }

    private static byte[] readExactly(Socket socket, int size, long deadline) throws IOException {
        byte[] bytes = new byte[size];
        int offset = 0;
        while (offset < size) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("读取完整 MySQL 问候超时");
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int read = socket.getInputStream().read(bytes, offset, size - offset);
            if (read == -1) throw new EOFException("MySQL 问候未接收完整，对端已关闭连接");
            offset += read;
        }
        return bytes;
    }

    private void stopSockets() {
        List<Socket> snapshot;
        synchronized (lifecycle) {
            stopping = true;
            snapshot = new ArrayList<Socket>(sockets);
        }
        for (Socket socket : snapshot) close(socket);
    }

    private static void close(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static void report(int id, String result, long started, long received) {
        System.out.println("连接 " + id + " | " + result + " | 停滞观察 "
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms | 收到 " + received + " 字节");
    }

    private int number(String[] args, String key, int fallback, int min, int max) {
        String value = getArg(args, key);
        int number = value == null ? fallback : Integer.parseInt(value);
        if (number < min || number > max) throw new IllegalArgumentException(key + " 必须在 " + min + " 到 " + max + " 之间");
        return number;
    }

    @Override public void printHelp() {
        System.out.println("\n不利 Case 用法: session connection_handshake_stall");
        System.out.println("silent：TCP 连接后不发送数据库报文；partial：在固定位置停止发送初始化报文。");
        System.out.println("PostgreSQL 3.0 / openGauss 3.51：只发送启动包前 12 字节；MySQL：读问候后发送响应前 8 字节。");
        System.out.println("  -mode              silent | partial，默认 silent");
        System.out.println("  -count             连接数，1-200，默认 10");
        System.out.println("  -duration          每个连接进入停滞后的观察时长，1-3600000 ms，默认 60000");
        System.out.println("  -connect-timeout   TCP 建连超时，1-60000 ms，默认 10000（不含系统 DNS 解析）");
        System.out.println("  -greeting-timeout  MySQL partial 完整问候读取超时，1-60000 ms，默认 10000");
        System.out.println("第一版只支持单地址、无查询参数的非 TLS JDBC URL，可用 -url 覆盖。");
        System.out.println("只打开原始 Socket，不完成认证、不发密码、不执行 SQL；EOF 不等同于认证超时。");
    }

    private static final class Stats {
        final AtomicInteger connected = new AtomicInteger();
        final AtomicInteger prepared = new AtomicInteger();
        final AtomicInteger eof = new AtomicInteger();
        final AtomicInteger transportErrors = new AtomicInteger();
        final AtomicInteger expired = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger cancelled = new AtomicInteger();
    }

    private static final class Endpoint {
        String host;
        int port;
        String database;
        boolean mysql;
        boolean openGauss;

        static Endpoint parse(String url, String dbType) {
            String type = dbType.toLowerCase(Locale.ROOT);
            if (!"mysql".equals(type) && !"postgresql".equals(type) && !"opengauss".equals(type)) {
                throw new IllegalArgumentException("本 Case 仅支持 mysql、postgresql、opengauss");
            }
            String prefix = "jdbc:" + type + "://";
            if (url == null || !url.startsWith(prefix)) {
                throw new IllegalArgumentException("URL 协议必须匹配 --db：" + prefix + "<host>:<port>/<database>");
            }
            URI uri;
            try { uri = URI.create(url.substring(5)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("JDBC URL 格式无效"); }
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPath() == null || uri.getPath().length() <= 1
                    || uri.getPath().substring(1).contains("/") || uri.getPath().indexOf('\0') >= 0) {
                throw new IllegalArgumentException("第一版需要单地址、无查询参数的 URL，例如 jdbc:" + type
                        + "://127.0.0.1:<port>/chaos_test；可用 -url 覆盖，TLS/代理/多主机不在支持范围");
            }
            Endpoint result = new Endpoint();
            result.host = uri.getHost();
            result.mysql = "mysql".equals(type);
            result.openGauss = "opengauss".equals(type);
            result.port = uri.getPort() == -1 ? (result.mysql ? 3306 : 5432) : uri.getPort();
            if (result.port < 1 || result.port > 65535) throw new IllegalArgumentException("URL 端口超出有效范围");
            result.database = uri.getPath().substring(1);
            return result;
        }
    }
}
