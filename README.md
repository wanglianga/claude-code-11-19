# 书店会员历史迁移服务

在旧书店系统**继续营业记账**的前提下，搬迁会员、购书与积分记录，并让运营人员根据**明确的核对结果**决定何时切换读取与写入。

技术栈：Java 21（运行于 JDK 25）/ Spring Boot 3.5 / PostgreSQL 18 / springdoc OpenAPI。

---

## 1. 要解决的核心问题与对应设计

| 业务风险 | 本服务的处理 |
| --- | --- |
| 旧会员卡回收重发，交易卡号不能直接套用今天的持卡人 | 按**交易时刻匹配持卡有效区间**；同卡号多段区间分别归属，回收前后交易不会串到另一人名下 |
| 一次性按当前资料回填会把早年购书串人 | 快照原件 + 区间解析，重叠/缺失不自动猜，进入人工判定 |
| 边营业边复制的快照不一致 | 快照在**同一逻辑截点**导出，会员/卡片/余额/交易共用 `snapshot_time` 与截止序号 `cutoff_seq=100`，不能分别取不同营业时刻 |
| 变更重复应用导致积分多算 | 递增序号 + 内容哈希；重复事件记 `DUPLICATE` 不再改变余额；同序号不同内容记 `CONFLICT` 并暂停 |
| 序号断档 | 断档即暂停该来源（`GAP_WAIT`），补齐后续接，绝不跳过序号 |
| 撤销先于原交易到达 | 记 `REVERSAL_WAIT` 等待原交易，原交易到达后联动生效 |
| 停业门店只留下积分期初数 | 缺期初只标记**阻断**，禁止造调整填平；可凭补充证据补录真实期初并全程留痕 |
| 同手机号家庭成员被错误合并 | 数据层始终是独立会员，系统不做任何自动合并 |
| 运营凭余额接近自动配对 | 人工判定**必须附补充证据**且只能选候选持有人（区间缺失可凭证据指定已存在会员） |
| 修正归属影响历史余额 | 仅重算原归属与新归属两名会员；原判定标记 `superseded` 仍可查 |
| 两侧同时写 | 切换状态机：旧端确认最终序号后拒绝一切新营业写入；未开放新端前可取消并重新开放旧端，开放后不得自动退回 |

### 三层数据分离（同一 PostgreSQL 库内分表族，互不覆盖）

- `legacy_*`：模拟旧端**原件**（迁移方只读，旧端继续营业写自己的表）
- `mig_*`：**迁移原件与进度**（快照清单/分段、复制原件、变更登记、序号进度、搬运游标、证据留痕）
- `target_*`：**新端可读数据**（会员、卡片、交易归属、积分台账、核对结果、切换状态）

---

## 2. 本地启动

### 方式 A：Docker Compose 一键启动（推荐，自带 PostgreSQL）

```bash
docker compose up --build
# 应用：http://localhost:8080
# Swagger UI：http://localhost:8080/swagger-ui.html
# OpenAPI JSON：http://localhost:8080/v3/api-docs
```

### 方式 B：本地已有 PostgreSQL（本隔离容器实测方式）

前置：PostgreSQL 18 运行在 `127.0.0.1:5432`，库/用户/密码均为 `ccdev`。
表结构由应用启动时自动执行 `src/main/resources/schema.sql` 创建（全部 `IF NOT EXISTS`，可重复启动），样例数据幂等播种。

```bash
mvn clean package
java -jar target/member-migration-1.0.0.jar
# 可用环境变量覆盖：DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD
```

### 测试账号

本系统为后端迁移服务，无终端用户登录体系。运营操作通过接口参数 `decided_by` / `requested_by` / `recorded_by` / `actor` 记录操作人，示例统一使用：

- 运营人员：`op1`、`op2`、`alice`、`bob`、`carol`
- 数据库账号（仅开发/测试，非生产配置）：`ccdev / ccdev`

### 样例数据（启动自动播种，可随时 `POST /api/legacy/system/reset` 重置）

- 7 名会员 M001–M007、3 家门店 S01–S03、卡号 C9001–C9005
- **卡号换人**：C9001 先属 M001（2025-01-01~06-30），回收后发给 M002（07-01 起）
- **区间重叠**：C9002 在 2025-05~08 同时挂 M003/M004，交易 T1004 待人工判定
- **区间缺失**：C9003 交易 T1005 发生时无有效持卡区间
- **缺期初**：停业门店 S02 的 M007 无积分期初行
- **同手机号家庭成员**：M004/M005 同为 13800000004，保持两个独立会员
- **变更事件**：序号 101–110，其中 104 故意断档、106 撤销先于 107 原交易

