# 协议观察与接入假设

核查日期：2026-10-25（Asia/Shanghai）。仅阅读公开官方资料，未启动协议客户端或访问业务资源。以下观察用于设计兼容边界；适配器实施时还须核查实际端点协商结果。

| 来源 | 当前观察 | 设计影响 |
| --- | --- | --- |
| [ACP 初始化](https://agentclientprotocol.com/protocol/v1/initialization) | 官方导航把 v1 标为 Latest，v2 标为 Draft；初始化交换协议和能力 | 固定协商版本，不把 v2 草案默认当稳定生产能力 |
| [ACP 会话](https://agentclientprotocol.com/protocol/v1/session-setup) | 新会话配置 cwd 与 MCP；恢复依赖双方支持 | 平台固定工作区与受控 MCP 引用；会话恢复不等于透明迁移 |
| [ACP 工具权限](https://agentclientprotocol.com/protocol/v1/tool-calls) | Agent 可发原生权限请求；请求不是每个 Agent 必然具备的治理保证 | 平台审批桥与资源层分别强制，不依赖 Agent 自觉请求 |
| [MCP HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) | 2026-07-28 移除旧 GET 流和协议会话，与 2025-11-25 存在兼容差异 | 保存协议版本和适配器支持范围，分别处理流和恢复语义 |
| [MCP 授权](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization) | HTTP 授权绑定目标资源服务；stdio 凭据方式不同 | 按传输配置认证，不把平台令牌或模型令牌跨服务透传 |
| [MCP roots](https://modelcontextprotocol.io/specification/2026-07-28/client/roots) | 此版本已弃用 roots；其本来也是范围提示而非访问控制 | 使用网关和 OS/资源限制；不把 roots 当沙箱 |
| [MCP tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools) | 工具描述/schema 与 annotation 需要可信策略判断；同名工具可在不同服务出现 | 工具以连接和 schema 摘要定位，参数、风险和输出均受控 |

本轮未重新核查总控仓库部署状态。先前在设计入口记录的提交号仅是当时源码证据，不能说明接口已上线。本文不要求升级任何现有服务。真实模型发现、认证、Agent 子进程隔离、资源限制与协议兼容均待实施阶段实测。
