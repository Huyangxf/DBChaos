# 杨小飞新增故障情况

本文总结“连接与会话管理”下新增的五个故障 Case、模式、注入方式和实验结果判定方法。

## 故障 Case 总览

| 故障 Case | 模拟场景 | DBChaos 注入方式 |
| --- | --- | --- |
| 空闲会话积压 | 连接已建立但长期不执行 SQL，例如连接池泄漏、连接未归还或清理滞后。 | 使用配置账号建立 JDBC 连接，认证成功后不执行 SQL，保持连接空闲，窗口结束后关闭。 |
| 空闲事务积压 | 事务访问过数据但迟迟没有提交或回滚，例如请求结束后遗漏事务清理。 | 建立连接，开启只读事务并读取实验表一行，保持事务和连接打开，结束时回滚并关闭。 |
| 会话强制终止 | 管理员误杀会话、节点切换或服务端清理造成已建立会话突然断开。 | 建立目标会话并取得会话 ID，通过独立控制连接终止目标会话；不模拟应用重连。 |
| 连接认证失败 | 错误账号、错误凭据或失效配置导致认证阶段被数据库拒绝。 | 每次运行生成不存在的用户名，发起 JDBC 登录；不创建用户、不修改真实账号、不执行 SQL。 |
| 连接握手停滞 | TCP 已建立，但数据库协议初始化没有完成，例如客户端卡死或只发送部分握手数据。 | 使用原始 TCP Socket；按模式不发送协议报文，或只发送固定协议前缀；不认证、不发密码、不执行 SQL。 |

## 各 Case 的模式

每个 Case 只列出一次，模式放在该 Case 下面的表格中。使用标准 Markdown 表格，避免不同阅读器对 HTML `rowspan` 的兼容差异。

#### 空闲会话积压

| 模式 | 模拟场景 | 行为变化 |
| --- | --- | --- |
| `fixed` | 一次性出现固定规模的连接泄漏。 | 固定数量连接保持空闲至窗口结束。 |
| `ramp` | 应用持续建连，释放速度跟不上。 | 在窗口内逐步建立连接。 |
| `random_hold` | 不同请求的连接占用时长不同。 | 连接随机保持后提前释放。 |

#### 空闲事务积压

| 模式 | 模拟场景 | 行为变化 |
| --- | --- | --- |
| `fixed` | 一批请求同时忘记结束事务。 | 固定数量只读事务保持打开。 |
| `ramp` | 开启事务速度高于结束速度。 | 事务数量在窗口内逐步增加。 |
| `random_hold` | 不同事务清理延迟不同。 | 事务随机保持后回滚释放。 |

#### 会话强制终止

| 模式 | 模拟场景 | 行为变化 |
| --- | --- | --- |
| `serial` | 会话陆续被清理。 | 逐个终止，按 `-interval` 间隔执行。 |
| `burst` | 故障切换或重启造成集中断开。 | 按有限并发批量终止。 |

#### 连接认证失败

| 模式 | 模拟场景 | 行为变化 |
| --- | --- | --- |
| `serial` | 低频、持续的错误登录。 | 按间隔逐个尝试认证。 |
| `burst` | 配置错误发布或异常登录峰值。 | 按有限并发集中尝试认证。 |

#### 连接握手停滞

| 模式 | 模拟场景 | 行为变化 |
| --- | --- | --- |
| `silent` | 客户端建连后没有发送启动报文。 | TCP 建立后保持静默。 |
| `partial` | 客户端只发送部分初始化报文后停住。 | PostgreSQL 3.0/openGauss 3.51 发送前 12 字节；MySQL 读取问候后发送响应前 8 字节。 |

## 概念区分

- **空闲会话积压：**连接已认证，没有未结束事务。
- **空闲事务积压：**连接已认证，事务已读取实验表但没有结束。
- **会话强制终止：**测试数据库终止已有会话的服务端行为，不包含应用或连接池重连。
- **连接认证失败：**失败发生在认证阶段，不等于连接数耗尽或连接建立后断开。
- **连接握手停滞：**停留在认证之前的协议初始化阶段；对端 EOF 不能单独证明数据库执行了超时回收。

## 实验注入说明

### 1. 统一运行准备

#### 1.1 数据库配置

