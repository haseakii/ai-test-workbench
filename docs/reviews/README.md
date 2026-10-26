# 评审与归档索引

本目录保留决策依据和验证证据。**当前设计以架构决策、产品规格和开发设计为准；历史复审与逐次变更记录只用于追溯，不覆盖当前决定。**

## 当前有效决策

- [ADR-001：独立业务平台与可插拔执行后端](../architecture/ADR-001.md)
- [ADR-002：技术栈与平台数据库](../architecture/ADR-002.md)
- [数据库决定](DATABASE-DECISION-20261026.md)
- [开发设计入口](../development/README.md)

## 评审证据

| 文档 | 用途 |
| --- | --- |
| [整体方案复核](OVERALL-SOLUTION-REVIEW-20261026.md) | 技术实施前的整体风险复核；其中旧技术选项应以当前 ADR 为准 |
| [初稿统一评审](INITIAL-DRAFT-REVIEW.md) | 产品范围、身份、首批链路与实施门槛的评审清单 |
| [可用性优化记录](USABILITY-REFINEMENT-20261025.md) | 原型交互调整及其对应的离线验证范围 |

这些记录中的测试数字只适用于相应提交和测试范围。它们不代表真实数据库、Agent、MCP、资源隔离、额度结算或生产部署已验收。

## 历史过程记录

以下文件保留原意以便追溯，但不作为日常阅读入口：

- [逐次决策记录](DECISIONS-20261025.md)
- [初始设计变更日志](INITIAL-DESIGN-CHANGELOG-20261025.md)
- [端到端走查](END-TO-END-REVIEW-20261025.md)
- [执行资源走查](EXECUTION-RESOURCE-REVIEW-20261025.md)
- [日常可用性走查](USABILITY-REVIEW-20261025.md)

## 不可变原稿

- [原始设计基线](../review-baseline-20261025/)
- [生成阅读版](../generated/v0.2/README.md)
- [原件与阅读版说明](../ARCHIVE-STATUS.md)

历史文件不为“文档整洁”而删除或改写；如需改变当前规则，应更新对应的正式规格或 ADR，并在当前决策中说明。
