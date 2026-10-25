# 分层状态转换表

状态：初始设计评审稿。以下由 [state-transitions.json](contracts/state-transitions.json) 生成，作为唯一转换目录；原型状态浏览页读取同一目录。未列出的边默认拒绝，重复事实只幂等确认。终态不回退，重开和合法外层重试创建新对象；模拟场景重置不是生产状态转换。

## 归属与聚合

| 层 | 真相拥有者 | 关键约束 |
| --- | --- | --- |
| 问题 | 业务平台/授权用户 | 后端完成不能触发 resolved；用户确认绑定结果版本。 |
| 运行 | 业务平台 | 聚合业务节点；waiting/reconciling 不等于失败；handoff 不释放未知拥有权。 |
| 节点 | 业务平台 | 只调度业务阶段；内部 attempts 由后端负责，外层重试创建新 NodeRun。 |
| Binding | 平台不可变身份 + 后端执行事实 | 连接健康另存，不用 offline 覆盖执行事实；一个未关闭 Binding 即占用。 |
| 审批 | 业务审批服务 | 决定、送达、动作状态分开，后两者见审批恢复文档。 |

运行开始后绑定 provider、连接 revision、Agent、工作区、模型请求、Skill、资源范围与预算。修改默认连接不迁移在途运行。能力 unknown/unsupported、模型未确认、预算未落实、审批桥接不可用时启动门禁明确阻断。

## 问题会话（issue）

初始状态：`awaiting_user`。状态全集：`open`、`awaiting_user`、`awaiting_verification`、`resolved`、`handoff`、`archived_unconfirmed`。

| ID | 从 | 触发 | 到 | 责任主体 | 守卫条件 | 原子组 |
| --- | --- | --- | --- | --- | --- | --- |
| I01 | awaiting_user | conditions_confirmed | open | 提交人/授权负责人 | 必需条件及环境固定 | T_CREATE |
| I02 | open | input_required | awaiting_user | 业务流程 | 仅缺阻塞条件 | T_PROJECT |
| I03 | open / awaiting_user | result_delivered | awaiting_verification | 业务服务 | 运行成功且证据/文件授权及完整性满足 | T_RESULT |
| I04 | awaiting_verification | user_resolved | resolved | 提交人/授权负责人 | 对当前输入轮次及 resultRevision 的明确确认，必要来源仍有效 | T_VERIFY |
| I05 | awaiting_verification / resolved | user_reopened | open | 提交人/授权负责人 | 旧执行安全关闭；新轮次保留旧结果 | T_REOPEN |
| I06 | open / awaiting_user / awaiting_verification | handoff_requested | handoff | 授权负责人 | 接管包及责任人明确；不释放未知拥有权 | T_HANDOFF |
| I07 | awaiting_verification | archive_unconfirmed | archived_unconfirmed | 授权负责人 | 未收到解决确认，不计入解决率 | T_VERIFY |

## 流程运行（run）

初始状态：`draft`。状态全集：`draft`、`ready`、`active`、`waiting`、`reconciling`、`stopping`、`succeeded`、`failed`、`cancelled`、`handoff`。

| ID | 从 | 触发 | 到 | 责任主体 | 守卫条件 | 原子组 |
| --- | --- | --- | --- | --- | --- | --- |
| R01 | draft | preflight_passed | ready | 业务服务 | 固定流程、能力、权限及预算校验通过 | T_CREATE |
| R02 | ready | dispatch_started | active | 业务服务 | 节点与唯一 Binding、预算、outbox 原子保存 | T_DISPATCH |
| R03 | active | wait_required | waiting | 业务服务 | 存在等待输入/审批节点，禁止计费轮询 | T_PROJECT |
| R04 | waiting | wait_cleared | active | 业务服务 | 输入采纳或批准已送达且执行前复查通过 | T_PROJECT |
| R05 | active / waiting / stopping | observation_unknown | reconciling | 业务服务 | 接纳/执行/停止事实未知 | T_PROJECT |
| R06 | reconciling | active_recovered | active | 业务服务 | 权威快照确认原执行活跃 | T_PROJECT |
| R07 | active / waiting / reconciling | cancel_requested | stopping | 授权用户/业务服务 | 登记停止命令，不宣告已取消 | T_CANCEL |
| R08 | active / waiting / reconciling / stopping | all_nodes_succeeded | succeeded | 业务服务 | 必要节点全部成功、无活跃执行/未知副作用、产物可用 | T_RESULT |
| R09 | stopping / reconciling | cancel_confirmed | cancelled | 业务服务 | 原拥有者不活跃且副作用已知 | T_CLOSE |
| R10 | ready / active / waiting / reconciling / stopping | failure_confirmed | failed | 业务服务 | 权威失败且无活跃执行/未知副作用 | T_CLOSE |
| R11 | ready / active / waiting / reconciling / stopping | handoff_requested | handoff | 授权负责人 | 接管包完整，未知占用仍保留 | T_HANDOFF |

