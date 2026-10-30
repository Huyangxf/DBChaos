# 连接认证失败

Case：`session connection_auth_failure`。支持 PostgreSQL、openGauss、MySQL/MariaDB；实现只通过 JDBC 发起登录，不执行数据库专属 SQL。

每次运行随机生成一个符合常见用户名长度限制、几乎不可能存在的 `dbchaos_x_...` 用户名，并使用当前配置的 JDBC URL 和密码连接。注入器不创建用户、不更改或锁定真实账号，成功连接时也会立即关闭。认证拒绝依据 JDBC SQLState class `28` 识别；连接拒绝、超时、DNS、驱动或配置错误另列为连接错误，不能算作认证失败。

| 模式 | 行为 |
| --- | --- |
| `serial` | 按 `-interval` 间隔逐次发起认证，模拟持续、低频的错误登录 |
| `burst` | 以 `-concurrency` 并发执行指定数量尝试，模拟短时间认证失败突增 |

## 命令示例

使用新版 fat jar（包含 JDBC 驱动）：

```bash
java -cp "config:target/DBChaos-0.0.1.jar" chaos.Main --db mysql session connection_auth_failure -mode serial -count 20 -interval 500
java -cp "config:target/DBChaos-0.0.1.jar" chaos.Main --db mysql session connection_auth_failure -mode burst -count 20 -concurrency 10
```

将 `--db mysql` 分别改成 `--db postgresql` 或 `--db opengauss`，并确保 `config/db.properties` 中对应数据库的 URL、用户名、密码正确。若单独使用非 fat jar，则 classpath 还要包含对应 JDBC 驱动。

## 参数

| 参数 | 默认值 | 限制/含义 |
| --- | --- | --- |
| `-mode` | `serial` | `serial` 或 `burst` |
| `-count` | 20 | 总尝试次数，1～200 |
| `-interval` | 500 ms | serial 模式两次尝试之间的间隔，0～60000 |
| `-concurrency` | `min(count, 10)` | burst 并发数，1～32 |
| `-login-timeout` | 10 秒 | JDBC 登录超时，1～60 秒 |

检查输出中的 `认证拒绝` 计数。若它为 0，而 `其他连接错误` 大于 0，应先排查 URL、端口、网络、驱动、证书和数据库服务状态。某些数据库可能将异常认证形式的 SQLState 映射不同；服务器版本上的真实 SQLState 需由使用者验证。

## 验证注意事项

- 使用专门的实验实例；认证失败可能写入数据库安全日志或审计日志。
- 先用小规模参数，例如 `-count 5`，确认数据库的认证失败策略和日志行为。
- 不要设置很高的 burst 并发或次数。数据库可能有限速、暂时封禁、告警或安全审计策略。
- 程序不主动写数据库数据，也不会保留已成功打开的连接。
- 该故障验证的是连接认证拒绝路径，不等同于连接池耗尽或已建立会话积压。
