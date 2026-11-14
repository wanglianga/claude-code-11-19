package com.bookstore.migration.changestream;

import com.bookstore.migration.legacy.LegacyDataSeeder;
import com.bookstore.migration.legacy.LegacySimulatorService;
import com.bookstore.migration.projection.TargetProjectionService;
import com.bookstore.migration.support.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 变更流接入规则：
 * - 仅应用快照截止序号之后的事件；
 * - 严格连续序号：断档即暂停该来源，记录原因，补齐后续接；
 * - 重复事件（同序号同内容哈希）登记 DUPLICATE，不再改变余额；
 * - 同序号不同内容：登记 CONFLICT 并暂停，需人工处置后续接；
 * - 撤销先到：登记 REVERSAL_WAIT 等待原交易，原交易到达后联动生效；
 * - 每批记录已提交位置（mig_chunk_cursor），进程中断后从 contiguous_seq 续接。
 */
@Service
public class ChangeStreamService {

    private final JdbcTemplate jdbc;
    private final LegacySimulatorService legacy;
    private final TargetProjectionService projection;
    private final ObjectMapper objectMapper;

    public ChangeStreamService(JdbcTemplate jdbc, LegacySimulatorService legacy,
                               TargetProjectionService projection, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.legacy = legacy;
        this.projection = projection;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------
    // 主动拉取：按已连续应用位置继续，分段提交
    // ---------------------------------------------------------------
    @Transactional
    public Map<String, Object> pullChunk(int limit) {
        ensureProgressExists();
        Map<String, Object> progress = getProgress();
        if (Boolean.TRUE.equals(progress.get("paused"))) {
            throw ApiException.precondition("来源已暂停（" + progress.get("pause_reason")
                    + "），请先处置后再续接");
        }
        long afterSeq = ((Number) progress.get("contiguous_seq")).longValue();
        List<Map<String, Object>> events = legacy.fetchChanges(afterSeq, limit);
        int applied = 0;
        Long gapAt = null;
        for (Map<String, Object> event : events) {
            long seq = ((Number) event.get("seq")).longValue();
            long expected = afterSeq + 1;
            if (seq != expected) {
                gapAt = expected;
                pause("序号断档：期望 " + expected + "，实际到达 " + seq + "，暂停来源等待补齐", null);
                break;
            }
            registerAndApply(event);
            afterSeq = seq;
            applied++;
        }
        List<Long> cursorIds = jdbc.queryForList(
                "SELECT id FROM mig_chunk_cursor WHERE stream_kind='CHANGE' AND source_id=?",
                Long.class, LegacyDataSeeder.SOURCE_ID);
        if (cursorIds.isEmpty()) {
            jdbc.update("INSERT INTO mig_chunk_cursor(id, stream_kind, source_id, last_seq, committed_at) "
                            + "VALUES (nextval('app_global_seq'),'CHANGE',?,?,?)",
                    LegacyDataSeeder.SOURCE_ID, afterSeq, LocalDateTime.now());
        } else {
            jdbc.update("UPDATE mig_chunk_cursor SET last_seq=?, committed_at=? WHERE id=?",
                    afterSeq, LocalDateTime.now(), cursorIds.get(0));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("pulled", events.size());
        resp.put("applied", applied);
        resp.put("contiguous_seq", afterSeq);
        resp.put("gap_at", gapAt);
        resp.put("paused", gapAt != null);
        return resp;
    }

    /**
     * 手工投递事件（用于重复/冲突/乱序演示与外部来源接入）。
     * 入参为旧端事件同构报文。
     */
    @Transactional
    public Map<String, Object> ingest(Map<String, Object> event) {
        ensureProgressExists();
        normalizeEventTime(event);
        long seq = ((Number) event.get("seq")).longValue();
        Map<String, Object> progress = getProgress();
        long cutoff = ((Number) progress.get("cutoff_seq")).longValue();
        if (seq <= cutoff) {
            Long already = jdbc.queryForObject(
                    "SELECT count(*) FROM mig_change_event WHERE source_id=? AND seq=?",
                    Long.class, LegacyDataSeeder.SOURCE_ID, seq);
            if (already != null && already > 0) {
                return Map.of("seq", seq, "apply_status", "IGNORED", "message", "该序号已登记");
            }
            registerIgnored(event, "序号 " + seq + " 不大于快照截止序号 " + cutoff + "，忽略");
            return Map.of("seq", seq, "apply_status", "IGNORED");
        }
        List<Map<String, Object>> known = jdbc.queryForList(
                "SELECT content_hash, apply_status FROM mig_change_event WHERE source_id=? AND seq=?",
                LegacyDataSeeder.SOURCE_ID, seq);
        if (!known.isEmpty()) {
            String knownHash = (String) known.get(0).get("content_hash");
            if (knownHash.equals(event.get("content_hash"))) {
                // 重复事件：登记但不应用，余额不变
                registerDuplicate(event);
                return Map.of("seq", seq, "apply_status", "DUPLICATE",
                        "message", "重复事件，不再改变余额");
            }
            registerConflict(event, "同序号不同内容：已存哈希 " + knownHash
                    + "，新报文哈希 " + event.get("content_hash"));
            pause("序号 " + seq + " 内容冲突，暂停来源等待人工处置", seq);
            return Map.of("seq", seq, "apply_status", "CONFLICT", "paused", true);
        }
        long contiguous = ((Number) progress.get("contiguous_seq")).longValue();
        if (seq != contiguous + 1) {
            registerGapWait(event, "序号未连续：当前已到 " + contiguous + "，收到 " + seq);
            pause("序号断档：期望 " + (contiguous + 1) + "，实际收到 " + seq, null);
            return Map.of("seq", seq, "apply_status", "GAP_WAIT", "paused", true);
        }
        registerAndApply(event);
        return Map.of("seq", seq, "apply_status", "APPLIED");
    }

    // ---------------------------------------------------------------
    // 续接与冲突处置
    // ---------------------------------------------------------------
    @Transactional
    public Map<String, Object> resume() {
        Map<String, Object> progress = getProgress();
        if (!Boolean.TRUE.equals(progress.get("paused"))) {
            return Map.of("message", "来源未暂停，无需续接", "contiguous_seq", progress.get("contiguous_seq"));
        }
        Long conflictSeq = progress.get("conflict_seq") == null ? null
                : ((Number) progress.get("conflict_seq")).longValue();
        if (conflictSeq != null) {
            throw ApiException.precondition("序号 " + conflictSeq + " 存在内容冲突，须先调用冲突处置接口");
        }
        long expected = ((Number) progress.get("contiguous_seq")).longValue() + 1;
        Long exists = jdbc.queryForObject(
                "SELECT count(*) FROM legacy_change_event WHERE seq=?", Long.class, expected);
        if (exists == null || exists == 0) {
            throw ApiException.precondition("断档序号 " + expected + " 尚未在旧端补齐，继续暂停");
        }
        // 补齐后先应用 GAP_WAIT 登记的事件（若已登记），再解除暂停
        List<Map<String, Object>> waiting = jdbc.queryForList(
                "SELECT * FROM mig_change_event WHERE source_id=? AND apply_status='GAP_WAIT' ORDER BY seq",
                LegacyDataSeeder.SOURCE_ID);
        jdbc.update("UPDATE mig_source_progress SET paused=FALSE, pause_reason=NULL WHERE source_id=?",
                LegacyDataSeeder.SOURCE_ID);
        for (Map<String, Object> w : waiting) {
            long seq = ((Number) w.get("seq")).longValue();
            if (seq == expected) {
                Map<String, Object> fresh = jdbc.queryForMap(
                        "SELECT seq, source_id, entity_type, entity_key, op, payload, content_hash, "
                                + "business_time, linked_trans_no FROM legacy_change_event WHERE seq=?", seq);
                jdbc.update("DELETE FROM mig_change_event WHERE id=?", w.get("id"));
                registerAndApply(fresh);
            }
        }
        return status();
    }

    @Transactional
    public Map<String, Object> resolveConflict(long seq, String action) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM mig_change_event WHERE source_id=? AND seq=? AND apply_status='CONFLICT'",
                LegacyDataSeeder.SOURCE_ID, seq);
        if (rows.isEmpty()) {
            throw ApiException.notFound("序号 " + seq + " 无冲突记录");
        }
        if (!"KEEP_ORIGINAL".equals(action)) {
            throw ApiException.badRequest("仅支持 KEEP_ORIGINAL：以旧端原件为准，冲突报文不进入余额");
        }
        Map<String, Object> original = legacy.fetchChanges(seq - 1, 1).stream()
                .filter(e -> ((Number) e.get("seq")).longValue() == seq).findFirst()
                .orElseThrow(() -> ApiException.precondition("旧端原件缺失序号 " + seq + "，无法以原件为准"));
        // 冲突报文留痕但不再计为未决冲突：切换条件只认 apply_status='CONFLICT'
        jdbc.update("UPDATE mig_change_event SET apply_status='IGNORED', "
                + "note=COALESCE(note,'')||'；冲突处置：保留原件，冲突报文丢弃' WHERE id=?",
                rows.get(0).get("id"));
        jdbc.update("UPDATE mig_source_progress SET paused=FALSE, pause_reason=NULL, conflict_seq=NULL "
                + "WHERE source_id=?", LegacyDataSeeder.SOURCE_ID);
        // 原件若尚未应用则补应用（冲突事件本身未投影）
        long contiguous = ((Number) getProgress().get("contiguous_seq")).longValue();
        if (seq == contiguous + 1) {
            registerAndApply(original);
        }
        return status();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        Map<String, Object> progress = getProgress();
        Map<String, Object> sourceState = legacy.sourceState();
        List<Map<String, Object>> recent = jdbc.queryForList(
                "SELECT seq, entity_type, entity_key, op, apply_status, note, applied_at "
                        + "FROM mig_change_event ORDER BY seq DESC, id DESC LIMIT 20");
        long backlog = ((Number) sourceState.get("last_seq")).longValue()
                - ((Number) progress.get("contiguous_seq")).longValue();
        Map<String, Object> resp = new LinkedHashMap<>(progress);
        resp.put("source_last_seq", sourceState.get("last_seq"));
        resp.put("source_frozen", sourceState.get("frozen"));
        resp.put("estimated_backlog_events", Math.max(backlog, 0));
        resp.put("recent_events", recent);
        return resp;
    }