---

## 3. 验证方式

### 一键端到端脚本（需先启动应用，53 项断言）

```bash
bash scripts/e2e-verify.sh
# 期望末尾输出：RESULT: PASS=53 FAIL=0
```

覆盖：卡号换人、区间重叠/缺失、缺期初、断档后补齐、撤销先到、重复事件、同序号冲突、人工证据判定、余额重建、核对下钻、切换中断恢复、取消切换、开放后同侧查询。

### Maven 集成测试

```bash
mvn test
# MigrationFlowIntegrationTest（完整主链路）+ HashesTest（规范哈希稳定性）
```

### 手工主链路（也可直接在 Swagger UI 操作）

```bash
# 0. 重置到初始样例
curl -X POST localhost:8080/api/legacy/system/reset
# 1. 快照：清单 -> 分段接收 -> 分段验证 -> 完成 -> 投影
curl -X POST localhost:8080/api/snapshot/manifest
for s in MEMBER CARD PURCHASE OPENING; do
  curl -X POST "localhost:8080/api/snapshot/sections/$s/receive?limit=3"   # 重复调用直到 has_more=false
  curl -X POST "localhost:8080/api/snapshot/sections/$s/verify"
done
curl -X POST localhost:8080/api/snapshot/complete
curl -X POST localhost:8080/api/snapshot/project-baseline
# 2. 变更流（遇 104 断档暂停 -> 补齐 -> 续接）
curl -X POST 'localhost:8080/api/changes/pull?limit=50'
curl -X POST localhost:8080/api/legacy/gap/fill-104
curl -X POST localhost:8080/api/changes/resume
curl -X POST 'localhost:8080/api/changes/pull?limit=50'
# 3. 人工判定（证据必填）
curl -X POST localhost:8080/api/ownership/decisions -H 'Content-Type: application/json' \
  -d '{"trans_no":"T1004","owner_code":"M003","evidence":"门店纸质签购单核对签名确认","decided_by":"op1"}'
curl -X POST localhost:8080/api/ownership/decisions -H 'Content-Type: application/json' \
  -d '{"trans_no":"T1005","owner_code":"M005","evidence":"停业门店留存的纸质签购单凭证","decided_by":"op1"}'
# 4. 缺期初凭证据补录
curl -X POST localhost:8080/api/ownership/openings/evidence -H 'Content-Type: application/json' \
  -d '{"member_code":"M007","opening_points":60,"evidence":"停业门店交接清单登记期初60分","recorded_by":"op1"}'
# 5. 核对（overall_passed=true、diff_count=0）
curl -X POST localhost:8080/api/reconcile/check
# 6. 切换四阶段
curl -X POST localhost:8080/api/switch/requests -H 'Content-Type: application/json' \
  -d '{"requested_by":"alice","requested_cutoff":110}'
curl -X POST localhost:8080/api/switch/requests/<id>/confirm-final-seq
curl -X POST localhost:8080/api/switch/requests/<id>/apply-target
curl -X POST localhost:8080/api/switch/requests/<id>/open
```

**已实际执行的验证结论（2026-11-14，本容器内）**

- `mvn test`：3 个测试方法全部通过；
- `scripts/e2e-verify.sh`：`PASS=53 FAIL=0`；
- 全新空库冷启动：27 张表自动创建、样例自动播种；
- Swagger UI 200，`/v3/api-docs` 200，42 个接口路径。

---

## 4. 关键流程说明

### 4.1 快照基线（清单未齐不得宣布完成）

1. `POST /api/snapshot/manifest` 取得清单：4 个分段、每段行数、业务摘要、段哈希、总校验和、截止序号 100；
2. `POST /api/snapshot/sections/{name}/receive` 按 id 游标分页接收，进度写 `mig_chunk_cursor`，进程中断后从最后位置续接；
3. `POST /api/snapshot/sections/{name}/verify` 从旧端独立全量重拉并按规范 JSON 重算 SHA-256 比对；
4. 任一段未 `VERIFIED` 时 `POST /api/snapshot/complete` 返回 412；
5. 完成后建立来源进度 `contiguous_seq=100`，变更流只允许序号 >100 的事件。

### 4.2 变更序号规则

