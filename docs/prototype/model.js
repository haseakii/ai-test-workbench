/* Offline review simulator. All facts, IDs, amounts and capabilities are synthetic. */
(function (root) {
  'use strict';
  const clone = value => JSON.parse(JSON.stringify(value));
  const connections = {
    control: { label: '总控适配器', agent: '示例 Agent A', model: '合成分析模型 A', bridge: false },
    direct: { label: '受控 ACP 直连', agent: '示例 Agent B', model: '合成分析模型 B', bridge: true }
  };
  const scenarios = {
    normal: '只读问题闭环', timeout: '提交无响应', approval: '审批与安全恢复',
    mismatch: '能力不匹配', unknownCapability: '能力未知', bridge: '审批桥接不可用',
    usage: '用量未上报', evidence: '证据版本不一致', artifact: '产物完整性失败'
  };
  function binding(provider, round) {
    return { id: 'demo-binding-' + round, nodeRunId: 'demo-node-' + round, provider,
      connectionRevision: 3, ownershipEpoch: round, specHash: 'a'.repeat(64), state: 'prepared',
      externalRef: null, executionActive: false, effectStatus: 'none', outcome: null,
      ownerOccupied: false, admissionFence: null, checkpoint: true };
  }
  function newApproval(revision = 1, round = 1) {
    return { id: 'demo-approval-' + round + '-' + revision, revision, decision: 'pending', delivery: 'ready',
      action: 'not_started', requestId: 'demo-permission-' + round + '-' + revision, mappingRevision: revision,
      requestLive: true, valid: true, planHash: 'b'.repeat(64), planRevalidated: true,
      preconditionsMatch: true, operationId: 'demo-export-' + round, consumed: false, comment: '' };
  }
  function createState(scenario = 'normal') {
    const s = { scenario, revision: 1, round: 1, issue: 'awaiting_user', run: 'draft', node: 'blocked',
      defaultProvider: 'control', binding: binding('control', 1), approval: null, approvalHistory: [],
      conditions: '', resultRevision: 0, results: [], verification: null, health: 'online',
      capability: 'simulation_only', usage: 'unreported_actual', usageBlocked: false,
      evidenceConsistent: true, artifact: 'metadata_only', modelCalls: 0, startCommands: 0,
      permissionSends: 0, operationsExecuted: 0, stopRequested: false, currentPlanHash: 'b'.repeat(64), eventIds: [], eventWatermark: 0, events: [],
      message: '请选择演示路径。实际连接、模型与资源能力均未验收。' };
    if (['approval', 'bridge'].includes(scenario)) {
      s.defaultProvider = scenario === 'approval' ? 'direct' : 'control';
      s.binding = binding(s.defaultProvider, 1);
      Object.assign(s, { issue: 'open', run: 'waiting', node: 'waiting_approval', startCommands: 1 });
      Object.assign(s.binding, { state: 'waiting_permission', externalRef: 'demo-run-1', executionActive: true, ownerOccupied: true });
      s.approval = newApproval();
      s.conditions = '合成报表：10 月 1–24 日，UTC+8，已完成订单，EUR';
    }
    if (scenario === 'mismatch') s.capability = 'unsupported';
    if (scenario === 'unknownCapability') s.capability = 'unknown';
    if (scenario === 'usage') s.usageBlocked = true;
    if (scenario === 'evidence') s.evidenceConsistent = false;
    if (scenario === 'artifact') s.artifact = 'failed';
    if (scenario === 'timeout') {
      s.conditions = '合成报表：UTC+8，10 月 1–24 日，已完成订单';
      Object.assign(s, { issue: 'open', run: 'reconciling', node: 'reconciling', startCommands: 1 });
      Object.assign(s.binding, { state: 'acceptance_unknown', executionActive: 'unknown', ownerOccupied: true });
    }
    return s;
  }
  function safeClosed(s) {
    const b = s.binding;
    return (b.state === 'terminal' || b.state === 'rejected') && b.executionActive === false &&
      b.effectStatus !== 'unknown' && !b.ownerOccupied;
  }
  function require_(condition, message) { if (!condition) throw new Error(message); }
  function record(s, message) {
    s.eventWatermark += 1;
    s.events.push({ sequence: s.eventWatermark, source: '本地合成模拟器', message });
    s.message = message;
  }
  function permissionGuard(s) {
    const a = s.approval;
    require_(a && a.decision === 'approved', '先由合格审批人对当前计划作出批准。');
    require_(a.requestLive && a.valid, '请求或批准已失效，禁止发送旧权限选项。');
    require_(a.planHash === s.currentPlanHash && a.planRevalidated && a.preconditionsMatch, '计划或前置条件变化，必须重新取证和审批。');
    require_(connections[s.binding.provider].bridge, '此连接没有可用审批桥接；转原生工作台或人工接管。');
    require_(!s.usageBlocked, '用量未上报，禁止新增计费或敏感分派；可继续不计费核查。');
    require_(!a.consumed, '该操作已经消费批准，不可再次执行。');
  }
  function active(s) {
    s.binding.state = s.stopRequested ? 'stopping' : 'active';
    s.binding.executionActive = true; s.binding.ownerOccupied = true;
    s.run = s.stopRequested ? 'stopping' : 'active'; s.node = s.run;
  }
  function act(original, action, payload = {}) {
    const s = clone(original);
    try {
      switch (action) {
        case 'conditions':
          require_(s.run === 'draft', '本轮条件已固定，补充应进入消息采纳流程。');
          require_((payload.text || '').trim().length > 0, '请补充报表范围、时区和订单状态。');
          s.conditions = payload.text.trim(); s.issue = 'open'; s.run = 'ready'; s.node = 'ready';
          record(s, '条件已保存；等待模拟提交，未调用执行后端。'); break;
        case 'default_provider':
          require_(connections[payload.provider], '请选择已登记的连接。');
          s.defaultProvider = payload.provider;
          record(s, '下次默认连接已变更；当前 Binding 和拥有者保持固定。'); break;
        case 'start': case 'start_timeout':
          require_(s.node === 'ready' && s.binding.state === 'prepared', '已有提交或执行占用，禁止重复 start。');
          require_(s.capability === 'simulation_only', '能力未知或不匹配，启动门禁拒绝。');
          require_(!s.usageBlocked, '用量未上报，禁止新的计费调用。');
          s.startCommands += 1; s.binding.ownerOccupied = true;
          if (action === 'start_timeout') {
            s.binding.state = 'acceptance_unknown'; s.binding.executionActive = 'unknown';
            s.run = 'reconciling'; s.node = 'reconciling';
            record(s, 'start 响应丢失：保留原键和预算，先核查接纳，不能换后端重做。');
          } else {
            s.binding.externalRef = 'demo-run-' + s.round; active(s);
            record(s, '模拟收到 accepted 回执并确认原执行活跃；没有启动真实 Agent。');
          } break;
        case 'lookup_not_found':
          require_(s.binding.state === 'acceptance_unknown', '当前没有待核查的未知提交。');
          record(s, 'not_found 不能排除延迟接纳；保持 acceptance_unknown 与原拥有权。'); break;
        case 'lookup_found':
          require_(s.binding.state === 'acceptance_unknown', '只允许核查原未知提交。');
          s.binding.externalRef = 'demo-run-' + s.round; active(s);
          record(s, '找到原接收回执，恢复同一个 Binding；没有发送第二个 start。'); break;
        case 'close_final':
          require_(s.binding.state === 'acceptance_unknown', '仅可封闭原未知提交。');
          Object.assign(s.binding, { state: 'rejected', executionActive: false, effectStatus: 'none', ownerOccupied: false, admissionFence: 'demo-final-fence' });
          s.run = 'failed'; s.node = 'failed';
          record(s, '模拟接纳端返回 not_accepted_final 与 admissionFence；旧键永久封闭。'); break;
        case 'disconnect':
          require_(['active', 'waiting_permission', 'stopping'].includes(s.binding.state), '当前没有可断线的在途执行。');
          s.health = 'offline'; s.binding.state = 'reconciling'; s.binding.executionActive = 'unknown';
          s.run = 'reconciling'; s.node = 'reconciling';
          if (s.approval) { s.approval.requestLive = false; s.approval.delivery = 'stale'; }
          record(s, '连接离线：执行事实未知，仍保留原拥有者与预算挂账。'); break;
        case 'reconcile_active':
          require_(s.binding.state === 'reconciling' && s.binding.externalRef, '先按原提交核查外部引用。');
          require_(!s.approval || s.approval.delivery !== 'delivery_unknown', '先核对原权限决定是否送达。');
          s.health = 'online'; active(s);
          if (s.approval && !s.approval.requestLive) { s.binding.state = 'waiting_permission'; s.run = 'waiting'; s.node = 'waiting_approval'; }
          record(s, '模拟权威快照确认原拥有者仍在；失效权限仍需单独恢复。'); break;
        case 'cancel':
          require_(s.binding.externalRef && ['active', 'waiting_permission', 'reconciling'].includes(s.binding.state), '先核查是否已接收；没有引用时不能假装取消成功。');
          s.stopRequested = true; s.binding.state = 'stopping'; s.run = 'stopping'; s.node = 'stopping';
          record(s, '已登记停止请求；尚未确认停止，也不承诺回滚。'); break;
        case 'confirm_cancel':
          require_(s.binding.state === 'stopping', '先登记停止请求。');
          require_(!s.approval || s.approval.delivery !== 'delivery_unknown', '权限送达未知，不能证明最终副作用；先核查。');
          Object.assign(s.binding, { state: 'terminal', executionActive: false, effectStatus: s.approval?.consumed ? 'verified' : 'none', outcome: 'cancelled', ownerOccupied: false });
          s.run = 'cancelled'; s.node = 'cancelled';
          record(s, '模拟停止与副作用回执已确认；已发生的动作仍保留。'); break;
        case 'complete':
          require_(['active', 'stopping'].includes(s.binding.state) && ['active', 'stopping'].includes(s.node), '仅可确认原执行的权威完成事实。');
          require_(!s.approval || s.approval.action === 'verified', '受限动作尚无核验回执，不可跳到完成。');
          require_(s.evidenceConsistent && s.artifact !== 'failed', '证据版本/时点或文件完整性不满足，不能交付确定性结果。');
          Object.assign(s.binding, { state: 'terminal', executionActive: false, effectStatus: s.approval?.consumed ? 'verified' : 'none', outcome: 'succeeded', ownerOccupied: false });
          s.run = 'succeeded'; s.node = 'succeeded'; s.issue = 'awaiting_verification'; s.artifact = 'verified';
          s.resultRevision += 1;
          s.results.push({ revision: s.resultRevision, bindingId: s.binding.id, conclusion: '合成案例：状态口径不同，示例差额 2,530 EUR', verifiedBy: null });
          record(s, '模拟结果已交付，问题待验证；Agent 完成不会自动解决问题。'); break;
        case 'verify':
          require_(s.issue === 'awaiting_verification' && s.artifact === 'verified' && s.evidenceConsistent, '当前结果不满足人工验证条件。');
          s.issue = 'resolved'; s.verification = { actor: '合成提交人', resultRevision: s.resultRevision };
          s.results.at(-1).verifiedBy = '合成提交人';
          record(s, '合成提交人确认当前结果版本已解决。'); break;
        case 'new_round':
          require_(safeClosed(s), '旧执行未安全关闭；普通 not_found、离线或停止请求均不足。');
          if (s.approval) s.approvalHistory.push(clone(s.approval));
          s.round += 1; s.binding = binding(s.defaultProvider, s.round); s.issue = 'open';
          s.run = 'ready'; s.node = 'ready'; s.approval = null; s.verification = null; s.artifact = 'metadata_only'; s.stopRequested = false; s.currentPlanHash = 'b'.repeat(64);
          record(s, '已保留旧结果/审批，并创建新轮次、新 NodeRun 与新 Binding。'); break;
        case 'request_approval':
          require_(s.binding.state === 'active' && !s.approval, '需要原执行的具体权限请求。');
          s.approval = newApproval(1, s.round); s.binding.state = 'waiting_permission'; s.run = 'waiting'; s.node = 'waiting_approval';
          record(s, '模拟受限文件导出请求；暂停动作，等待人工审批。'); break;
        case 'approve':
          require_(s.approval?.decision === 'pending', '当前审批已处理，不可重复决定。');
          require_(payload.checked === true, '请确认已审阅当前固定计划和影响范围。');
          require_(s.approval.requestLive && s.approval.valid && s.approval.preconditionsMatch, '请求/批准失效或计划漂移，需重新审批。');
          s.approval.decision = 'approved'; s.approval.revision += 1;
          record(s, '业务批准已保存，尚未发送权限响应，动作未执行。'); break;
        case 'deny':
          require_(s.approval?.decision === 'pending', '只能拒绝待处理审批。');
          require_((payload.comment || '').trim(), '拒绝必须填写原因。');
          s.approval.decision = 'denied'; s.approval.comment = payload.comment.trim();
          record(s, '业务审批已拒绝；敏感动作未执行，等待调整或接管。'); break;
        case 'expire_native': case 'expire_business': case 'drift':
          require_(s.approval && ['pending', 'approved'].includes(s.approval.decision) && !s.approval.consumed, '没有可失效的未消费审批。');
          require_(s.approval.delivery === 'ready', '响应已经发送，先核查是否发生动作，不能假定未执行并重新批准。');
          s.approval.decision = action === 'expire_business' ? 'expired' : 'superseded';
          s.approval.delivery = 'stale'; s.approval.requestLive = false;
          if (action === 'expire_business') s.approval.valid = false;
          if (action === 'drift') { s.currentPlanHash = 'c'.repeat(64); s.approval.preconditionsMatch = false; s.approval.planRevalidated = false; }
          record(s, '旧批准/请求失效，禁止重放旧 option；新请求必须重新人工批准。'); break;
        case 'refresh_plan':
          require_(s.approval?.delivery === 'stale', '仅对失效计划重新取证。');
          s.approval.planRevalidated = true; s.approval.preconditionsMatch = true;
          record(s, '合成材料重新核查完成；这还不是新的批准。'); break;
        case 'prepare_new_permission': {
          const a = s.approval;
          require_(a && a.delivery === 'stale', '先确认旧请求失效。');
          require_(connections[s.binding.provider].bridge && s.binding.checkpoint && s.binding.effectStatus === 'none' && s.binding.executionActive !== 'unknown', '缺少安全检查点/未执行证明/桥接能力，转人工接管。');
          require_(a.planRevalidated && a.preconditionsMatch && !a.consumed, '先重新取证并排除已执行动作。');
          if (['pending', 'approved'].includes(a.decision)) a.decision = 'superseded';
          s.approvalHistory.push(clone(a)); s.approval = newApproval(a.mappingRevision + 1, s.round);
          s.approval.planHash = s.currentPlanHash;
          s.approval.operationId = a.planHash === s.currentPlanHash ? a.operationId : 'demo-export-' + s.round + '-plan-' + s.approval.mappingRevision;
          s.binding.state = 'waiting_permission'; s.run = 'waiting'; s.node = 'waiting_approval';
          record(s, '原 Binding 取得新的合成请求与新业务审批；未执行工具，确认框重新清空。'); break;
        }
        case 'send_permission': case 'send_timeout':
          permissionGuard(s);
          require_(s.approval.delivery === 'ready', '已发送或送达未知，禁止重复响应。');
          require_(s.binding.state === 'waiting_permission', '执行已停止或状态未知，不能发送批准。');
          s.permissionSends += 1;
          if (action === 'send_timeout') {
            s.approval.delivery = 'delivery_unknown'; s.approval.action = 'result_unknown';
            s.binding.state = 'reconciling'; s.binding.effectStatus = 'unknown'; s.run = 'reconciling'; s.node = 'reconciling';
            record(s, '权限响应送达未知；保留决定键，先核查，不重新批准或重发。');
          } else { s.approval.delivery = 'delivered'; active(s); record(s, '模拟响应已送达；动作结果仍未确认。'); }
          break;
        case 'lookup_delivery':
          require_(s.approval?.delivery === 'delivery_unknown', '当前没有未知送达。');
          s.approval.delivery = 'delivered'; s.approval.action = 'not_started'; s.binding.effectStatus = 'none'; active(s);
          record(s, '模拟核对原决定回执：已送达、尚未执行；没有额外发送。'); break;
        case 'inner_deny':
          require_(s.approval?.decision === 'approved' && !s.approval.consumed, '先有业务批准且尚未执行。');
          require_(s.approval.delivery !== 'delivery_unknown', '先核查原响应，不可把未知伪装拒绝。');
          s.approval.delivery = 'denied_by_provider'; s.binding.state = 'waiting_permission'; s.run = 'waiting'; s.node = 'waiting_approval';
          record(s, '内层策略拒绝，外层批准不能绕过；动作未执行。'); break;
        case 'operation_receipt':
          permissionGuard(s);
          require_(s.approval.delivery === 'delivered' && s.binding.state === 'active', '没有有效送达或执行已停止。');
          s.approval.action = 'verified'; s.approval.consumed = true; s.binding.effectStatus = 'verified'; s.operationsExecuted += 1;
          record(s, '收到合成动作核验回执，批准仅消费一次。'); break;
        case 'usage_unknown': s.usageBlocked = true; record(s, '真实用量未上报；新增计费动作阻断，核查与停止仍可用。'); break;
        case 'billable_step':
          require_(s.binding.state === 'active' && !s.usageBlocked, '当前状态或未知用量禁止新增计费动作。');
          require_(s.modelCalls < 2, '模拟调用次数上限为 2；这不证明真实金额硬限制。');
          s.modelCalls += 1; record(s, '仅模拟一次新调用计数，没有模型调用或实际费用。'); break;
        case 'evidence_mismatch': s.evidenceConsistent = false; record(s, '代码部署版本与取证版本不一致，结论限为待核查。'); break;
        case 'artifact_corrupt': s.artifact = 'failed'; record(s, '合成产物哈希校验失败，禁止下载/确认解决，不自动重新生成。'); break;
        case 'duplicate_event':
          if (!s.eventIds.includes('demo-event-1')) { s.eventIds.push('demo-event-1'); record(s, '首次接收合成事件 demo-event-1；不含计费。'); }
          else s.message = '重复事件已忽略，没有重复推进、通知或计费。';
          break;
        case 'handoff':
          require_(!['resolved', 'archived_unconfirmed'].includes(s.issue), '已解决问题请先明确重开。');
          s.issue = 'handoff'; s.run = 'handoff'; s.node = 'handoff';
          record(s, '已准备合成人工接管材料；原 Binding 与未决拥有权仍保留。'); break;
        default: throw new Error('未知模拟动作。');
      }
      s.revision += 1;
    } catch (e) { s.message = '操作被阻止：' + e.message; }
    return s;
  }
  const api = { createState, act, safeClosed, connections, scenarios };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ReviewModel = api;
})(typeof globalThis === 'undefined' ? this : globalThis);