1. 在服务器项目目录的 `config/db.properties` 中配置目标数据库连接（db.properties文件中是我在服务器上三个数据库的配置）。
2. 默认实验账号是配置文件中的正常账号，例如使用 `chaos_user` 登录 `chaos_test`。
3. 运行命令中的 `--db`、URL、账号、密码和 JDBC 驱动必须属于同一个数据库。
4. 三种数据库的驱动分别为：
   - PostgreSQL：`lib/postgresql-42.7.2.jar`
   - openGauss：`lib/opengauss-jdbc-7.0.0-RC2.jar`
   - MySQL/MariaDB：`lib/mysql-connector-j-8.0.33.jar`

#### 1.2 实验表

只有空闲事务积压依赖实验表。建议在 `chaos_test` 中准备专用表 `dbchaos_txn_demo`：

1. 表必须已经存在。
2. 表至少有一行可读数据。
3. `chaos_user` 必须具有 SELECT 权限。
4. MySQL/MariaDB 使用 InnoDB。
5. 注入器只读取一行，不写入或删除数据。
6. 注入结束时回滚事务，但不会删除实验表。

其他四个新增 Case 不需要预先创建业务表。

#### 1.3 通用命令结构

Linux 示例：

```bash
java -cp "config:<数据库驱动>:target/DBChaos-0.0.1.jar"   chaos.Main --db <数据库类型> session <故障Case> <参数>
```

Windows 将 classpath 分隔符 `:` 改为 `;`。示例中的 `<数据库驱动>`、`<数据库类型>`、Case 和参数需要替换。命令会对真实数据库建立连接或发送请求，请先确认目标实例、权限和测试窗口。

#### 1.4 切换 PostgreSQL、openGauss 和 MySQL

下面三条是同一个 Case 的切换模板。以 `idle_session_buildup` 为例，`session`、Case 名和 Case 参数保持不变，只替换数据库类型和驱动 jar：

```bash
# PostgreSQL
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" \
  chaos.Main --db postgresql session idle_session_buildup \
  -mode fixed -count 20 -duration 60000

# openGauss
java -cp "config:lib/opengauss-jdbc-7.0.0-RC2.jar:target/DBChaos-0.0.1.jar" \
  chaos.Main --db opengauss session idle_session_buildup \
  -mode fixed -count 20 -duration 60000

# MySQL 或 MariaDB
java -cp "config:lib/mysql-connector-j-8.0.33.jar:target/DBChaos-0.0.1.jar" \
  chaos.Main --db mysql session idle_session_buildup \
  -mode fixed -count 20 -duration 60000
```

切换时按以下规则检查：

1. `--db postgresql`、`--db opengauss`、`--db mysql` 必须与目标数据库一致。
2. classpath 中只应优先放与目标数据库匹配的驱动 jar；不要只替换 `--db` 而继续使用另一个数据库的驱动。
3. `config/db.properties` 中应配置对应前缀的连接字段：

   | 目标数据库 | 建议配置前缀 | 示例 URL 形式 |
   | --- | --- | --- |
   | PostgreSQL | `postgresql.url`、`postgresql.user`、`postgresql.password` | `jdbc:postgresql://<host>:<port>/<database>` |
   | openGauss | `opengauss.url`、`opengauss.user`、`opengauss.password` | `jdbc:opengauss://<host>:<port>/<database>` |
   | MySQL/MariaDB | `mysql.url`、`mysql.user`、`mysql.password` | `jdbc:mysql://<host>:<port>/<database>` |

4. `<host>`、`<port>` 和 `<database>` 必须替换为对应数据库实例的实际值；三种数据库不一定使用相同端口。
5. 如果没有对应前缀，程序会按自身的回退规则读取配置；为了避免误连其他数据库，建议为每种实际使用的数据库都显式填写专属前缀。
6. 也可以只对本次运行覆盖连接信息，不修改配置文件：

   ```bash
   java -cp "config:lib/mysql-connector-j-8.0.33.jar:target/DBChaos-0.0.1.jar" \
     chaos.Main --db mysql session idle_session_buildup \
     -url jdbc:mysql://<host>:<port>/<database> \
     -user <mysql_user> -password '<mysql_password>' \
     -mode fixed -count 20 -duration 60000
   ```

   `-url`、`-user` 和 `-password` 是本次运行覆盖值；密码直接出现在命令行时可能进入 shell 历史，服务器实验中应优先使用受保护的本地配置文件。文档和仓库中不要填写真实密码。

7. 各 Case 的参数规则通常不因数据库切换而改变，但数据库权限和实现结果可能不同：会话强制终止需要目标数据库对应的终止权限；空闲事务需要目标数据库中已有同名实验表；连接握手停滞还要求 URL 是单地址、无查询参数的非 TLS 直连地址。

