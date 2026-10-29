# 正式单租户空库迁移：设计草案，禁止运行

状态：已生成**仅供评审的空库迁移候选**，没有接入运行入口，也没有在数据库执行或验收。不要用原 synthetic Flyway 路径创建正式库，也不要将现有测试库转换成正式库；不得将这些候选当作可部署迁移或首装验证。

## 已核对的基线

| 原迁移 | 对正式空库的影响 |
| --- | --- |
| V1 | 定义平台基础结构；末尾插入固定租户和 5 个演示主体及成员，正式库必须省去种子。`fixture_submissions` 是合成执行器结构，生产服务当前仍直接依赖。 |
| V2 | 增加标题来源、接纳期限和额度策略；回填会读取既有会话与成员，空库部署和迁移旧库语义不同。 |
| V3 | 为授权和正文租约增加审阅修订。 |
| V4 | 增加命令回执范围；创建并写入 `synthetic_boundary` 的固定测试标记，并对旧产物回填状态。正式迁移不能写该标记，也不能把该标记缺失当作通过。 |
| V5 | 固化额度账对并含有 `fixture-v1` 的旧数据回填；面向真实账目的回填不能套用。 |
| V6 | 增加上下文清单、理解记录和执行引用。 |
| V7 | 增加绑定快照及 fixture 日志、事件、产物、命令结构。正式代码仍存在直接查询，不能仅删表就保证可运行。 |
| V8 | 增加单次资源消费审计。 |
| V9 | 增加会话摘要，外键依赖成员。 |
| V10 | 增加独立账号及有界限流桶，外键依赖成员与租户，无默认账号。 |
| V11（仅候选） | 建立单行引导 guard 结构，不插入 guard 行或凭据；允许 `provisioned` 与 `consumed` 两种状态并约束令牌、租户字段的一致性。它不是已接入的初始化器，也不更新正式模式 marker。 |

上述 V8–V10 当前为工作区开发稿，未执行正式迁移。真实 MySQL 临时表检查不证明完整空库、外键、升级或恢复。原 V1–V10 文件及已存在 Flyway 历史不得修改或 repair。

## 必须先解决的设计边界

1. 服务端先沿用 `Store.TENANT` 固定 UUID（`00000000-0000-4000-8000-000000000001`）以兼容当前单租户代码；由将来的可信引导负责创建该租户并生成独立部署 UUID。候选中的正式 marker 只允许未初始化状态及空租户/部署 ID；V11 的 guard 消费不修改 marker 或开放服务，要启用必须另做向前迁移及权威校验。把标记改名不完成租户隔离。
2. 定义一次性首位管理员引导、凭据存储及失败/重试/恢复；正式空库必须不含默认真实账号，也不能默认创建合成主体。
3. 让 synthetic 与正式模式明确互斥：当前 `SyntheticBoundary`、定时 Worker、`FixtureProvider`、`PreparationService`、`HandoverService` 等仍对合成数据库或 fixture 表有直接依赖。不能通过放宽循环地址限制来启用正式模式。
4. 定义正式空库与正式升级两条严格分隔的迁移语义。只有在确认从未初始化的空库时才能跳过旧数据回填；不能把正式空库脚本用于已有库或覆盖旧历史。
5. 固定正式 schema、迁移历史和 marker 的校验、服务账号权限、两库及 Blob 一致性备份、恢复验证；采用独立正式迁移路径，默认不由现有运行入口加载。

候选位于本目录 `candidate/`，不在 Maven 运行时资源目录；由 `python docs/development/production-schema-draft/generate.py --write` 从当前源生成，省去 V1 演示种子、V2/V4/V5 旧数据回填，V4 改为仅允许未初始化状态的正式 marker；候选独有 V11 增加未预置数据的 guard 结构，不存在引导器或正式验签；保留 fixture 表仅为兼容结构，不能启用 fixture 服务。运行相同命令不带 `--write` 可核对全部字节和 SHA-256；源迁移边界变化时生成器会拒绝处理。

候选仅适用于从未初始化的全空库，不提供旧库升级、引导或真实服务启动；它尚未经过完整 SQL、外键和 MySQL 空库链测试。未来经评审后须采用独立迁移历史，在新建、独立、明确授权的隔离测试库上验证空库链、全部外键、重复启动、失败恢复、旧库拒绝和跨模式拒绝。

## 源码证据入口

- [当前合成迁移 V1](../../../backend/platform-server/src/main/resources/db/migration/V1__platform.sql) · [V2](../../../backend/platform-server/src/main/resources/db/migration/V2__title_source_and_quota_policy.sql) · [V4](../../../backend/platform-server/src/main/resources/db/migration/V4__receipt_scope_and_synthetic_marker.sql) · [V5](../../../backend/platform-server/src/main/resources/db/migration/V5__frozen_quota_accounts.sql) · [V7](../../../backend/platform-server/src/main/resources/db/migration/V7__typed_provider_journal.sql)
- [合成边界](../../../backend/platform-server/src/main/java/io/aitest/workbench/identity/SyntheticBoundary.java) · [准备服务](../../../backend/platform-server/src/main/java/io/aitest/workbench/execution/PreparationService.java) · [接管服务](../../../backend/platform-server/src/main/java/io/aitest/workbench/handover/HandoverService.java)
