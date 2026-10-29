# 隔离开发服务运行说明

当前工程用于 D0–D2 的真实持久服务与合成数据验证。只允许显式 synthetic profile；测试身份、持久 Provider 替身和 CSV 夹具都不能作为真实登录、模型或业务资源接入证明。平台服务只允许 127.0.0.1 绑定及 JDBC 指向 workbench_platform 回环测试库，且要求数据库合成 marker；其他目标拒绝启动，未对外部署。

用户交付必须支持简单搭建，具体门槛见 [D4-DEPLOY](../docs/development/IMPLEMENTATION-PLAN.md#简单搭建交付验收d4-deploy用户首装待验收)。现在已有统一操作入口与可验证的合成交付准备；目标已确定为 Windows 电脑，正在准备 `Workbench.cmd` 与白名单发行目录/ZIP；完整用户安装器、首位真实管理员和精确输入预算仍待验收冻结。以下私有配置和分权账号是已有授权合成环境的前提，不声称已经完成普通使用者首装。

## Windows 发行准备（尚未实机验收）

**最简主路线已收敛为便携Java＋新私有MySQL**，已有环境仅兼容。`Workbench.cmd requirements` 无Java也能说明缺依赖；已有可信Java可用 `setup`/`setup --json` 查看只读方案。当前返回 `canInitialize=false`，不会下载、运行候选、生成凭据或初始化。3动作/0手填数据库密码只是目标，不是已完成体验；Windows包精确版本/SHA/许可、MSVC/UCRT、DPAPI策略及目标机器仍待核验，初始化执行器尚未接通。准备文件和精确范围见[首装目录](distribution/setup/dependencies.json)与[准备版说明](distribution/README.md)。

下面已有Java/MySQL/TLS/账号的路径保留为兼容预览，不再当作普通用户简单首装完成。新准备包另放 `backend/target/portable-setup-preparation/`，原 `backend/target/distribution/` 预览包未覆盖。


用户已选择 Windows 电脑。发行候选的主入口为 `Workbench.cmd`，双击进入中文菜单，使用现有Java21直接运行Jar中的专用CLI；不要求Python、PowerShell脚本、Docker Desktop、Node或Maven。程序自带前端；外部依赖仍为已有获准的本机MySQL8.4、TLS和原分权账号。此包仍只有合成身份/执行替身，不能用于真实业务数据或冒充生产登录。

菜单覆盖配置已有环境、只读预检、启动、状态、停止和“已有获准两库初始化并启动”。向导引用现有证书库及已有密码，不生成证书/账号或安装依赖；当前有6个提示，其中DB/界面端口有缺省，另外4个是现有证书库路径及3个密码。数量未达到或冻结建议3项预算。私有配置默认放在当前用户LocalAppData下的专用目录，不回写发行包。无权/缺依赖/迁移失败不就绪，不用管理员或关闭TLS绕过。

启动固定Jar、私有run目录和PID/启动时间/nonce/摘要；重复启动沿用原进程。停止只向原私有run写严格匹配的请求，由服务关闭Spring上下文后退出；不对其他PID发信号、不强杀或停止MySQL。Windows ACL/ProcessHandle可能不可用时保守报未知，不能改成猜测成功；其实际兼容性仍待Windows验收。源码版Linux诊断入口保留在下节。

维护者沿既有构建系统生成发行包（不会创建安装资源）：

```sh
npm --prefix frontend run build
./backend/mvnw -f backend/pom.xml clean package -DskipTests -B -ntp
mkdir -p backend/target
python scripts/release_bundle.py build --jar backend/platform-server/target/platform-server-0.1.0-SNAPSHOT.jar --output-dir backend/target/distribution
python scripts/release_bundle.py verify --archive backend/target/distribution/ai-test-workbench-0.1.0-SNAPSHOT-synthetic.zip
```

`-DskipTests` 仅用于这里的打包，不代表测试通过；Java定向测试和源码/发行检查单独运行。Maven输出时间规范化，归档按固定顺序/时间/权限生成；实际版本从Jar元数据取得，清单记录依赖、摘要和观察到的未提交状态，不冒充已发布提交或远端CI。

原预览包固定12文件；首装准备包v2固定17文件并加入只读目录/动作和不可执行模板，旧v1校验仍兼容。载荷含Jar、3份占位模板、Windows入口、维护者诊断/完整性脚本、说明/兼容性、LICENSE及清单/校验和。拒绝缺类/缺JDBC依赖的Jar、填写秘密的模板、额外/缺失文件、篡改、大小写冲突、穿越/ADS/Windows保留名、链接及超限内容；不复制数据库、Blob、运行配置、证据、源码、缓存或日志。嵌入合成初始化为预览程序本身的明确组成，不称为生产数据。校验和用于完整性，不替代可信发布来源或签名。

发行说明源文件：[README](distribution/README.md) · [兼容性与升级/恢复约束](distribution/COMPATIBILITY.md)。当前executor是Linux；中文/空格路径、解包和Java CLI离线通过不能替代Windows双击、NTFS权限、实际首装和停启。未运行真实安装/升级/备份恢复，未创建新资源或凭据。完整用户首装、A01空库链和远端CI仍待对应条件。

## 统一入口与健康检查

当前开发验证前提：Linux（`/proc`、文件锁、pidfd）、Java 21、Python 3.12、包含正式前端和两库迁移的预构建平台 Jar；已获准的回环 MySQL 8.4、受信 TLS 配置、原有两个测试库与分开的运行/迁移账号。入口不安装软件、不下载信任库、不创建实例/库/账号、不改变权限，也不停止既有 MySQL。平台、UI、合成执行器共用一个 JVM，无需额外前端服务器。Windows 发行候选入口复用 Java21，不要求 Docker Desktop、Python 或 PowerShell脚本；下面的Python入口保留用于内部Linux诊断，不是Windows主入口。

用 `template` 查看[运行模板](config/runtime.example.json)及[平台迁移模板](config/migration-platform.example.json)/[Runner迁移模板](config/migration-runner.example.json)，填写**原有获准资源**，将秘密配置保存在仓库外且仅本人可读（`600`）。`REPLACE_` 占位符不会被当成有效配置。共有 6 个运行键，URL 内参数也计为独立输入；没有达到或宣称已冻结“≤3项”的最终输入目标。

```sh
python scripts/synthetic_runtime.py template
python scripts/synthetic_runtime.py template --template-kind migration-platform
python scripts/synthetic_runtime.py template --template-kind migration-runner
python scripts/synthetic_runtime.py doctor --runtime-dir <private-runtime-dir> --config <private-runtime.json> --java-home <jdk21> --jar <prebuilt-platform.jar>
python scripts/synthetic_runtime.py up --runtime-dir <private-runtime-dir> --config <private-runtime.json> --platform-migration-config <private-platform-migration.json> --runner-migration-config <private-runner-migration.json> --java-home <jdk21> --jar <prebuilt-platform.jar>
```

`doctor` 只读：检查配置权限/键/目标/TLS、Java 版本、Jar 完整性、目录可写性、端口或受管进程身份、MySQL 版本/引擎/严格模式/UTC/隔离级别以及当前源码目标的 V1–V10 成功历史/校验和；不创建目录、改权限、迁移或写运行记录。`up` 先预检所有配置，依次用独立短期迁移进程检查/迁移平台和 Runner，再检查精确就绪并启动 JVM；Runner 迁移来自 Jar 内 `classpath:db/runner-migration`，无需源码目录或手工 SQL。迁移后的 Web 进程仅获得平台读写账号；没有迁移或 Runner 密码。已有数据上的重复检查应为 0 条新迁移，不能冒充完整空库安装证明。

日常运行沿同一入口，省略迁移参数，不重复初始化：

```sh
python scripts/synthetic_runtime.py up --runtime-dir <private-runtime-dir> --config <private-runtime.json> --java-home <jdk21> --jar <prebuilt-platform.jar>
python scripts/synthetic_runtime.py status --runtime-dir <private-runtime-dir> --config <private-runtime.json> --java-home <jdk21>
python scripts/synthetic_runtime.py restart --runtime-dir <private-runtime-dir> --config <private-runtime.json> --java-home <jdk21> --jar <prebuilt-platform.jar>
python scripts/synthetic_runtime.py stop --runtime-dir <private-runtime-dir> --java-home <jdk21>
```

`status` 核对固定制品/PID/启动代次与实际监听或服务就绪回执，再检查 active 合成会话；`doctor` 另查数据库精确版本。PID 存活不等于就绪。启动回执不含用户正文或凭据，不是身份令牌；每次用唯一 nonce 文件保留诊断证据。停止通过 pidfd 再核对进程身份并发送 SIGTERM，只作用于本目录记录的 JVM，保留 Blob、数据库、日志和历史回执。配置损坏时仍可只凭原运行目录/Java执行安全停止。重复 `up` 返回原 PID；无法确认归属或停止时，不强杀、不另起替代进程。

所有错误返回非零和固定 JSON `code/message`，不回显密码、连接串或原始异常；迁移进度为多行 JSON，最终一行为结果。常见处理：

| 错误 | 处理 |
| --- | --- |
| `CONFIG_*` / `TARGET_DENIED` / `DB_TLS_REQUIRED` | 核对模板及原授权配置、私有文件权限和受信证书，不改连真实库或关闭校验 |
| `JAVA_*` / `ARTIFACT_INCOMPLETE` | 指定已有 Java 21、由维护者提供包含前端/迁移/预检的完整 Jar，入口不编译或安装 |
| `PORT_IN_USE` / `OPERATION_BUSY` | 核查原进程/并发操作；已停止连接的 TIME_WAIT 不算活跃监听，不停止其他服务 |
| `DATABASE_*` / `SCHEMA_NOT_READY` | 核查已有实例、原账号、TLS 和历史；只修正确定问题，不删库/盲目 repair |
| `RUNNING_INITIALIZE_REFUSED` / `MIGRATION_FAILED` | 不并发迁移运行中的服务；先核查并优雅停止。失败保留私有迁移日志，服务不启动，未知 DDL 结果不自动重试 |
| `PROCESS_OWNERSHIP_UNCERTAIN` / `START_NOT_CONFIRMED` / `STOP_UNCONFIRMED` | 保留原记录和数据，先 `status`/`doctor` 核查；不自动重启或改后端重放 |

启动等待最长约30秒，停止最长约20秒；超过等待即保留未知状态，不杀掉未确认进程。迁移不自动强杀可能处于 DDL/COMMIT 的进程，需根据私有日志核查后再继续。服务和迁移日志分别保存在私有运行目录，不把原始日志提交到仓库。

升级备份/恢复尚未实现、未执行；不能把换 Jar 的 `restart` 当成已验证升级流程。跨目标环境首装、非开发人员搭建、非特权用户发行包、真实登录以及 MySQL 生命周期管理仍须后续验收。A01 新空实例、远端 hosted CI 保持未运行。

自动化检查（只对原有已获准合成环境执行）：

```sh
python -m unittest discover -s scripts/tests -v
python scripts/synthetic_setup_check.py --runtime-dir <private-runtime-dir> --runtime-config <private-runtime.json> --java-home <jdk21> --jar <prebuilt-platform.jar> --output <setup-results.json>
```

后一个检查对已就绪且无未决工作的本任务服务执行配置/端口/数据库错误实验及优雅停启，保留原数据，不新建资源。需要独占运行，不能对用户共用服务执行。Java/MySQL 回归也须在本受管服务已停止后独占运行，避免后台 Worker 扫描测试事务夹具；HTTP/浏览器/中断检查在该回归结束后串行执行。

## 工程和依赖

三个 Maven 模块分别为 provider-contract、platform-server、execution-runner。Runner 模块已有接纳仲裁和一次性持久启动意图基础，尚未接入真实 ACP 进程或证明资源侧 fencing。Java 目标为 21；Maven Wrapper 固定 3.9.11 并校验发行包 SHA-256；Boot 4.1.2 管理 Connector/J 9.7.0、Flyway core/mysql 12.4.0、Jackson 3.1.7。ACP 0.80.0、MCP 2.1.0 目前仅为离线测试依赖。

正式前端在 ../frontend，React 19.3.1、Vite 8.3.5，使用 package-lock.json。先构建前端，再打包服务，生成的静态资源进入同一服务 Jar。

```sh
npm --prefix frontend ci --ignore-scripts --strict-ssl=true
npm --prefix frontend run test:contracts
npm --prefix frontend run build
./backend/mvnw -f backend/pom.xml package
```

需要 JDK 21、兼容 Node 的本地构建环境，以及真实 MySQL 8.4。MySQL 集成测试以 WB_DB_URL、WB_DB_USER、WB_DB_PASSWORD 启用；权限测试另需 WB_RUNNER_DB_URL、WB_RUNNER_PASSWORD、WB_MIGRATION_USER、WB_MIGRATION_PASSWORD。没有这些配置时用例明确跳过，不能据此宣称数据库通过。

## 配置和迁移

凭据与配置保存在仓库外，不写入源码、命令参数或 Git。运行配置 JSON 使用以下键：

| 键 | 用途 |
| --- | --- |
| WB_DB_URL | 平台库 JDBC URL，固定 UTC；使用可信 MySQL CA 验证 TLS |
| WB_DB_USER / WB_DB_PASSWORD | 仅平台库读写的运行账号 |
| WB_BLOB_ROOT | 本轮隔离文件目录 |
| WB_PORT | 本地服务端口，当前验证用 18080 |
| SPRING_PROFILES_ACTIVE | 只允许 synthetic，其他组合拒绝启动 |

迁移使用独立配置，包含 WB_DB_URL、WB_MIGRATION_USER、WB_MIGRATION_PASSWORD。迁移进程退出后再启动 Web 服务；Web 进程不持有迁移或 Runner 密码。平台与 Runner 的数据库、账号和 Flyway 历史分开。启动/迁移先按 SHA-256 固定任务私有只读 Jar 副本，运行制品不会被后续 target 构建覆盖；PID 与实际制品摘要记录在私有 runtime-dir。

```sh
python scripts/synthetic_runtime.py up --runtime-dir <private-runtime-dir> --config <private-runtime.json> --platform-migration-config <private-platform-migration.json> --runner-migration-config <private-runner-migration.json> --java-home <jdk21>
```

当前任务的临时账号调整已单独获用户批准并验证。后续继续使用这些资源，不自行重建账号、放宽权限或删除数据库。新的环境需先落实相应资源授权。

当前平台迁移为 V7，Runner 为 V1。V5 给每条额度预占保存 `total` 与本次 `model:*` 账户键及原 UTC 日期；消费和释放只更新这对账户。旧记录仅在原范围可确定时回填，无法重建的范围保留为空，未知预占不自动释放。V6 保存独立理解记录和上下文清单；V7 保存完整 ExecutionSpec、冻结额度/能力快照及合成 Provider 的命令、事件和产物日志，不伪造旧记录。业务时间统一经 Spring 注入的 UTC `Clock` 并规范到微秒；测试使用可变时钟，不修改系统时间。审批范围的亚微秒输入明确拒绝，CSV 原始事件的更高精度仍按原值计算。

完整原始空库迁移链可在本地隔离环境验证，并不依赖推送或远端 CI。当前两个测试库已有需保留的数据，增量已到 V7；A01 的目标版本、失败历史、校验和及独立前缀中断门禁已有实测，原始空库全链仍待新隔离实例授权。不得清空现有库或自行新增库/账号来补证。

## 合成闭环检查

测试身份由 synthetic profile 的服务端夹具映射，前端身份选择器不是真实登录。admin 管配置与元数据，reviewer 处理已授权环境的固定审批，alice / pending 作为成员夹具；未开通身份没有环境。此模式不能用于任何真实数据。

```sh
python scripts/synthetic_service_check.py --runtime-dir <private-runtime-dir> --runtime-config <private-runtime.json> --java-home <jdk21>
python scripts/synthetic_hardening_check.py --output <review-evidence.json>
python scripts/synthetic_context_check.py --validate-api --output <context-evidence.json>
python scripts/synthetic_authorization_check.py --validate-api --output <authorization-evidence.json>
npm --prefix frontend run test:e2e
```

HTTP 检查实际访问本地服务、MySQL 和文件存储；差异工具使用 BigDecimal 计算合成 CSV。测试会创建有来源标记的合成配置、问题、审批及文件，保留已有数据。服务重启只针对脚本记录并核对过的本服务进程。Playwright 默认使用本地 Chromium，可用 CHROMIUM_PATH 指定受信浏览器路径。

结果及未覆盖项见[实施检查点](../docs/reviews/IMPLEMENTATION-PROGRESS-20261026.md)。真实 Provider、Agent 进程隔离、付费模型、GitLab/MCP 资源和生产身份仍在后续阶段。

## 契约、CI 与进程中断复验

```sh
node scripts/generate_provider_contract.mjs --check
node scripts/generate_handover_schema.mjs --check
npm --prefix frontend run test:handover
node scripts/check_api_responses.mjs --check
./backend/mvnw -f backend/pom.xml verify
python scripts/collect_java_results.py --require-complete --output <java-results.json>
python scripts/synthetic_service_check.py --validate-api --runtime-dir <private-runtime-dir> --runtime-config <private-runtime.json> --java-home <jdk21> --output <http-results.json>
python scripts/synthetic_hardening_check.py --validate-api --output <hardening-results.json>
python scripts/synthetic_crash_check.py --runtime-dir <private-runtime-dir> --runtime-config <private-runtime.json> --java-home <jdk21> --output <process-results.json>
```

生成合同有修改时先审阅 TypeScript 源，再用 --write 更新生成文件；平台 OpenAPI 由 ApiContractTest 比对，明确更新时仅该次构建注入 WB_UPDATE_API_CONTRACT=true。请求类型来自控制器 record，响应投影来源为 contracts/platform-responses.schema.json，并对实际回包校验。

进程中断脚本要求 Linux pidfd，只允许本任务已知容器或精确的 hosted-job 服务。它会在 6 个私有检查点终止并重启已记录的本服务进程，每个窗口约 5–35 秒，原数据及 Blob 保留。新增窗口在计算后、写输出前中断：重启后不重跑，保留未知占用；只有原本地进程已死亡且未进入写入阶段的持久证明，才允许显式取消。运行前要求无未决 Binding/outbox；不得在用户共用服务或真实数据环境运行。

Worker 通过 `ExecutionProvider` 的类型化端口提交、核查和导入；持久合成 Provider 独立推进计算，快照/事件查询不触发执行。成功结果含 CSV 与结构化摘要两份有摘要校验的后端文件，平台对用户发布一份 CSV。输入变化或来源撤权会在服务端使清单失效；理解记录不调用模型，工具集为空。真实执行器、跨主机进程隔离与真实费用均未在这里验收。

本地 CI 配置已提供，远端执行及新的 hosted-job MySQL 准备分支未运行。不要在本地设置伪 CI 环境绕过 ci_prepare_mysql.py 的限制；当前任务继续使用已批准账号及两个测试库。

## 原提交恢复与迁移就绪门禁

JSON 写命令在本标签页先保存原命令键、命名空间、固定参数和内容摘要，再发送请求；消息同时固定 clientMessageId。网络/5xx 结果未知时，页面和弹窗提供“核查并继续原提交”，先用原身份查询 command-lookups，查不到才按原键、原内容重试。刷新不自动发送；切换身份会清除草稿正文和请求正文，只保留待核查引用，切回须重新认证后显式核查；若原请求未到达且正文已清除，需重新填写完全相同内容，仍沿用原键。不同逻辑提交只有在上一提交已确定后才分配新键。附件不缓存文件字节，必要时重新选择同一文件并核对摘要。

## 固定 CSV 接管包

在原问题对话点击“导出接管包”，或由当前 owner 请求 `GET /api/conversations/{id}/handover`。返回 `{package, packageSha256}`；浏览器核对包的 JCS/SHA 后下载 JSON。包内包含固定范围及原审批哈希、Binding/接纳/停止事实、原上下文和材料摘要、已核验产物及用户验证、未决项和未分派责任说明；不含消息正文、附件字节、凭据或私有路径。管理员元数据权限、reviewer 正文租约都不等于导出权。

每次导出重新鉴权并记录审计，不能改变业务状态或额度。开放执行保留拥有者未知和原占用；停止请求与权威取消/未接纳证明分开，缺失的历史证明不补造。新输入后可导出标为 stale 的范围与未决项，旧结果不附入；会话来源撤权时拒绝整包导出。无持久摘要锚的历史数值保留为空。接管包与 CSV 的晚响应遇到身份代次或已观察到的会话水位变化，会停止下载。

缓存仅用 sessionStorage，按服务端 subjectId 划分，标签页互相独立；退出当前身份和权限丢失时清除正文。此处仍是 synthetic 身份夹具，不是生产登录方案。配置草稿保存新增必需 expectedDraftHash（由列表的 draftHash 提供）并结合发布 revision 做 CAS，避免两个标签在同一发布版本下覆盖彼此草稿；冲突会保留编辑输入，显式载入当前基础后再保存。

启动现在用运行账号只读验证原 Flyway 历史、精确 V1–V7 成功记录和迁移校验和，缺失/失败/未知未来版本均不能启动；运行 Web 服务不会自动 migrate/repair；统一入口只在显式提供独立迁移配置时协调迁移。A01 的 MySQL 故障测试仅新建 a01_<随机前缀>_* 表和独立历史（新测试历史有明确 baseline 0），验证 DDL 已持久但迁移进程中断时仍不就绪。它不是原始空库链的替代证明，原库/历史不清空。额外隔离实例方案见实施证据目录 a01-empty-instance-proposal.json，未获单独授权前不创建。

```sh
npm --prefix frontend run test:submissions
WB_UI_REPORT=../docs/reviews/implementation-d0-d2/ui-submission-readiness-checks.json npm --prefix frontend run test:e2e
```

## A04 并发和事件一致性

事件发布自身加入事务，确保会话计数与事件行一起提交/回滚；已有业务事务内调用会加入原事务。终态重放先比较已存同一事件的规范哈希，再判断 Binding 是否已关闭，避免把关闭后的异内容重放当作成功。原哈希域、冻结账户键/周期和数据库唯一约束未改。

`ConcurrentTransactionsTest` 的 9 项检查保持首个事务未提交，让不同连接的第二个事务已开始再放行；覆盖同 NodeRun 绑定、同键/消息 ID、回滚重试、一次终态结算和事件/快照。`EventTerminalConsistencyTest` 的 2 项检查保留了修复前失败：真实 SQL 插入拒绝不应推进计数，同终态事件异内容必须冲突。屏障和连接观察器仅存在测试代码。

```sh
./backend/mvnw -f backend/pom.xml -pl platform-server -am -Dtest=ConcurrentTransactionsTest,EventTerminalConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

重复本地 HTTP/浏览器检查保留实际累计消费，不能把旧消费归零。当专用合成 Alice 账户剩余额度不足 20 且没有预占/活跃槽时，夹具通过既有受审计管理员 API 补足 100 单位测试余量，并为新测试环境设置一致上限；逐项核对消费/预占未变。该设置不涉及真实费用或数据库权限，产品默认值不变。仍有未决任务时先按原拥有者核查，不借配额调整释放未知占用。

## D3 新增迁移与开发边界

当前源码新增平台 V8（资源操作消费审计）、V9（独立会话摘要）、V10（独立账号及限流）与 Runner V2/V3（启动意图与命令命名空间）。平台启动门禁目标为 V10；旧 V7 数据库须由独立迁移入口在获准、服务停止且备份/恢复方案明确后升级，不能绕过门禁。Runner 启用前需验证 V2/V3 的完整历史；V2 将历史已接纳行保守标记为已有启动意图，禁止因缺记录重启。新增迁移尚未在本环境执行或通过真实 MySQL 验证，不能把离线测试当作升级成功。现有已打包制品及其 V7/V1 实测证据保留为原版本事实。