    // ---------------------------------------------------------------
    // 内部：登记 + 应用
    // ---------------------------------------------------------------
    private void registerAndApply(Map<String, Object> event) {
        long seq = ((Number) event.get("seq")).longValue();
        // 登记（若已存在同哈希则跳过重复应用）
        List<Map<String, Object>> known = jdbc.queryForList(
                "SELECT id, apply_status FROM mig_change_event WHERE source_id=? AND seq=?",
                LegacyDataSeeder.SOURCE_ID, seq);
        long eventId;
        if (known.isEmpty()) {
            eventId = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
            jdbc.update("INSERT INTO mig_change_event(id, seq, source_id, entity_type, entity_key, op, payload, "
                            + "content_hash, business_time, linked_trans_no, received_at, apply_status) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,'RECEIVED')",
                    eventId, seq, event.get("source_id"), event.get("entity_type"), event.get("entity_key"),
                    event.get("op"), event.get("payload"), event.get("content_hash"),
                    event.get("business_time"), event.get("linked_trans_no"), LocalDateTime.now());
        } else {
            eventId = ((Number) known.get(0).get("id")).longValue();
            if (!"REVERSAL_WAIT".equals(known.get(0).get("apply_status"))
                    && !"GAP_WAIT".equals(known.get(0).get("apply_status"))) {
                return; // 已应用/重复，不再改变余额
            }
        }
        boolean waiting = dispatch(event, seq);
        // 撤销先到时保留 REVERSAL_WAIT 状态，等待原交易到达联动；序号连续性本身仍然前进
        if (!waiting) {
            jdbc.update("UPDATE mig_change_event SET apply_status='APPLIED', applied_at=? WHERE id=?",
                    LocalDateTime.now(), eventId);
        }
        jdbc.update("UPDATE mig_source_progress SET contiguous_seq=? WHERE source_id=?",
                seq, LegacyDataSeeder.SOURCE_ID);
    }

