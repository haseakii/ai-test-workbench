# 审批等待与恢复

状态：初始设计合同草案。审批原型只模拟受限文件导出，未连接真实 Agent 或执行服务。

## 1. 三种独立状态

业务审批 decisionState：pending / approved / denied / changes_requested / expired / revoked / superseded。

权限桥接 deliveryState：unmapped / awaiting_request / ready / sending / delivered / delivery_unknown / stale / unsupported / denied_by_provider。

动作 actionState：not_started / dispatching / running / result_unknown / verified / failed / cancelled。

approved 不是 delivered，delivered 不是 verified。每个获准动作只有一个 operationId；资源服务以 operationId + operationHash 去重，消耗批准和登记动作须原子完成。原生 allow_always 不得自动转成业务长期授权。

## 2. 审批绑定

BusinessApproval 保存审批人、资格依据、申请人、Binding、operationId、operationHash、计划 revision、系统环境、参数/目标/前置条件哈希、影响预览、预算、validUntil、expectedRevision 和决定键。映射另存 providerPermissionRequestId、requestContextHash、mappingRevision、原生有效性观察及送达回执。

operationHash 覆盖操作类型、环境、参数、目标集合、前置状态、输出范围和资源授权引用；更改任何一项使旧批准失效。凭据和客户数据不进入可见示例。

## 3. 路径及责任

| 触发情形 | 平台保存与界面 | 后端/资源侧动作 | 退出条件 |
| --- | --- | --- | --- |
| 首次请求 | Node 等待审批，显示完整固定计划 | 原拥有者暂停敏感动作 | 合法批准且映射仍有效 |
| 长时人工等待 | 持久化计划、检查点、负责人；不反复调模型 | 保持暂停或安全结束本轮 | 恢复前重查请求有效性 |
| 请求已取消、断连或实现超时 | delivery=stale，冻结“发送批准”；保留旧决定 | 只核查原请求及动作回执 | 明确未执行且支持安全检查点 |
| 安全恢复 | 原 Binding 下 preparePermissionResume，展示新旧关联 | 不执行工具，仅准备新请求 | 新 requestId、新 mappingRevision、新业务审批 |
| 原生无法安全恢复 | 提供接管包，状态 handoff | 不重放旧 option，不启动替代任务 | 人工提供核查结果后另行决定 |
| 业务批准过期/撤销 | 禁止未开始动作；保留已开始事实 | 发送停止并核查，不承诺回滚 | 实际停止/完成及副作用确认 |
| 计划或前置条件漂移 | 旧审批 superseded，新计划重新取证 | 不接受旧 operationHash | 新资格、批准及执行前复查 |
| 决定已保存、响应超时 | decision 保留，delivery_unknown | 查询原请求/决定回执；不改投后端 | 确认 delivered、未送达或继续未知 |
| 内层 deny | denied_by_provider，显示具体原因 | 拒绝执行，外层批准不覆盖 | 修订新计划或人工接管 |
| 无审批桥接 | unsupported，显示原生工作台处理提示 | 不伪造平台“已批准”能力 | 后续受限桥接真实验收 |

ACP 官方权限请求没有统一固定超时。原生请求寿命按 Agent、协议版本、当前 prompt turn 和连接状态确认；原生 expiresAt 可为 null，但 null 不等于永久有效。[官方权限请求](https://agentclientprotocol.com/protocol/v1/tool-calls#requesting-permission)

## 4. 恢复步骤

1. 暂停分派；保存 checkpointRef、原生请求、原批准、最后事件水位及未知动作。
2. 原拥有者 getSnapshot：确认动作未发生、executionActive 的实际值、请求可响应性；未知则停在核查。
3. 请求仍有效且批准未变：执行前重查资格、哈希、期限、权限和预算，然后以原决定键发送。
4. 请求失效：旧批准标 superseded（本草案采取保守策略，即便计划未变也重新批准）；旧 requestId/optionId 永久不可发送。
5. 仅 preparePermissionResume 已被真实验证且不会执行工具时，在原 Binding 取得新请求。新审批关联前一审批，重新展示影响及前置状态，默认不勾选确认。
6. 人工批准后仍重新验证，登记送达命令；得到明确回执才显示 delivered；资源服务核查成功才显示 verified。
7. 任何步骤不支持、未知或过期，返回等待/核查/接管，不能“自动通过”或切后端重跑。

## 5. 事务边界和竞态

T1：决定 revision CAS、资格/期限检查、决定审计及分派 outbox 同事务。双人批准只允许一次转换；拒绝/过期不能被迟到批准覆盖。

T2：发送前重查当前批准、撤权、原生请求映射和有效性；记录发送尝试及命令哈希。响应丢失保留 delivery_unknown；只有后端响应幂等且能确认同请求未消费时才可重发同决定。

T3：资源服务原子校验并消费 operationId，执行前复查目标与条件；执行回执关联原动作。外部系统无法提供事务/幂等时，断线结果为 unknown，不自动再做。

T4：消费权威动作回执、更新节点投影及记录用量来源同一平台事务；事件重放不重复结算。已执行后的撤销仅撤销未来权限，补偿为另一项明确审批动作。

取消发生在批准之后：停止审批发送及新分派，查询动作实际状态。可能已经执行，不能显示“取消成功因此没发生”。租约结束不清除未核查动作。

## 6. 接管包

包含问题与环境、固定流程、Binding/后端/外部引用、计划与参数哈希、批准及映射历史、事件水位、最后权威快照、已执行动作和未决副作用、证据/产物授权引用、预算挂账、下一步核查清单、责任人。导出前逐项授权；包中不含凭据、原始私密模型推理或跨租户材料。

## 整体走查补充：责任人与依赖变化

提交人、计划审批人、结果验收人分别判定。没有此操作审批权的提交人只能请求负责人确认，不能用前台“允许”直接取得批准；负责人只对固定输入与计划决定，不因此获得用户全文或文件下载权。计划绑定 inputRevision、冻结配置、当前准入 / 授权修订、模型、MCP schema 与上下文 / 记忆来源。任一必要依赖变化，待确认或未执行批准须按新计划重新确认；不能把旧批准贴给新模型或新材料。具体交接见[衔接合同](END-TO-END-WALKTHROUGH.md)。