## 节点运行（node）

初始状态：`blocked`。状态全集：`blocked`、`ready`、`dispatching`、`active`、`waiting_input`、`waiting_approval`、`reconciling`、`stopping`、`succeeded`、`failed`、`cancelled`、`handoff`。

| ID | 从 | 触发 | 到 | 责任主体 | 守卫条件 | 原子组 |
| --- | --- | --- | --- | --- | --- | --- |
| N01 | blocked | dependencies_ready | ready | 业务流程 | 必需输入及前置节点满足 | T_PROJECT |
| N02 | ready | dispatch_started | dispatching | 业务服务 | 未存在另一未关闭 Binding | T_DISPATCH |
| N03 | dispatching | execution_active | active | 后端事实投影 | accepted 回执及原执行活跃已确认 | T_PROJECT |
| N04 | active | input_required | waiting_input | 业务流程 | 缺少阻塞条件 | T_PROJECT |
| N05 | active | permission_required | waiting_approval | 后端事实投影 | 具体 operationHash 与 requestId 已保存 | T_APPROVAL |
| N06 | waiting_input / waiting_approval | wait_cleared | active | 业务服务 | 采纳回执/有效权限送达及复查 | T_PROJECT |
| N07 | dispatching / active / waiting_input / waiting_approval / stopping | observation_unknown | reconciling | 后端事实投影 | 失联或外部结果未知 | T_PROJECT |
| N08 | reconciling | active_recovered | active | 后端事实投影 | 原执行权威快照一致 | T_PROJECT |
| N09 | active / waiting_input / waiting_approval / reconciling | cancel_requested | stopping | 授权用户/业务服务 | 保留正在发生的动作 | T_CANCEL |
| N10 | active / reconciling / stopping | execution_succeeded | succeeded | 后端事实投影 | 终态、不活跃、副作用已知、输出合同满足 | T_CLOSE |
| N11 | stopping / reconciling | cancel_confirmed | cancelled | 后端事实投影 | 权威停止及副作用已知 | T_CLOSE |
| N12 | dispatching / active / waiting_input / waiting_approval / reconciling / stopping | failure_confirmed | failed | 后端事实投影 | 明确拒绝或安全失败；未知不得走此边 | T_CLOSE |
| N13 | blocked / ready / dispatching / active / waiting_input / waiting_approval / reconciling / stopping | handoff_requested | handoff | 授权负责人 | 保留原 Binding 事实与占用 | T_HANDOFF |

## 执行绑定（binding）

初始状态：`prepared`。状态全集：`prepared`、`submitting`、`acceptance_unknown`、`accepted`、`active`、`waiting_permission`、`stopping`、`reconciling`、`terminal`、`rejected`。

