# 会话被强制终止

Case：`session session_forced_termination`。

该 Case 只测试数据库服务端行为：DBChaos 建立目标会话，记录每个会话 ID，再通过独立控制连接终止这些目标会话，最后检查目标连接是否失效并清理。它不包含业务应用、连接池或自动重连逻辑。

支持模式：

- `serial`：逐个终止目标会话，可用 `-interval` 控制终止间隔。
- `burst`：使用有限并发批量终止目标会话，可用 `-concurrency` 控制并发度。

PostgreSQL/openGauss 使用 `pg_terminate_backend`；MySQL/MariaDB 使用 `KILL CONNECTION`。控制账号必须具有终止其他会话的权限。默认控制账号复用 `db.properties` 中的账号，也可以使用 `-control-user` 和 `-control-password` 覆盖；密码直接写在命令行可能进入 shell 历史，优先使用本地受保护配置或交互式环境管理。

## 参数

```text
-mode             serial | burst，默认 serial
-count            目标会话数，1-100，默认 10
-interval         serial 模式终止间隔，0-60000 ms，默认 500
-concurrency      burst 模式并发终止数，1-32，默认 min(count, 10)
-control-user     控制连接用户名，默认使用 db.properties 用户
-control-password 控制连接密码，默认使用 db.properties 密码
```

## 示例

```bash
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" \
  chaos.Main --db postgresql session session_forced_termination \
  -mode serial -count 10 -interval 1000
```

```bash
java -cp "config:lib/opengauss-jdbc-7.0.0-RC2.jar:target/DBChaos-0.0.1.jar" \
  chaos.Main --db opengauss session session_forced_termination \
  -mode burst -count 20 -concurrency 10
```

```bash
java -cp "config:lib/mysql-connector-j-8.0.33.jar:target/DBChaos-0.0.1.jar" \
  chaos.Main --db mysql session session_forced_termination \
  -mode burst -count 20 -concurrency 10
```

## 结果解释

成功时应看到目标会话建立成功，并且终止成功数大于零；终止后目标会话应不可用。若控制账号权限不足，程序会报告终止失败，不应把“建立了目标连接”误认为故障注入成功。

该 Case 不会让数据库自动恢复原会话，也不实现应用重连。数据库负责终止会话、回滚未提交事务并释放资源；应用或连接池的重连属于后续独立测试。
