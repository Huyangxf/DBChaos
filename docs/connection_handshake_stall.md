# 连接握手停滞

Case：`session connection_handshake_stall`。

该 Case 只打开原始 TCP Socket，不使用 JDBC 完成连接，不发送密码，也不执行 SQL。它模拟连接已经到达数据库端口，但数据库协议初始化没有完成的情况。

第一版固定两种停滞位置：

- `silent`：TCP 建立后不发送数据库协议报文。
- `partial`：发送固定前缀后停止。PostgreSQL 使用协议 3.0 启动包前 12 字节；openGauss 7.0.0-RC2 使用协议 3.51 启动包前 12 字节；MySQL/MariaDB 先完整读取服务端问候，再只发送客户端响应前 8 字节。

三种数据库的握手方向不同，因此 `partial` 不共用同一份报文：PostgreSQL/openGauss 是客户端先发启动包，MySQL/MariaDB 是服务端先发问候包。第一版只支持单地址、无查询参数的非 TLS JDBC URL；TLS、代理、多主机 URL 和任意截断位置暂不支持。

## 参数

```text
-mode              silent | partial，默认 silent
-count             连接数，1-200，默认 10
-duration          每个连接进入停滞后的观察时长，1-3600000 ms，默认 60000
-connect-timeout   TCP 建连超时，1-60000 ms，默认 10000
-greeting-timeout  MySQL partial 读取问候超时，1-60000 ms，默认 10000
-url               可覆盖 config/db.properties 中的地址
```

## 示例

```bash
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" \
  chaos.Main --db postgresql session connection_handshake_stall \
  -url jdbc:postgresql://127.0.0.1:15432/chaos_test \
  -mode silent -count 10 -duration 60000
```

```bash
java -cp "config:lib/opengauss-jdbc-7.0.0-RC2.jar:target/DBChaos-0.0.1.jar" \
  chaos.Main --db opengauss session connection_handshake_stall \
  -url jdbc:opengauss://127.0.0.1:15433/chaos_test \
  -mode partial -count 10 -duration 60000
```

```bash
java -cp "config:lib/mysql-connector-j-8.0.33.jar:target/DBChaos-0.0.1.jar" \
  chaos.Main --db mysql session connection_handshake_stall \
  -url jdbc:mysql://127.0.0.1:3306/chaos_test \
  -mode partial -count 10 -duration 60000
```

## 结果解释

程序会分别统计 TCP 建立、进入停滞、对端 EOF、停滞后的传输错误、实验到期主动关闭、建连/准备失败和取消数量。`实验到期主动关闭` 表示 DBChaos 自己清理连接，不能证明数据库执行了超时回收；`对端 EOF` 也必须结合数据库日志判断关闭原因。

未完成认证的连接可能不会出现在 `pg_stat_activity` 或 MySQL `PROCESSLIST` 中。观察时应结合服务器端 TCP 连接、数据库日志和窗口 A 的统计。业务表不应发生变化。
