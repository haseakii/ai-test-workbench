# 最小合同与定向设计修复

日期：2026-10-26。本轮已获授权在隔离开发环境推进 D0–D2；真实模型、业务资源、外部部署和推送不在范围内。工作区已通过 Git 快进完整同步至 2ae5c5，原有四份计划文档已备份并恢复；不是手工拼接仓库基线。

## 数值与规范摘要

revision、epoch、sequence、计数和字节数统一为 0 至 9007199254740991；Java long 与 MySQL BIGINT 的更大范围不对外开放。API、数据库 CHECK 和 TypeScript 校验共同落实。内部 ID 使用 UUID，外部 Provider ID 保持不透明字节语义。金额为 BigDecimal 对应的十进制字符串，不能转成浮点数。

摘要使用 RFC 8785 的规范 JSON UTF-8 和 SHA-256；拒绝重复对象键、非有限数值和不合法 Unicode。对象键按规范排序，数组顺序保留，null 与缺省不同。平台权威时间固定 UTC 六位微秒；原始外部时间另存。command 摘要绑定方法名和请求体，排除 command 本身与认证/追踪 envelope；operation 摘要绑定结构化范围。ExecutionSpec 的 executionContentHash 仅排除 admission 字段，specHash 覆盖完整 spec，避免准入凭据自引用。合成占位示例保留标记，新增黄金样例才作为可复算依据。

## 审批身份与固定摘要

ApprovedOperation 改为携带 DecisionAuthority，与 BusinessApprovalRecord 使用同一身份判别式。human 保留实际 subject/资格，policy 保留 grant、签发人、服务身份及匹配证明；不再强制填一个人工 approverSubjectId。批准操作中的 authority 必须等于原决定，不能由 Provider 或请求方替换。

审批摘要从被 operationHash 覆盖的结构化计划生成：系统环境、输入哈希、数据集、区间、时区、状态、币种、输出范围、预算及有效期。显示摘要和决定引用相同不可变计划，不用独立可编辑文本或聊天摘要决定执行范围。最新 ADMIN-DAILY-UI 已有该原则，本轮只补机器合同和执行验证。

## 首次接纳与期限

start 携带 AdmissionTicket，绑定 ticketId、Binding、executionContentHash、授权修订、批准及其修订、notBefore/notAfter 和可信 authorityRef。notAfter 不晚于批准期限、准入有效期和执行政策 deadline。它不是 HTTP 等待超时；已经合法接纳的长任务不因批准期限经过而被重放或改写。

接纳端在持久化首次 accepted 之前，经认证调用 authority，锁住当前授权、配置守卫、批准、停止标记和 ticket，原子复查并单次消费。接纳以该消费为线性化点，幂等重查返回原事实；消费后回执未知只能核查，不能换 ticket 重投。authority 不可达、期限已过或版本不符时 fail closed。真实分离 Provider 若无法落实该协议，admission_guard 不标 verified 并阻断对应流程。

撤权以同一守卫事务阻断尚未消费的准入；先合法消费、后撤权的执行保留接纳事实，立即阻断后续资源许可并请求停止/核查。资源服务每次操作仍校验当前授权。D1 持久替身可在同一隔离 MySQL 事务验证；它不证明 D3 外部后端支持该能力。

## 五项既有评审复核

| 事项 | 最新基线事实 | 本轮处理与验证目标 |
| --- | --- | --- |
| 分派中权威终态和停止竞态 | N09/N10、B09/B12 缺部分分派中来源状态 | 补 dispatching、submitting、acceptance_unknown 的合法停止/直接终态边；必须有一致接纳事实、原拥有者不活跃和已知副作用；终态不回退 |
| 快照与游标一致性 | 有 eventWatermark，但未规定原子返回续读位置 | ExecutionSnapshot 增加 resumeCursor；快照、投影修订、水位与游标在同一一致性读取得，续读严格大于水位；快照之后事件不能跳过 |
| 来源撤权与派生内容 | CONTEXT-MEMORY 已要求派生结果/文件/缓存重授权；INTEGRATION-AND-API 已禁止重用含撤权材料的 Agent session | 保留现有原则；来源依赖映射用于同步读时阻断，异步清理不作为权限门禁。不能证明原生会话已净化时标 session unusable，不恢复旧 session |
| 审批摘要与范围 | ADMIN-DAILY-UI 已要求摘要和 operationHash 对应同一固定计划 | 保留交互；类型化计划作为唯一摘要数据源，变更任一语义参数使摘要与批准一起失效 |
| 显式变更集及紧急撤权 | 已有对象 expectedRevision、所选变更集及原子发布原则 | 发布还必须 CAS 复核未选但被引用依赖的基础修订；相关依赖漂移整批回滚。紧急停用/撤权走独立即时守卫命令，不被未发布草稿阻塞，也不发布其他草稿 |

## 机器合同与状态来源

docs/design/contracts/execution-provider.d.ts 为 Provider 结构来源，严格 TypeScript 编译后生成完整 provider.schema.json、core.schema.json 投影、12 方法 Java ExecutionProvider 与 ProviderTypes DTO。--check 比对过期产物；共享 provider-vectors.json 在 Java 和 TS 两端验证请求/回执、联合类型及完整哈希域。生成/编译通过不等于真实适配器已通过共同契约。state-transitions.json 仍是五层转换唯一目录，Markdown 表同步。CP23 起补对应竞态与跨语言用例，真实后端状态保持 not_run。D0 用 Java/TypeScript 黄金样例及 Schema 验证，D1/D2 用真实 MySQL 和服务故障试验验证。