    /** @return true=撤销先到，处于等待原交易状态；false=已正常应用 */
    @SuppressWarnings("unchecked")
    private boolean dispatch(Map<String, Object> event, long seq) {
        String entityType = (String) event.get("entity_type");
        String op = (String) event.get("op");
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue((String) event.get("payload"), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("事件 payload 解析失败 seq=" + seq, e);
        }
        switch (entityType) {
            case "PURCHASE" -> {
                if ("DELETE".equals(op)) {
                    String transNo = (String) event.getOrDefault("linked_trans_no", payload.get("trans_no"));
                    List<Map<String, Object>> txn = jdbc.queryForList(
                            "SELECT trans_no FROM target_purchase WHERE trans_no=?", transNo);
                    if (txn.isEmpty()) {
                        // 撤销先到：等待原交易
                        jdbc.update("UPDATE mig_change_event SET apply_status='REVERSAL_WAIT', "
                                + "note='撤销先到，等待原交易 " + transNo + "' WHERE seq=? AND source_id=?",
                                seq, LegacyDataSeeder.SOURCE_ID);
                        return true;
                    }
                    projection.applyPurchaseDelete(transNo, seq);
                } else {
                    projection.applyPurchaseUpsert(payload, seq);
                    // 原交易到达：联动此前等待的撤销
                    List<Map<String, Object>> waits = jdbc.queryForList(
                            "SELECT id, seq FROM mig_change_event WHERE apply_status='REVERSAL_WAIT' "
                                    + "AND linked_trans_no=? AND source_id=?",
                            payload.get("trans_no"), LegacyDataSeeder.SOURCE_ID);
                    for (Map<String, Object> w : waits) {
                        projection.applyPurchaseDelete((String) payload.get("trans_no"),
                                ((Number) w.get("seq")).longValue());
                        jdbc.update("UPDATE mig_change_event SET apply_status='APPLIED', applied_at=?, "
                                + "note=COALESCE(note,'')||'；原交易到达，撤销生效' WHERE id=?",
                                LocalDateTime.now(), w.get("id"));
                    }
                }
            }
            case "MEMBER" -> projection.applyMemberUpsert(payload, seq);
            case "CARD" -> projection.applyCardUpsert(payload, seq);
            case "OPENING" -> projection.applyOpeningUpsert(payload, seq);
            default -> throw ApiException.badRequest("未知实体类型: " + entityType);
        }
        return false;
    }

