# 术语与边界

| 术语 | 含义与归属 |
| --- | --- |
| 测试协作平台 | 本项目；业务入口与治理层，独立于总控 |
| 总控中心 | `haseakii/multi-agent-control-plane`；可选执行后端，不是本项目父系统 |
| Conversation | 一个业务问题的持续会话；不是 ACP session |
| WorkflowRun / NodeRun | 平台的业务流程运行及节点；不要复制外部任务引擎 |
| ExecutionProvider | 平台执行后端契约；不要求所有后端使用同一底层协议 |
| ExecutionBinding | 一次节点执行绑定 provider + 外部 run/task/session 引用 |
| Direct Agent Host | 后端 ACP Client 与进程/会话宿主；不是浏览器，也不是第二套总控 |
| AssistantProfile | 对用户展示的业务助手，绑定流程和允许的执行策略 |
| Skill | 版本化方法、约束和交付要求；不授予资源权限 |
| MCP | 工具接入协议；配置成功不等于工具已受控或实机通过 |
| ApprovedOperation | 绑定具体动作和前置条件的批准，不能扩展为任意执行许可 |
| Evidence / Artifact | 来源可定位的证据与交付文件，访问受业务权限控制 |
| 问题解决 | 提交人或获授权负责人确认；不由 Agent 完成事件自动判定 |

业务代码的 Java/MySQL 环境与平台自身技术栈是两项选择。先沿既有 TypeScript/React 路径评估，但本次不冻结版本、数据库产品或部署拓扑。
