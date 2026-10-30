# 空闲事务积压：三种模式与验证指南

Case：`session idle_transaction_buildup`。支持选择 `postgresql`、`opengauss`、`mysql`；MariaDB 使用 `--db mysql`。真实数据库兼容性仍需按下述步骤在你的实验环境验证，本地验证不会连接服务器。

## 1. 注入行为

每个连接设置为 **REPEATABLE READ、只读、关闭自动提交**，然后执行一次：

```sql
SELECT * FROM dbchaos_txn_demo LIMIT 1;
```

读取到一行后关闭查询对象，但保持连接和事务打开，等待期间不再发送 SQL。结束时尝试 `ROLLBACK` 并关闭连接。与普通空闲会话不同，这些会话已经访问数据并持有事务快照；例如 PostgreSQL 中预期为 `idle in transaction`。

注入器不创建、写入或删除实验表，不执行 `FOR UPDATE`，不主动获取行锁。但普通 SELECT 仍可能持有表级读锁/元数据锁，影响同表 DDL；不能称为“完全无锁”。积压可能占用连接、保留旧版本；只有同时存在更新等负载时，才可能观察到版本回收受阻，不能保证短时间实验就产生明显性能下降。

| 模式 | 场景 | 数量变化 |
| --- | --- | --- |
| `fixed` | 一批请求读表后忘记结束事务 | 并发建立，保持，再统一释放 |
| `ramp` | 应用持续遗漏事务清理，逐步积压 | 窗口内分批建立，窗口结束统一释放 |
| `random_hold` | 多个事务被应用闲置不同时间后才释放 | 并发建立，在随机时间逐个释放 |

`fixed` / `random_hold` 的 `duration` 从连接建立阶段结束后开始；建立阶段最多等待 30 秒。`random_hold` 的随机保持时间也从此时算起，因此先建立的事务实际打开时间会更长。`ramp` 从调度开始计时，在 `duration × i / count` 时刻尝试第 i+1 个事务，较晚建立的事务保持时间较短，慢连接可能错过窗口。

建立阶段等待上限不等于 JDBC 硬超时：驱动连接或网络操作不一定响应线程中断，建议在自己本地配置的 JDBC URL 中设置驱动支持的连接/网络超时。迟到的连接不会加入积压，会尝试回滚关闭。单次查询超时设置为 10 秒。

## 2. 构建及配置

Windows PowerShell 在项目根目录执行（含点号的 Maven 参数整体加引号）：

```powershell
mvn clean package "-Djar.name=DBChaos-0.0.1" "-Dmaven.compiler.release=8" -DskipTests
```

当前本地 JDK 21 下这条命令生成 Java 8 字节码。使用 JDK 8 构建时，可改用 `"-Dmaven.compiler.source=1.8" "-Dmaven.compiler.target=1.8"`，并确认编译插件兼容你的 JDK。

由你自行将 `target/DBChaos-0.0.1.jar` 放到服务器项目的 `target/` 下。服务器命令均从 `~/DBChaos` 执行：

```bash
cd ~/DBChaos
```

保留你自己服务器上的 `config/db.properties`，确认目标数据库的 `postgresql.url/user/password`、`mysql.url/user/password` 或 `opengauss.url/user/password` 指向实验数据库。不要把密码提交到项目。运行时 `config` 放在 classpath 首位，避免读取 jar 内的默认配置。

```bash
/usr/lib/jvm/java-8-openjdk-amd64/bin/java -cp "config:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session idle_transaction_buildup --help
```

目的：确认新版 jar 能在 Java 8 下加载，帮助中应包含 `-table` 和三种模式。如果需要沿用之前 PostgreSQL 的外部驱动，将 classpath 改为 `config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar`。

## 3. 准备专用实验表

只在自己的 `chaos_test` 数据库中操作。推荐由注入账号 `chaos_user` 建表，使它有 SELECT 权限。表必须至少有一行；不存在、无权限或空表都会报告建立事务失败。

### PostgreSQL

```bash
/usr/lib/postgresql/10/bin/psql -h 127.0.0.1 -p 15432 -U chaos_user -d chaos_test -W
```

交互输入数据库密码，然后执行：

```sql
CREATE TABLE dbchaos_txn_demo (id INTEGER PRIMARY KEY, note VARCHAR(100));
INSERT INTO dbchaos_txn_demo VALUES (1, 'idle transaction experiment');
SELECT * FROM dbchaos_txn_demo;
\q
```

预期建表、插入成功且查询返回一行。若同名表已存在，先确认它确实是你的实验表；不重复插入主键为 1 的行。

### MySQL / MariaDB

```bash
mysql --protocol=TCP -h 127.0.0.1 -P 3306 -u chaos_user -p chaos_test
```

交互输入密码，在客户端执行：

```sql
CREATE TABLE dbchaos_txn_demo (
  id INTEGER PRIMARY KEY,
  note VARCHAR(100)
) ENGINE=InnoDB;
INSERT INTO dbchaos_txn_demo VALUES (1, 'idle transaction experiment');
SHOW TABLE STATUS LIKE 'dbchaos_txn_demo';
SELECT * FROM dbchaos_txn_demo;
quit
```

预期 `Engine` 为 InnoDB，查询返回一行。不要使用不支持事务的 MyISAM 表；注入器不会自动检查存储引擎。

### openGauss

只有你已经建立自己的 openGauss 实验实例、数据库和账号时才执行。使用 `gsql -h <实验地址> -p <实验端口> -U <实验账号> -d chaos_test -W` 登录并交互输入密码，执行与 PostgreSQL 相同的建表、插入 SQL。不要直接照搬 PostgreSQL 的端口或 URL。