| 情形 | 登记状态 | 行为 |
| --- | --- | --- |
| 序号 ≤ 截止序号 | `IGNORED` | 不应用 |
| 严格下一号且内容未见过 | `APPLIED` | 投影并推进 `contiguous_seq` |
| 同序号同哈希重发 | `DUPLICATE` | 留痕，余额不变 |
| 同序号不同哈希 | `CONFLICT` | 暂停来源；`POST /api/changes/conflicts/{seq}/resolve?action=KEEP_ORIGINAL` 以原件为准后续接 |
| 跳号 | `GAP_WAIT` + 暂停 | 旧端补齐（`/api/legacy/gap/fill-104`）后 `/api/changes/resume` |
| 撤销先到 | `REVERSAL_WAIT` | 原交易到达自动联动撤销，交易 `valid_flag=false` |

### 4.3 余额重建（期初 + 逐笔有效变动）

- 台账 `target_point_ledger` 逐笔记录 `OPENING/PURCHASE` 与 `balance_after`，任何会员的余额形成过程可完整回放；
- 仅统计 `ownership_kind IN (AUTO, MANUAL)` 且未撤销的交易；阻断中交易不计入；
- 缺期初或存在待判定交易时 `target_point_balance.blocked=true` 并写明原因；
- 归属修正只重算涉及会员，旧任务不会替换更晚确认的数据。

### 4.4 核对下钻

- `POST /api/reconcile/check` 输出总数（人数/交易数/积分差额/序号积压）与门店分组；
- 差异必须对应具体原行（`ref_kind=ROW`）或事件（`ref_kind=EVENT`），类型：
  `SOURCE_MISSING`（源有新无）、`TARGET_EXTRA`（新有源无）、`OWNERSHIP_MISMATCH`（归属未决）、
  `BALANCE_GAP`（余额差）、`MISSING_OPENING`（缺期初）、`SEQ_LAG/GAP_PAUSED/CONFLICT`；
- `GET /api/reconcile/stores/{storeCode}` 下钻门店，`GET /api/reconcile/members/{memberCode}` 下钻会员；
- 每次核对按 `run_id` 留档，可查历史差异。

### 4.5 受控切换状态机

```
FREEZE_REQUESTED → FINAL_SEQ_CONFIRMED → TARGET_APPLIED → OPENED
        │                  │                   │
        └──────── 未开放均可 CANCELLED（联动重新开放旧端）────────┘
OPENED 之后取消接口返回 409，不得自动退回
```

四项共同条件（`GET /api/switch/conditions` 实时展示，含预计积压事件数）：

1. 旧端已停写 `condition_legacy_frozen`
2. 最终序号追平且来源未暂停 `condition_seq_caught_up`
3. 归属冲突/待判定清零 `condition_ownership_conflicts_cleared`
4. 最近一次核对通过 `condition_reconcile_passed`

- 两人申请不同截点：同时只允许一份活动申请，第二份返回 409；
- 中途超时：`GET /api/switch/requests/{id}` 查实际阶段、条件面板、允许动作；失败态带 `recovery_step` 明确恢复步骤；
- 停写后旧端营业写入返回 409，任何时刻不存在两侧同时写；
- 开放后 `GET /api/target/*` 才可用，数据固定来自新端同一侧完整范围；未开放时这些查询返回 412 并指引到迁移侧报告 `/api/reports/members/{code}`。

---

## 5. 接口分组（共 42 个路径，详见 Swagger UI）

| 前缀 | 职责 |
| --- | --- |
| `/api/legacy` | 模拟旧端：营业写入、断档补齐、冲突报文、停写/解冻、全量重置 |
| `/api/snapshot` | 快照清单、分段接收/验证、完成、基线投影 |
| `/api/changes` | 变更拉取/手工投递、续接、冲突处置、状态 |
| `/api/ownership` | 待判定清单、候选持有人、人工证据判定、判定历史、期初证据补录 |
| `/api/reconcile` | 核对执行、历史 run、门店/会员下钻 |
| `/api/switch` | 切换申请、四阶段推进、取消、条件面板 |
| `/api/reports` | 单会员迁移报告（旧记录/采用归属/台账/阻断原因/判定历史） |
| `/api/target` | 开放守卫下的新端业务查询 |

## 6. 工程结构

```
src/main/java/com/bookstore/migration
├── legacy        模拟旧端：样例播种、同截点快照、序号变更、营业写入
├── snapshot      清单/分段校验接收、续接游标
├── changestream  断档/重复/冲突/撤销先到规则
├── ownership     持卡区间解析与人工证据判定
├── projection    新端投影、余额重建、期初证据补录
├── reconcile     总数→门店→会员核对与差异留档
├── switchflow    切换四阶段状态机与失败留痕
├── report        单会员迁移报告
├── query         新端开放守卫查询
├── web           REST 控制器
└── support       异常处理、规范哈希
```
