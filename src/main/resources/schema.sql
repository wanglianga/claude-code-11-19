-- =====================================================================
-- 书店会员历史迁移服务 数据库结构
-- 三层分离：legacy_* 模拟旧端原件 | mig_* 迁移原件/进度 | target_* 新端可读数据
-- 全部使用 IF NOT EXISTS，支持进程中断后重启续接
-- =====================================================================

-- 迁移侧统一代理主键序列（旧端原件使用自身固定 id）
CREATE SEQUENCE IF NOT EXISTS app_global_seq START 10000;

-- ---------------------------------------------------------------------
-- 一、模拟旧端（legacy）：旧系统继续营业记账，迁移方只读
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS legacy_member (
    id              BIGINT PRIMARY KEY,
    member_code     VARCHAR(32)  NOT NULL UNIQUE,
    name            VARCHAR(64)  NOT NULL,
    phone           VARCHAR(32),
    store_code      VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_seq     BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS legacy_card (
    id              BIGINT PRIMARY KEY,
    card_no         VARCHAR(32)  NOT NULL,
    holder_id       BIGINT       NOT NULL,            -- 当前/当时持卡人
    valid_from      TIMESTAMP    NOT NULL,
    valid_to        TIMESTAMP,                        -- NULL 表示至今有效
    store_code      VARCHAR(32)  NOT NULL,
    updated_seq     BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (card_no, holder_id, valid_from)
);

CREATE TABLE IF NOT EXISTS legacy_purchase (
    id              BIGINT PRIMARY KEY,
    trans_no        VARCHAR(40)  NOT NULL UNIQUE,
    card_no         VARCHAR(32)  NOT NULL,            -- 交易上的卡号（可能已换人）
    store_code      VARCHAR(32)  NOT NULL,
    trans_time      TIMESTAMP    NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    points_delta    INTEGER      NOT NULL,
    updated_seq     BIGINT       NOT NULL DEFAULT 0   -- 0=快照基线内交易，>0=由该序号变更引入
);

-- 停业门店可能只留下积分期初数；缺期初即不插行（禁止迁移方造调整填平）
CREATE TABLE IF NOT EXISTS legacy_point_opening (
    id              BIGINT PRIMARY KEY,
    member_code     VARCHAR(32)  NOT NULL UNIQUE,
    store_code      VARCHAR(32)  NOT NULL,
    opening_points  INTEGER      NOT NULL,
    updated_seq     BIGINT       NOT NULL DEFAULT 0
);

-- 有递增序号的变更事件（同序号内容哈希不同即冲突）
CREATE TABLE IF NOT EXISTS legacy_change_event (
    seq             BIGINT PRIMARY KEY,
    source_id       VARCHAR(32)  NOT NULL,
    entity_type     VARCHAR(16)  NOT NULL,            -- MEMBER/CARD/PURCHASE/OPENING
    entity_key      VARCHAR(64)  NOT NULL,
    op              VARCHAR(16)  NOT NULL,            -- UPSERT/DELETE
    payload         TEXT         NOT NULL,
    content_hash    VARCHAR(64)  NOT NULL,
    business_time   TIMESTAMP    NOT NULL,
    linked_trans_no VARCHAR(40)                       -- 撤销事件关联的原交易号
);

CREATE TABLE IF NOT EXISTS legacy_source_state (
    source_id       VARCHAR(32) PRIMARY KEY,
    last_seq        BIGINT NOT NULL DEFAULT 0,        -- 旧端已产生的最大序号
    frozen          BOOLEAN NOT NULL DEFAULT FALSE,  -- 旧端停写确认
    frozen_seq      BIGINT,
    frozen_at       TIMESTAMP
);

-- ---------------------------------------------------------------------
-- 二、迁移原件区（mig）：快照清单/分段、复制原件、变更接入与序号进度
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mig_snapshot_manifest (
    id              BIGINT PRIMARY KEY,
    source_id       VARCHAR(32)  NOT NULL UNIQUE,
    cutoff_seq      BIGINT       NOT NULL,            -- 快照明确截止序号
    snapshot_time   TIMESTAMP    NOT NULL,            -- 同一逻辑截点
    total_sections  INTEGER      NOT NULL,
    total_rows      BIGINT       NOT NULL,
    checksum        VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,            -- LISTED/RECEIVING/COMPLETED
    created_at      TIMESTAMP    NOT NULL,
    completed_at    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mig_snapshot_section (
    id              BIGINT PRIMARY KEY,
    manifest_id     BIGINT       NOT NULL REFERENCES mig_snapshot_manifest(id),
    section_name    VARCHAR(32)  NOT NULL,           -- MEMBER/CARD/PURCHASE/OPENING
    ordinal         INTEGER      NOT NULL,
    expected_rows   BIGINT       NOT NULL,
    section_summary TEXT         NOT NULL,
    section_hash    VARCHAR(64)  NOT NULL,
    received_rows   BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL,           -- PENDING/RECEIVED/VERIFIED
    verified_at     TIMESTAMP,
    UNIQUE (manifest_id, section_name)
);

CREATE TABLE IF NOT EXISTS mig_raw_member (
    id              BIGINT PRIMARY KEY,
    member_code     VARCHAR(32)  NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    phone           VARCHAR(32),
    store_code      VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    manifest_id     BIGINT,
    source_seq      BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (member_code)
);

CREATE TABLE IF NOT EXISTS mig_raw_card (
    id              BIGINT PRIMARY KEY,
    card_no         VARCHAR(32)  NOT NULL,
    holder_code     VARCHAR(32)  NOT NULL,
    valid_from      TIMESTAMP    NOT NULL,
    valid_to        TIMESTAMP,
    store_code      VARCHAR(32)  NOT NULL,
    manifest_id     BIGINT,
    source_seq      BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (card_no, holder_code, valid_from)
);

CREATE TABLE IF NOT EXISTS mig_raw_purchase (
    id              BIGINT PRIMARY KEY,
    trans_no        VARCHAR(40)  NOT NULL,
    card_no         VARCHAR(32)  NOT NULL,
    store_code      VARCHAR(32)  NOT NULL,
    trans_time      TIMESTAMP    NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    points_delta    INTEGER      NOT NULL,
    manifest_id     BIGINT,
    source_seq      BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (trans_no)
);

CREATE TABLE IF NOT EXISTS mig_raw_opening (
    id              BIGINT PRIMARY KEY,
    member_code     VARCHAR(32)  NOT NULL,
    store_code      VARCHAR(32)  NOT NULL,
    opening_points  INTEGER      NOT NULL,
    manifest_id     BIGINT,
    source_seq      BIGINT       NOT NULL DEFAULT 0,
    evidence        VARCHAR(500),
    recorded_by     VARCHAR(64),
    UNIQUE (member_code)
);

-- 凭补充证据补录的真实期初留痕（区别于凭空造调整填平）
CREATE TABLE IF NOT EXISTS mig_opening_evidence (
    id               BIGINT PRIMARY KEY,
    member_code      VARCHAR(32) NOT NULL,
    opening_points   INTEGER     NOT NULL,
    evidence         VARCHAR(500) NOT NULL,
    recorded_by      VARCHAR(64) NOT NULL,
    recorded_at      TIMESTAMP   NOT NULL
);

-- 变更接入登记：重复事件不再改变余额；同序号不同内容记 CONFLICT
CREATE TABLE IF NOT EXISTS mig_change_event (
    id              BIGINT PRIMARY KEY,
    seq             BIGINT       NOT NULL,
    source_id       VARCHAR(32)  NOT NULL,
    entity_type     VARCHAR(16)  NOT NULL,
    entity_key      VARCHAR(64)  NOT NULL,
    op              VARCHAR(16)  NOT NULL,
    payload         TEXT         NOT NULL,
    content_hash    VARCHAR(64)  NOT NULL,
    business_time   TIMESTAMP    NOT NULL,
    linked_trans_no VARCHAR(40),
    received_at     TIMESTAMP    NOT NULL,
    apply_status    VARCHAR(16)  NOT NULL,           -- APPLIED/DUPLICATE/CONFLICT/GAP_WAIT/REVERSAL_WAIT/IGNORED
    applied_at      TIMESTAMP,
    note            VARCHAR(255)
);
-- 同一来源同一序号允许多条登记（原件应用 + 重复投递 + 冲突报文均留痕）
CREATE INDEX IF NOT EXISTS idx_mig_change_event_src_seq ON mig_change_event(source_id, seq);

-- 来源级序号进度：断档暂停、续接位置
CREATE TABLE IF NOT EXISTS mig_source_progress (
    source_id        VARCHAR(32) PRIMARY KEY,
    cutoff_seq       BIGINT NOT NULL,               -- 快照截止序号，仅应用其后变更
    contiguous_seq   BIGINT NOT NULL,               -- 已连续应用到的最大序号
    paused           BOOLEAN NOT NULL DEFAULT FALSE,
    pause_reason     VARCHAR(255),
    conflict_seq     BIGINT,
    last_chunk_id    BIGINT
);

-- 分段搬运位置（进程中断后续接）
CREATE TABLE IF NOT EXISTS mig_chunk_cursor (
    id               BIGINT PRIMARY KEY,
    stream_kind      VARCHAR(16) NOT NULL,          -- SNAPSHOT/CHANGE
    source_id        VARCHAR(32) NOT NULL,
    section_name     VARCHAR(32),
    last_key         BIGINT,
    last_seq         BIGINT,
    committed_at     TIMESTAMP NOT NULL,
    UNIQUE (stream_kind, source_id, section_name)
);

-- ---------------------------------------------------------------------
-- 三、新端可读数据区（target）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS target_member (
    id              BIGINT PRIMARY KEY,
    member_code     VARCHAR(32)  NOT NULL UNIQUE,
    name            VARCHAR(64)  NOT NULL,
    phone           VARCHAR(32),
    store_code      VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS target_card (
    id              BIGINT PRIMARY KEY,
    card_no         VARCHAR(32)  NOT NULL,
    holder_code     VARCHAR(32)  NOT NULL,
    valid_from      TIMESTAMP    NOT NULL,
    valid_to        TIMESTAMP,
    store_code      VARCHAR(32)  NOT NULL
);

-- 交易归属：AUTO 按持卡有效区间自动匹配，MANUAL 凭补充证据人工判定，BLOCKED 待判定
CREATE TABLE IF NOT EXISTS target_purchase (
    id               BIGINT PRIMARY KEY,
    trans_no         VARCHAR(40) NOT NULL UNIQUE,
    card_no          VARCHAR(32) NOT NULL,
    store_code       VARCHAR(32) NOT NULL,
    trans_time       TIMESTAMP   NOT NULL,
    amount           NUMERIC(12,2) NOT NULL,
    points_delta     INTEGER     NOT NULL,
    owner_code       VARCHAR(32),                    -- 采纳的归属会员
    ownership_kind   VARCHAR(16) NOT NULL,           -- AUTO/MANUAL/BLOCKED
    issue_type       VARCHAR(24),                    -- OVERLAP/MISSING/NONE
    source_seq       BIGINT      NOT NULL,          -- 来自快照(0)或变更序号
    valid_flag       BOOLEAN     NOT NULL DEFAULT TRUE,  -- 撤销/删除后为 FALSE
    linked_trans_no  VARCHAR(40),
    updated_at       TIMESTAMP   NOT NULL
);

CREATE TABLE IF NOT EXISTS target_ownership_candidate (
    id               BIGINT PRIMARY KEY,
    trans_no         VARCHAR(40) NOT NULL,
    candidate_code   VARCHAR(32) NOT NULL,
    card_no          VARCHAR(32) NOT NULL,
    valid_from       TIMESTAMP,                       -- 区间缺失的证据判定无持卡区间，允许为空
    valid_to         TIMESTAMP,
    UNIQUE (trans_no, candidate_code)
);

-- 人工判定及历史（原判定仍可查）
CREATE TABLE IF NOT EXISTS target_ownership_decision (
    id               BIGINT PRIMARY KEY,
    trans_no         VARCHAR(40) NOT NULL,
    decided_owner    VARCHAR(32) NOT NULL,
    evidence         VARCHAR(500) NOT NULL,         -- 必须提供补充证据
    decided_by       VARCHAR(64) NOT NULL,
    decided_at       TIMESTAMP   NOT NULL,
    superseded       BOOLEAN NOT NULL DEFAULT FALSE,
    prev_decision_id BIGINT
);

-- 积分台账：期初 + 逐笔有效变动
CREATE TABLE IF NOT EXISTS target_point_ledger (
    id               BIGINT PRIMARY KEY,
    member_code      VARCHAR(32) NOT NULL,
    entry_type       VARCHAR(16) NOT NULL,          -- OPENING/PURCHASE/REVERSAL/ADJUST
    trans_no         VARCHAR(40),
    points_delta     INTEGER     NOT NULL,
    balance_after    INTEGER     NOT NULL,
    source_seq       BIGINT      NOT NULL,
    created_at       TIMESTAMP   NOT NULL,
    UNIQUE (member_code, entry_type, trans_no)
);

CREATE TABLE IF NOT EXISTS target_point_balance (
    member_code      VARCHAR(32) PRIMARY KEY,
    store_code       VARCHAR(32) NOT NULL,
    opening_points   INTEGER,                       -- NULL 表示缺期初
    balance_points   INTEGER NOT NULL DEFAULT 0,
    rebuilt_at       TIMESTAMP   NOT NULL,
    blocked          BOOLEAN NOT NULL DEFAULT FALSE,
    block_reason     VARCHAR(255)
);

-- 核对结果（可重复执行，按 run_id 留档）
CREATE TABLE IF NOT EXISTS target_reconcile_run (
    id               BIGINT PRIMARY KEY,
    run_time         TIMESTAMP   NOT NULL,
    source_last_seq  BIGINT      NOT NULL,
    applied_seq      BIGINT      NOT NULL,
    overall_passed   BOOLEAN     NOT NULL,
    summary_json     TEXT        NOT NULL
);

CREATE TABLE IF NOT EXISTS target_reconcile_diff (
    id               BIGINT PRIMARY KEY,
    run_id           BIGINT NOT NULL REFERENCES target_reconcile_run(id),
    scope_level      VARCHAR(16) NOT NULL,          -- TOTAL/STORE/MEMBER
    scope_key        VARCHAR(32),
    diff_type        VARCHAR(24) NOT NULL,          -- SOURCE_MISSING/TARGET_EXTRA/OWNERSHIP_MISMATCH/BALANCE_GAP/SEQ_LAG/GAP_PAUSED/CONFLICT/MISSING_OPENING
    ref_kind         VARCHAR(16) NOT NULL,          -- ROW/EVENT
    ref_key          VARCHAR(64) NOT NULL,          -- 具体原行号或事件序号
    detail           VARCHAR(500) NOT NULL
);

-- 切换申请与四阶段状态
CREATE TABLE IF NOT EXISTS target_switch_request (
    id                BIGINT PRIMARY KEY,
    request_no        VARCHAR(40) NOT NULL UNIQUE,
    requested_by      VARCHAR(64) NOT NULL,
    requested_cutoff  BIGINT NOT NULL,              -- 两人申请不同截点只确认一份
    status            VARCHAR(24) NOT NULL,         -- 见下方状态机
    stage             VARCHAR(32) NOT NULL,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL,
    freeze_applied_at TIMESTAMP,
    final_seq         BIGINT,
    final_seq_at      TIMESTAMP,
    target_applied_at TIMESTAMP,
    opened_at         TIMESTAMP,
    cancelled_at      TIMESTAMP,
    cancel_reason     VARCHAR(255),
    fail_reason       VARCHAR(255),
    recovery_step     VARCHAR(500)
);
-- 状态机：
-- FREEZE_REQUESTED -> FINAL_SEQ_CONFIRMED -> TARGET_APPLIED -> OPENED
--                     任一阶段可 -> CANCELLED（OPENED 之后不得退回）
-- 失败：FAILED（带 recovery_step），先查询实际阶段再处理

CREATE TABLE IF NOT EXISTS target_switch_stage_log (
    id               BIGINT PRIMARY KEY,
    request_id       BIGINT NOT NULL REFERENCES target_switch_request(id),
    stage            VARCHAR(32) NOT NULL,
    actor            VARCHAR(64) NOT NULL,
    note             VARCHAR(500),
    logged_at        TIMESTAMP NOT NULL
);