## 4. 逐个测试三个模式

先打开终端 A 执行一种模式，在终端 B 查看数据库状态；该模式结束、确认恢复后，再运行下一种。以下为 PostgreSQL 示例，切换 MySQL/MariaDB 或 openGauss 时，把 `--db postgresql` 改为 `--db mysql` 或 `--db opengauss`，并先完成对应配置及实验表准备。

固定积压：5 个事务保持约 30 秒。

```bash
/usr/lib/jvm/java-8-openjdk-amd64/bin/java -cp "config:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session idle_transaction_buildup -table dbchaos_txn_demo -mode fixed -count 5 -duration 30000
```

逐步积压：30 秒内约每 6 秒尝试增加一个事务，窗口结束统一释放。

```bash
/usr/lib/jvm/java-8-openjdk-amd64/bin/java -cp "config:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session idle_transaction_buildup -table dbchaos_txn_demo -mode ramp -count 5 -duration 30000
```

随机释放：先建立 5 个事务，在共同保持阶段的第 10 至 30 秒随机释放。

```bash
/usr/lib/jvm/java-8-openjdk-amd64/bin/java -cp "config:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session idle_transaction_buildup -table dbchaos_txn_demo -mode random_hold -count 5 -duration 30000 -min-hold 10000
```

参数限制：`count` 为 1～50，`duration` 为 1～600000 毫秒；随机模式 `min-hold` 为 1～duration，默认 duration/2（至少 1 毫秒）。`ramp` 的 duration 不能小于 count。表名支持普通标识符和 `schema.table`，不支持引号、空格或 SQL 片段。

预期成功数为 5、失败为 0。若成功数为 0，故障没有生效，检查打印的 SQLState 和错误消息，不要仅凭“启动”日志判断成功。部分成功也会继续，但实际积压规模小于目标数量。

## 5. 观察注入前、中、后

### PostgreSQL

终端 B 使用上面的 psql 登录命令。在注入前执行一次，再在注入期间每秒刷新：

```sql
SELECT pid, usename, state, xact_start, backend_xmin, query
FROM pg_stat_activity
WHERE datname = current_database()
  AND usename = 'chaos_user'
  AND pid <> pg_backend_pid()
  AND state = 'idle in transaction'
  AND query LIKE 'SELECT * FROM dbchaos_txn_demo LIMIT 1%';
\watch 1
```

预期注入前无本次实验的记录；期间出现 `idle in transaction`，有事务开始时间，通常有 `backend_xmin`；结束后本次记录消失。`fixed` 数量近似 0→5→0，`ramp` 逐步上升后归零，`random_hold` 上升至 5 后逐步减少。按 Ctrl+C 停止刷新，不会终止终端 A 的注入。

如果设置了 `idle_in_transaction_session_timeout`，服务器可能提前断开事务。注入器不会发送心跳，也不会持续检测连接存活，因此最终应以数据库视图为准。

### MySQL / MariaDB

在另一个数据库客户端查询（查看全部 InnoDB 事务通常需要管理员账号或 PROCESS 权限；注入账号本身不必获得该权限）：

```sql
SHOW FULL PROCESSLIST;
SELECT t.trx_mysql_thread_id, t.trx_started, t.trx_state,
       p.USER, p.DB, p.COMMAND, p.TIME
FROM information_schema.INNODB_TRX t
JOIN information_schema.PROCESSLIST p ON p.ID = t.trx_mysql_thread_id
WHERE p.USER = 'chaos_user' AND p.DB = 'chaos_test';
```

每隔几秒重查，预期出现 `Sleep` 连接以及对应仍打开的 InnoDB 事务，结束后消失。只读事务的事务 ID 或查询文本可能为空/为 0，具体展示取决于数据库版本；**单凭 Sleep 不能证明存在空闲事务**。建议验证期间不要让同账号同时运行其他程序，以免混淆统计。

### openGauss

先在 gsql 执行 `\d pg_stat_activity` 查看当前版本字段，再查询该视图中实验账号、实验数据库的 `state`、`xact_start`、`query`。如字段兼容可使用上述 PostgreSQL 查询；不应假定所有版本都有 `backend_xmin`。关注 `idle in transaction` 的出现及结束后的消失。

### 数据表表现与恢复

注入前、期间和结束后分别查询：

```sql
SELECT * FROM dbchaos_txn_demo;
```

预期始终是原来的那一行，注入不修改数据。普通查询通常可继续执行；不要通过修改业务表验证影响。结束日志应提示尝试回滚释放。强制杀进程、网络故障等情况下，数据库实际清理时间取决于其断连检测；日志中的成功建立数是累计数，不是实时存活数。

若连接未消失，先用数据库视图核对账号、数据库、SQL 和 PID，确认只处理本次实验连接，再由管理员终止对应连接。清理后如不再需要实验表，可自行执行 `DROP TABLE dbchaos_txn_demo;`；注入期间不要删表，否则可能等待表锁。

## 6. 项目内的本地验证

不连接任何数据库的 JDBC 模拟验证（先执行上面的构建命令）：

```powershell
javac --release 8 -encoding UTF-8 -cp "target/DBChaos-0.0.1.jar" -d target/test-classes tests/IdleTransactionBuildupTest.java
java -cp "target/test-classes;target/DBChaos-0.0.1.jar" IdleTransactionBuildupTest
```

覆盖三个模式、只读/隔离级别设置、回滚关闭、空表、连接失败、非法表名及线程中断后的清理。此验证不能替代真实数据库上的状态观测。

配置生成器也已注册 224（fixed）、225（ramp）、226（random_hold）。生成的配置使用 `dbchaos_txn_demo`，运行联动实验前仍需先在目标数据库准备该表。