| ID | 从 | 触发 | 到 | 责任主体 | 守卫条件 | 原子组 |
| --- | --- | --- | --- | --- | --- | --- |
| B01 | prepared | start_sent | submitting | 适配器 | 先保存接纳日志再发送 | T_SEND |
| B02 | submitting / acceptance_unknown | acceptance_found | accepted | 后端回执 | 同键同哈希及外部运行引用 | T_ACCEPT |
| B03 | submitting | response_lost | acceptance_unknown | 适配器 | 接纳事实无法确认 | T_PROJECT |
| B04 | prepared / submitting | rejection_proven | rejected | 后端回执 | 明确未接纳且不可延迟接纳 | T_CLOSE |
| B05 | acceptance_unknown | admission_closed | rejected | 接纳端 | not_accepted_final + admissionFence | T_CLOSE |
| B06 | accepted | execution_active | active | 后端快照 | 原执行活跃 | T_PROJECT |
| B07 | active | permission_required | waiting_permission | 后端请求 | 原生请求与动作哈希可定位 | T_APPROVAL |
| B08 | waiting_permission | permission_delivered | active | 桥接回执 | 有效批准与当前请求；送达不等于已执行 | T_PROJECT |
| B09 | accepted / active / waiting_permission | cancel_requested | stopping | 适配器 | 先登记取消请求 | T_CANCEL |
| B10 | accepted / active / waiting_permission / stopping | observation_unknown | reconciling | 适配器 | 断线/回执冲突/副作用未知 | T_PROJECT |
| B11 | reconciling | active_recovered | active | 权威快照 | executionActive=true，原拥有者不变 | T_PROJECT |
| B12 | active / waiting_permission / stopping / reconciling / accepted | terminal_confirmed | terminal | 权威快照 | 终态 outcome + executionActive=false + effectStatus 已知 | T_CLOSE |

## 业务审批决定（approval）

初始状态：`pending`。状态全集：`pending`、`approved`、`denied`、`changes_requested`、`expired`、`revoked`、`superseded`。

| ID | 从 | 触发 | 到 | 责任主体 | 守卫条件 | 原子组 |
| --- | --- | --- | --- | --- | --- | --- |
| A01 | pending | approve | approved | 合格审批人 | CAS、当前映射、计划/期限/权限有效 | T_DECIDE |
| A02 | pending | deny | denied | 合格审批人 | 意见必填、CAS | T_DECIDE |
| A03 | pending | request_changes | changes_requested | 合格审批人 | 意见必填，新计划另建审批 | T_DECIDE |
| A04 | pending / approved | business_expired | expired | 服务端时钟 | validUntil 已过，未开始动作不可再消费 | T_EXPIRE |
| A05 | pending / approved | revoke | revoked | 有权撤销者 | 停止未来动作，已开始的另核查 | T_EXPIRE |
| A06 | pending / approved | plan_or_request_replaced | superseded | 业务服务 | 参数漂移或原生请求失效；保留历史，新审批重新确认 | T_APPROVAL |

## 跨层原子组与恢复

| 原子组 | 同一平台事务内保存 | 事务外及恢复 |
| --- | --- | --- |
| T_CREATE | 问题/轮次、流程快照、条件及审计 | 不先调用 Provider 再补业务身份。 |
| T_DISPATCH | NodeRun、唯一 Binding、预算预占、outbox | 发送器用同键；响应丢失保留未知。 |
| T_SEND / T_ACCEPT | 适配器接纳日志/回执与外部引用各自持久化 | 两个系统无全局事务；通过核查收敛。 |
| T_PROJECT | 事件去重记录、各层投影、事件水位、通知 outbox | 旧事件不回退；跳号先读快照。 |
| T_APPROVAL / T_DECIDE | 审批映射/决定、revision CAS、审计、分派记录 | 外部送达独立回执；未知不重复批准或执行。 |
| T_CANCEL | 停止请求、分派抑制标记、取消 outbox | 不立即 cancelled；取权威停止/完成事实。 |
| T_EXPIRE | 失效决定、阻断标记、停止/核查 outbox | 已发生的动作保留，不承诺撤销。 |
| T_CLOSE | 最终快照、节点结果、占用关闭证明、用量来源记录 | 费用未知部分挂账，不能假定归零。 |
| T_RESULT | 必需节点/产物检查、运行成功、问题待验证及结果引用 | Agent 不能确认业务解决。 |
| T_VERIFY / T_REOPEN | 人工验证记录或新轮次、旧结果引用及审计 | 旧运行只读；重开不重奖、不抹证据。 |
| T_HANDOFF | 责任人、接管包引用及业务状态 | 原 Binding 和未知预算仍挂起，禁止自动替代。 |

原生审批请求失效后，业务层保持等待或进入核查，delivery=stale；新请求及新审批均为新记录，旧批准不得回到 pending。重试/新轮次前必须证明旧执行安全关闭；仅 task completed、租约过期、普通 not_found 和停止请求均不足。