    // ---------------------------------------------------------------
    private void registerDuplicate(Map<String, Object> event) {
        jdbc.update("INSERT INTO mig_change_event(id, seq, source_id, entity_type, entity_key, op, payload, "
                        + "content_hash, business_time, linked_trans_no, received_at, apply_status, note) "
                        + "VALUES (nextval('app_global_seq'),?,?,?,?,?,?,?,?,?,?,'DUPLICATE','重复事件，余额不变')",
                event.get("seq"), event.get("source_id"), event.get("entity_type"), event.get("entity_key"),
                event.get("op"), event.get("payload"), event.get("content_hash"),
                event.get("business_time"), event.get("linked_trans_no"), LocalDateTime.now());
    }

    private void registerConflict(Map<String, Object> event, String note) {
        jdbc.update("INSERT INTO mig_change_event(id, seq, source_id, entity_type, entity_key, op, payload, "
                        + "content_hash, business_time, linked_trans_no, received_at, apply_status, note) "
                        + "VALUES (nextval('app_global_seq'),?,?,?,?,?,?,?,?,?,?,'CONFLICT',?)",
                event.get("seq"), event.get("source_id"), event.get("entity_type"), event.get("entity_key"),
                event.get("op"), event.get("payload"), event.get("content_hash"),
                event.get("business_time"), event.get("linked_trans_no"), LocalDateTime.now(), note);
    }

    private void registerGapWait(Map<String, Object> event, String note) {
        jdbc.update("INSERT INTO mig_change_event(id, seq, source_id, entity_type, entity_key, op, payload, "
                        + "content_hash, business_time, linked_trans_no, received_at, apply_status, note) "
                        + "VALUES (nextval('app_global_seq'),?,?,?,?,?,?,?,?,?,?,'GAP_WAIT',?)",
                event.get("seq"), event.get("source_id"), event.get("entity_type"), event.get("entity_key"),
                event.get("op"), event.get("payload"), event.get("content_hash"),
                event.get("business_time"), event.get("linked_trans_no"), LocalDateTime.now(), note);
    }

    private void registerIgnored(Map<String, Object> event, String note) {
        jdbc.update("INSERT INTO mig_change_event(id, seq, source_id, entity_type, entity_key, op, payload, "
                        + "content_hash, business_time, linked_trans_no, received_at, apply_status, note) "
                        + "VALUES (nextval('app_global_seq'),?,?,?,?,?,?,?,?,?,?,'IGNORED',?)",
                event.get("seq"), event.get("source_id"), event.get("entity_type"), event.get("entity_key"),
                event.get("op"), event.get("payload"), event.get("content_hash"),
                event.get("business_time"), event.get("linked_trans_no"), LocalDateTime.now(), note);
    }

    private void pause(String reason, Long conflictSeq) {
        jdbc.update("UPDATE mig_source_progress SET paused=TRUE, pause_reason=?, conflict_seq=? WHERE source_id=?",
                reason, conflictSeq, LegacyDataSeeder.SOURCE_ID);
    }

    private void normalizeEventTime(Map<String, Object> event) {
        Object bt = event.get("business_time");
        if (bt instanceof String s && !s.isBlank()) {
            event.put("business_time", LocalDateTime.parse(s.replace(' ', 'T')));
        }
    }

    private void ensureProgressExists() {
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT source_id FROM mig_source_progress WHERE source_id=?", LegacyDataSeeder.SOURCE_ID);
        if (list.isEmpty()) {
            throw ApiException.precondition("快照基线尚未完成，变更流不得开始（缺少截止序号）");
        }
    }

    private Map<String, Object> getProgress() {
        return jdbc.queryForMap("SELECT * FROM mig_source_progress WHERE source_id=?",
                LegacyDataSeeder.SOURCE_ID);
    }
}