---

### 2. 空闲会话积压

#### 2.1 前置条件

1. 配置账号可以建立 JDBC 连接。
2. 数据库连接上限允许建立 `-count` 个连接。
3. 本 Case 不访问业务表，也不执行 SQL。
4. 注入结束后 DBChaos 会关闭自己建立的连接。

#### 2.2 参数

| 参数 | 含义、单位和范围 |
| --- | --- |
| `-mode` | `fixed`、`ramp` 或 `random_hold`；默认 `fixed`。 |
| `-count` | 目标连接数；整数，1–200。 |
| `-duration` | 注入窗口；整数，单位毫秒，1–86,400,000（最多 24 小时）。`ramp` 下不得小于 `count`。 |
| `-min-hold` | 仅用于 `random_hold`；最短保持时间，单位毫秒，1–`duration`；默认 `duration/2`。 |

#### 2.3 命令示例

```bash
# fixed：固定数量
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session idle_session_buildup -mode fixed -count 20 -duration 60000

# ramp：逐步建立
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session idle_session_buildup -mode ramp -count 20 -duration 60000

# random_hold：随机时长后释放
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session idle_session_buildup -mode random_hold -count 20 -duration 60000 -min-hold 10000
```

#### 2.4 预期结果与影响

1. **注入前：**
   - 目标数据库中没有这批 DBChaos 新建的连接。
   - 业务连接数和服务器资源保持基线水平。
2. **注入中：**
   - `fixed`：成功连接数快速上升，并在窗口内大致保持。
   - `ramp`：连接数按阶段逐步上升。
   - `random_hold`：连接数先增加，随后因随机释放而上下变化。
   - 数据库会话视图中应能看到配置账号建立的空闲会话。
   - 命令输出应显示连接成功数、失败数和随机提前释放数。
3. **资源影响：**
   - 每条连接占用一个连接槽。
   - 数据库可能为连接分配会话内存、线程或进程资源。
   - 空闲期间不持续执行 SQL，因此 CPU 通常低于持续查询型故障。
   - 当连接数接近上限时，其他客户端可能连接失败或排队。
   - CPU、内存和连接槽的变化取决于数据库实现、连接数和当前负载，不能保证固定幅度。
4. **注入后：**
   - DBChaos 关闭剩余连接。
   - 数据库会话数量应回到接近注入前水平。
   - 不应产生业务表数据变化。

---

### 3. 空闲事务积压

#### 3.1 前置条件

1. `-table` 指向 `chaos_test` 中已有且非空的实验表。
2. 账号可以读取该表。
3. MySQL/MariaDB 表使用 InnoDB。
4. 程序先设置只读和重复读事务，再读取一行；等待期间不继续执行 SQL。
5. 注入结束时调用 `rollback()` 并关闭连接，不提交数据。

#### 3.2 参数

| 参数 | 含义、单位和范围 |
| --- | --- |
| `-mode` | `fixed`、`ramp` 或 `random_hold`；默认 `fixed`。 |
| `-table` | 必填，普通表名或 `schema.table`；仅允许字母、数字、下划线和 `$`。 |
| `-count` | 事务数量；整数，1–50。 |
| `-duration` | 注入窗口；单位毫秒，1–600,000（最多 10 分钟）。`ramp` 下不得小于 `count`。 |
| `-min-hold` | 仅用于 `random_hold`；单位毫秒，1–`duration`；默认 `duration/2`。 |

#### 3.3 命令示例

```bash
# fixed
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session idle_transaction_buildup -table dbchaos_txn_demo -mode fixed -count 10 -duration 60000

# ramp
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session idle_transaction_buildup -table dbchaos_txn_demo -mode ramp -count 10 -duration 60000

# random_hold
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session idle_transaction_buildup -table dbchaos_txn_demo -mode random_hold -count 10 -duration 60000 -min-hold 20000
```

#### 3.4 预期结果与影响

1. **注入前：**
   - 实验表已存在且至少有一行。
   - 活动事务视图中没有这批 DBChaos 事务。
2. **注入中：**
   - 成功事务执行一次 `SELECT * FROM dbchaos_txn_demo LIMIT 1`，随后显示为空闲事务。
   - `fixed`：事务数快速达到目标并保持。
   - `ramp`：事务数逐步增加。
   - `random_hold`：事务随机回滚，活动事务数上下变化。
   - 命令输出应显示事务成功数、失败数和提前释放数。
3. **数据库与服务器影响：**
   - 每个事务占用一个连接，并保留事务快照。
   - 长时间快照可能延迟 PostgreSQL/openGauss 的部分版本清理。
   - 只读查询不写入业务数据，但仍可能产生读锁、元数据锁或存储引擎相关的读状态。
   - 连接和事务数量增加会提高会话管理及事务管理开销。
   - CPU 通常主要集中在建连和首次查询阶段；内存、锁等待和清理延迟取决于数据库配置及并发写入。
4. **注入后：**
   - 所有剩余事务执行回滚并关闭连接。
   - 活动事务和空闲事务数量应逐渐回落。
   - 实验表数据不应发生变化。

---

### 4. 会话强制终止

#### 4.1 前置条件

1. DBChaos 先建立目标会话，再用控制连接终止目标会话。
2. PostgreSQL/openGauss 控制账号需要终止其他会话的权限。
3. MySQL/MariaDB 控制账号需要 `KILL` 权限。
4. 默认控制连接复用 `db.properties` 账号，也可用控制账号参数覆盖。
5. 本 Case 不实现应用或连接池自动重连。

#### 4.2 参数

| 参数 | 含义、单位和范围 |
| --- | --- |
| `-mode` | `serial` 或 `burst`；默认 `serial`。 |
| `-count` | 目标会话数；整数，1–100；默认 10。 |
| `-interval` | `serial` 模式相邻终止尝试的间隔；单位毫秒，0–60,000；默认 500。目标会话也按此间隔建立。 |
| `-concurrency` | `burst` 模式并发终止数；整数，1–32；默认 `min(count, 10)`。 |
| `-control-user` | 控制连接用户名；默认使用配置账号。 |
| `-control-password` | 控制连接密码；默认使用配置密码。 |

#### 4.3 命令示例

```bash
# serial
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session session_forced_termination -mode serial -count 10 -interval 1000

# burst
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session session_forced_termination -mode burst -count 20 -concurrency 10
```

#### 4.4 预期结果与影响

1. **注入前：**
   - 数据库中没有这批 DBChaos 目标会话。
   - 目标数据库业务数据保持不变。
2. **注入中：**
   - 目标会话先出现在会话视图中。
   - `serial`：目标会话逐个被终止，数量逐步下降。
   - `burst`：多个目标会话在短时间内同时消失。
   - 命令输出应报告目标建立数、成功终止数、仍存活数和失败数。
3. **数据库与服务器影响：**
   - 被终止连接的会话资源由数据库回收。
   - 若目标会话有未提交事务，数据库通常需要回滚该事务。
   - 回滚量较大时，CPU、日志写入、锁清理和 I/O 可能短时增加。
   - 控制连接本身也会产生少量连接建立和管理开销。
   - 该故障不会自动创建新会话，不应把“应用重新连接”作为本 Case 的成功条件。
4. **注入后：**
   - DBChaos 清理仍可用的目标连接。
   - 目标会话应从数据库会话视图消失。
   - 未提交事务应被回滚；不应产生预期之外的业务数据写入。
   - 如果终止成功数为 0，应优先检查控制账号权限、数据库函数和驱动。

---

### 5. 连接认证失败

#### 5.1 前置条件

1. 配置 URL、网络和 JDBC 驱动必须能正常到达目标数据库。
2. 不需要创建测试账号。
3. 程序每次生成不存在的用户名，并继续使用配置密码发起登录。
4. 如果 URL 或驱动错误，结果可能被统计为“其他连接错误”，不代表认证失败。

#### 5.2 参数

| 参数 | 含义、单位和范围 |
| --- | --- |
| `-mode` | `serial` 或 `burst`；默认 `serial`。 |
| `-count` | 登录尝试次数；整数，1–200；默认 20。 |
| `-interval` | `serial` 模式尝试间隔；单位毫秒，0–60,000；默认 500。 |
| `-concurrency` | `burst` 模式并发数；整数，1–32；默认 `min(count, 10)`。 |
| `-login-timeout` | 单次登录超时；单位秒，1–60；默认 10。 |

#### 5.3 命令示例

```bash
# serial
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session connection_auth_failure -mode serial -count 50 -interval 500 -login-timeout 10

# burst
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session connection_auth_failure -mode burst -count 50 -concurrency 10 -login-timeout 10
```

#### 5.4 预期结果与影响

1. **注入前：**
   - 正常账号仍可连接数据库。
   - 数据库认证日志和失败计数处于基线水平。
2. **注入中：**
   - `serial`：认证拒绝按时间间隔逐个产生。
   - `burst`：短时间内产生一批认证拒绝。
   - 命令输出中的“认证拒绝”应增加。
   - “其他连接错误”应为 0，或能由网络、驱动和超时原因解释。
   - “意外连接成功”应为 0；若大于 0，应检查匿名用户、认证映射或数据库配置。
3. **数据库与服务器影响：**
   - 失败连接不会长期保留为已认证会话。
   - 数据库会产生认证处理、网络读写和错误日志开销。
   - 高频失败可能增加认证相关 CPU 使用率和日志量。
   - 某些数据库或安全组件可能触发失败次数限制、审计告警或临时封禁。
   - 影响大小取决于尝试次数、并发度、认证方式和日志配置。
4. **注入后：**
   - 失败连接应被关闭，不应残留正常业务会话。
   - 认证日志可能保留失败记录。
   - 本 Case 不修改用户、密码和业务表。

---

### 6. 连接握手停滞

#### 6.1 前置条件

1. URL 对应可达的单一主机和端口。
2. 第一版不支持 URL 查询参数、TLS、代理或多主机地址。
3. `silent` 不发送数据库协议报文。
4. `partial` 发送固定协议前缀后停滞。
5. 两个模式都不完成认证、不发送密码、不执行 SQL。
6. 可用 `-url` 覆盖配置 URL，但必须满足上述 URL 限制。

#### 6.2 参数

| 参数 | 含义、单位和范围 |
| --- | --- |
| `-mode` | `silent` 或 `partial`；默认 `silent`。 |
| `-count` | 并发 TCP 连接数；整数，1–200；默认 10。 |
| `-duration` | 每条连接进入停滞后的观察时长；单位毫秒，1–3,600,000；默认 60,000。 |
| `-connect-timeout` | TCP 建连超时；单位毫秒，1–60,000；默认 10,000，不含系统 DNS 解析。 |
| `-greeting-timeout` | MySQL `partial` 读取完整服务器问候的超时；单位毫秒，1–60,000；默认 10,000。 |
| `-url` | 可选的单地址、无查询参数、非 TLS URL 覆盖值。 |

#### 6.3 命令示例

```bash
# silent
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session connection_handshake_stall -mode silent -count 50 -duration 30000 -connect-timeout 10000

# partial
java -cp "config:lib/postgresql-42.7.2.jar:target/DBChaos-0.0.1.jar" chaos.Main --db postgresql session connection_handshake_stall -mode partial -count 50 -duration 30000 -connect-timeout 10000
```

#### 6.4 预期结果与影响

1. **注入前：**
   - 目标端口可连接。
   - 数据库未出现这批 DBChaos 未完成握手连接。
2. **注入中：**
   - 客户端输出应显示 TCP 建立数和进入停滞数。
   - `silent`：服务端收到 TCP 连接，但没有收到客户端启动报文。
   - `partial`：服务端收到不完整初始化报文，继续等待剩余数据或主动关闭连接。
   - 服务端是否把连接显示为未认证连接，以及何时清理，取决于数据库协议和超时配置。
3. **数据库与服务器影响：**
   - 服务端可能暂时占用未认证连接槽、连接处理线程、内存和网络缓冲区。
   - 客户端每条 Socket 和线程也会占用本地资源。
   - 连接数较大时，可能影响其他客户端建立连接。
   - CPU 通常主要用于建连和协议处理；停滞期间不会持续执行 SQL。
   - 若数据库主动关闭连接，客户端会记录 EOF 或传输错误；这不等于已经确认数据库执行了超时回收。
4. **注入后：**
   - 到达 `duration` 后 DBChaos 关闭仍持有的 Socket。
   - 客户端线程和本地 Socket 应释放。
   - 数据库是否立即清理未认证连接，需通过数据库视图和日志确认。
   - 本 Case 不应改变业务表数据。

## 结果确认方法

1. **空闲会话积压：**观察数据库会话视图中的连接数和空闲状态。
2. **空闲事务积压：**观察活动事务、空闲事务和等待事件；确认结束后事务数量回落。
3. **会话强制终止：**核对命令输出中的建立数、终止成功数和剩余存活数，并确认目标会话消失。
4. **连接认证失败：**核对认证拒绝数、其他连接错误数和意外成功数，必要时查看数据库认证日志。
5. **连接握手停滞：**结合 DBChaos 的 TCP 事件、数据库未认证连接视图和服务端日志判断。
6. CPU、内存、日志量和连接数会受到数据库配置及当前负载影响，不能只凭单项资源曲线判断注入成功。

