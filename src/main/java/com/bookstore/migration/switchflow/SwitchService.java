package com.bookstore.migration.switchflow;

import com.bookstore.migration.legacy.LegacyDataSeeder;
import com.bookstore.migration.legacy.LegacySimulatorService;
import com.bookstore.migration.reconcile.ReconciliationService;
import com.bookstore.migration.support.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 切换状态机：
 *   FREEZE_REQUESTED -> FINAL_SEQ_CONFIRMED -> TARGET_APPLIED -> OPENED
 *   开放前任一阶段可 -> CANCELLED（同步重新开放旧端）；OPENED 后不得自动退回。
 * 共同放行条件（缺一维持旧端）：旧端停写、最终序号追平、归属冲突处理完、核对通过。
 */
@Service
public class SwitchService {

    public static final String S_FREEZE_REQUESTED = "FREEZE_REQUESTED";
    public static final String S_FINAL_SEQ_CONFIRMED = "FINAL_SEQ_CONFIRMED";
    public static final String S_TARGET_APPLIED = "TARGET_APPLIED";
    public static final String S_OPENED = "OPENED";
    public static final String S_CANCELLED = "CANCELLED";
    public static final String S_FAILED = "FAILED";

    private final JdbcTemplate jdbc;
    private final LegacySimulatorService legacy;
    private final ReconciliationService reconciliation;
    private final SwitchFailureRecorder failureRecorder;

    public SwitchService(JdbcTemplate jdbc, LegacySimulatorService legacy,
                         ReconciliationService reconciliation, SwitchFailureRecorder failureRecorder) {
        this.jdbc = jdbc;
        this.legacy = legacy;
        this.reconciliation = reconciliation;
        this.failureRecorder = failureRecorder;
    }

    /** 阶段 1：保存停写申请。两人申请不同截点只确认一份——同时只允许一份活动申请 */
    @Transactional
    public Map<String, Object> requestSwitch(String requestedBy, long requestedCutoff) {
        List<Map<String, Object>> active = activeRequests();
        if (!active.isEmpty()) {
            throw ApiException.conflict("已存在活动切换申请 " + active.get(0).get("request_no")
                    + "（截点 " + active.get(0).get("requested_cutoff") + "，状态 "
                    + active.get(0).get("status") + "）；两人申请不同切换截点只能确认一份");
        }
        Map<String, Object> source = legacy.sourceState();
        long sourceLast = ((Number) source.get("last_seq")).longValue();
        if (requestedCutoff > sourceLast) {
            throw ApiException.precondition("申请截点 " + requestedCutoff + " 超过旧端当前最大序号 " + sourceLast);
        }
        String requestNo = "SW-" + LocalDateTime.now().getYear()
                + String.format("%02d", LocalDateTime.now().getMonthValue())
                + String.format("%02d", LocalDateTime.now().getDayOfMonth())
                + "-" + (jdbc.queryForObject("SELECT coalesce(count(*),0)+1 FROM target_switch_request", Long.class));
        long id = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO target_switch_request(id, request_no, requested_by, requested_cutoff, status, "
                        + "stage, created_at, updated_at) VALUES (?,?,?,?,?, ?, ?,?)",
                id, requestNo, requestedBy, requestedCutoff, S_FREEZE_REQUESTED, "FREEZE_REQUEST", now, now);
        logStage(id, "FREEZE_REQUEST", requestedBy, "提交停写申请，截点序号 " + requestedCutoff);
        return getRequest(id);
    }

    /** 阶段 2：旧端确认最终序号并停写；此后旧端拒绝一切新营业写入 */
    @Transactional
    public Map<String, Object> confirmFinalSeq(long requestId, String actor) {
        Map<String, Object> req = mustGet(requestId);
        ensureNotTerminal(req);
        if (!S_FREEZE_REQUESTED.equals(req.get("status")) && !S_FAILED.equals(req.get("status"))) {
            throw ApiException.precondition("当前状态 " + req.get("status") + "，不能确认最终序号");
        }
        long finalSeq = ((Number) req.get("requested_cutoff")).longValue();
        Map<String, Object> source = legacy.sourceState();
        long sourceLast = ((Number) source.get("last_seq")).longValue();
        if (finalSeq != sourceLast) {
            throw ApiException.precondition("截点 " + finalSeq + " 与旧端最大序号 " + sourceLast
                    + " 不一致：存在未纳入的营业写入，请先追平或重新申请");
        }
        legacy.markFrozen(finalSeq);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE target_switch_request SET status=?, stage='FINAL_SEQ_CONFIRM', final_seq=?, "
                        + "final_seq_at=?, freeze_applied_at=?, updated_at=?, fail_reason=NULL, recovery_step=NULL WHERE id=?",
                S_FINAL_SEQ_CONFIRMED, finalSeq, now, now, now, requestId);
        logStage(requestId, "FINAL_SEQ_CONFIRM", actor, "旧端确认最终序号 " + finalSeq + " 并停写");
        return getRequest(requestId);
    }

    /** 阶段 3：目标应用完成（追平 + 冲突清零 + 阻断清零 + 跑核对） */
    @Transactional
    public Map<String, Object> applyTarget(long requestId, String actor) {
        Map<String, Object> req = mustGet(requestId);
        ensureNotTerminal(req);
        if (S_OPENED.equals(req.get("status"))) {
            throw ApiException.precondition("新端已开放，无需再应用");
        }
        Map<String, Object> conditions = evaluateConditions(req);
        List<String> unmet = unmetConditions(conditions);
        if (!unmet.isEmpty()) {
            String recovery = "恢复步骤：1) 调用 /changes/pull 追平序号；2) 若暂停按 /changes/resume 或冲突处置续接；"
                    + "3) 对阻断交易在 /ownership/decisions 凭证据判定；4) 重新 /reconcile/check 后再执行本接口。";
            failureRecorder.markFailed(requestId, "目标应用未满足条件：" + unmet, recovery, actor,
                    "未满足：" + unmet);
            throw ApiException.precondition("目标应用被阻断，未满足条件：" + unmet + "。" + recovery);
        }
        Map<String, Object> check = reconciliation.runCheck();
        if (!Boolean.TRUE.equals(check.get("overall_passed"))) {
            String recovery = "恢复步骤：查看 /reconcile/runs/" + check.get("run_id")
                    + " 的逐条差异，修正归属或补齐期初后重跑核对；期间旧端保持停写，两侧不会同时写。";
            failureRecorder.markFailed(requestId, "核对未通过，差异 " + check.get("diff_count") + " 条",
                    recovery, actor, "核对未通过 run=" + check.get("run_id"));
            throw ApiException.precondition("核对未通过（" + check.get("diff_count") + " 条差异）。" + recovery);
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE target_switch_request SET status=?, stage='TARGET_APPLY', target_applied_at=?, "
                        + "updated_at=?, fail_reason=NULL, recovery_step=NULL WHERE id=?",
                S_TARGET_APPLIED, now, now, requestId);
        logStage(requestId, "TARGET_APPLY", actor, "目标应用完成，核对通过 run=" + check.get("run_id"));
        return getRequest(requestId);
    }

    /** 阶段 4：开放确认。再次校验四项共同条件，全部满足才开放新端 */
    @Transactional
    public Map<String, Object> confirmOpen(long requestId, String actor) {
        Map<String, Object> req = mustGet(requestId);
        if (S_OPENED.equals(req.get("status"))) {
            return getRequest(requestId);
        }
        if (!S_TARGET_APPLIED.equals(req.get("status"))) {
            throw ApiException.precondition("目标应用尚未完成（当前 " + req.get("status")
                    + "），不能开放新端");
        }
        Map<String, Object> conditions = evaluateConditions(req);
        List<String> unmet = unmetConditions(conditions);
        if (!unmet.isEmpty()) {
            throw ApiException.precondition("开放被阻断，未满足条件：" + unmet
                    + "；未达到前维持旧端读取与写入");
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE target_switch_request SET status=?, stage='OPEN_CONFIRM', opened_at=?, updated_at=? WHERE id=?",
                S_OPENED, now, now, requestId);
        logStage(requestId, "OPEN_CONFIRM", actor, "新端开放，切换完成；此后不得自动退回旧端");
        return getRequest(requestId);
    }

    /** 取消：仅未开放时可批准，同步重新开放旧端；已开放不得退回 */
    @Transactional
    public Map<String, Object> cancel(long requestId, String actor, String reason) {
        Map<String, Object> req = mustGet(requestId);
        if (S_OPENED.equals(req.get("status"))) {
            throw ApiException.conflict("新端已开放，不得自动退回旧端；如需回退须走人工应急预案，不能由本接口执行");
        }
        if (S_CANCELLED.equals(req.get("status"))) {
            return getRequest(requestId);
        }
        Map<String, Object> source = legacy.sourceState();
        if (Boolean.TRUE.equals(source.get("frozen"))) {
            legacy.unfreeze(); // 重新开放旧端写入
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE target_switch_request SET status=?, stage='CANCELLED', cancelled_at=?, cancel_reason=?, "
                        + "updated_at=? WHERE id=?",
                S_CANCELLED, now, reason, now, requestId);
        logStage(requestId, "CANCEL", actor, "取消切换并重新开放旧端：" + reason);
        return getRequest(requestId);
    }

    /** 中途超时：先查询实际阶段与可执行恢复动作 */
    @Transactional(readOnly = true)
    public Map<String, Object> status(long requestId) {
        Map<String, Object> req = getRequest(requestId);
        req.put("stage_logs", jdbc.queryForList(
                "SELECT stage, actor, note, logged_at FROM target_switch_stage_log WHERE request_id=? ORDER BY id",
                requestId));
        req.put("conditions", evaluateConditions(req));
        req.put("allowed_actions", allowedActions((String) req.get("status")));
        return req;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRequests() {
        return jdbc.queryForList("SELECT id, request_no, requested_by, requested_cutoff, status, stage, "
                + "final_seq, created_at, updated_at, fail_reason FROM target_switch_request ORDER BY id DESC");
    }

    /** 条件面板：预计积压 + 四项共同条件是否满足 */
    @Transactional(readOnly = true)
    public Map<String, Object> conditionsPanel() {
        List<Map<String, Object>> active = activeRequests();
        Map<String, Object> req = active.isEmpty() ? Map.of() : active.get(0);
        Map<String, Object> panel = evaluateConditions(req);
        panel.put("active_request", req);
        return panel;
    }

    // ---------------------------------------------------------------
    private Map<String, Object> evaluateConditions(Map<String, Object> req) {
        Map<String, Object> source = legacy.sourceState();
        long sourceLast = ((Number) source.get("last_seq")).longValue();
        long contiguous = jdbc.queryForObject(
                "SELECT coalesce(max(contiguous_seq),0) FROM mig_source_progress WHERE source_id=?",
                Long.class, LegacyDataSeeder.SOURCE_ID);
        List<Boolean> pausedRows = jdbc.queryForList(
                "SELECT paused FROM mig_source_progress WHERE source_id=?", Boolean.class,
                LegacyDataSeeder.SOURCE_ID);
        boolean pausedFlag = !pausedRows.isEmpty() && Boolean.TRUE.equals(pausedRows.get(0));
        Long conflictCount = jdbc.queryForObject(
                "SELECT count(*) FROM mig_change_event WHERE apply_status='CONFLICT'", Long.class);
        Long blockedCount = jdbc.queryForObject(
                "SELECT count(*) FROM target_purchase WHERE ownership_kind='BLOCKED' AND valid_flag=TRUE",
                Long.class);
        Long missingOpening = jdbc.queryForObject(
                "SELECT count(*) FROM target_point_balance WHERE opening_points IS NULL", Long.class);
        Long lastPassedRun = jdbc.queryForObject(
                "SELECT count(*) FROM target_reconcile_run WHERE overall_passed=TRUE "
                        + "AND id=(SELECT max(id) FROM target_reconcile_run)", Long.class);
        Long totalRuns = jdbc.queryForObject("SELECT count(*) FROM target_reconcile_run", Long.class);
        long requestedCutoff = req.get("requested_cutoff") == null ? sourceLast
                : ((Number) req.get("requested_cutoff")).longValue();

        boolean frozen = Boolean.TRUE.equals(source.get("frozen"));
        boolean seqCaughtUp = contiguous >= sourceLast && !pausedFlag;
        boolean noOwnershipIssue = (conflictCount == null || conflictCount == 0)
                && (blockedCount == null || blockedCount == 0);
        boolean reconcilePassed = totalRuns != null && totalRuns > 0 && lastPassedRun != null && lastPassedRun > 0;

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("estimated_backlog_events", Math.max(sourceLast - contiguous, 0));
        c.put("source_last_seq", sourceLast);
        c.put("applied_contiguous_seq", contiguous);
        c.put("requested_cutoff", requestedCutoff);
        c.put("condition_legacy_frozen", frozen);
        c.put("condition_seq_caught_up", seqCaughtUp);
        c.put("condition_ownership_conflicts_cleared", noOwnershipIssue);
        c.put("condition_reconcile_passed", reconcilePassed);
        c.put("paused", pausedFlag);
        c.put("conflict_event_count", conflictCount);
        c.put("blocked_transaction_count", blockedCount);
        c.put("missing_opening_count", missingOpening);
        c.put("all_met", frozen && seqCaughtUp && noOwnershipIssue && reconcilePassed);
        c.put("unmet", unmetConditions(c));
        return c;
    }

    private List<String> unmetConditions(Map<String, Object> c) {
        List<String> unmet = new ArrayList<>();
        if (!Boolean.TRUE.equals(c.get("condition_legacy_frozen"))) {
            unmet.add("旧端未停写");
        }
        if (!Boolean.TRUE.equals(c.get("condition_seq_caught_up"))) {
            unmet.add("最终序号未追平/来源暂停（积压 " + c.get("estimated_backlog_events") + "）");
        }
        if (!Boolean.TRUE.equals(c.get("condition_ownership_conflicts_cleared"))) {
            unmet.add("仍有归属冲突或待判定交易（冲突 " + c.get("conflict_event_count")
                    + "，阻断 " + c.get("blocked_transaction_count") + "）");
        }
        if (!Boolean.TRUE.equals(c.get("condition_reconcile_passed"))) {
            unmet.add("最近一次核对未通过或尚未核对");
        }
        return unmet;
    }

    private List<String> allowedActions(String status) {
        return switch (status) {
            case S_FREEZE_REQUESTED, S_FAILED -> List.of("confirmFinalSeq", "applyTarget", "cancel", "status");
            case S_FINAL_SEQ_CONFIRMED -> List.of("applyTarget", "cancel", "status");
            case S_TARGET_APPLIED -> List.of("confirmOpen", "cancel", "status");
            case S_OPENED -> List.of("status");
            default -> List.of("status");
        };
    }

    private List<Map<String, Object>> activeRequests() {
        return jdbc.queryForList("SELECT * FROM target_switch_request WHERE status NOT IN (?,?) ORDER BY id",
                S_CANCELLED, S_OPENED);
    }

    private Map<String, Object> mustGet(long id) {
        List<Map<String, Object>> list = jdbc.queryForList("SELECT * FROM target_switch_request WHERE id=?", id);
        if (list.isEmpty()) {
            throw ApiException.notFound("切换申请不存在: " + id);
        }
        return list.get(0);
    }

    private Map<String, Object> getRequest(long id) {
        return mustGet(id);
    }

    private void ensureNotTerminal(Map<String, Object> req) {
        if (S_CANCELLED.equals(req.get("status"))) {
            throw ApiException.conflict("申请已取消，不能继续操作；请重新提交切换申请");
        }
        if (S_OPENED.equals(req.get("status"))) {
            throw ApiException.conflict("新端已开放，不能回退该申请");
        }
    }

    private void logStage(long requestId, String stage, String actor, String note) {
        jdbc.update("INSERT INTO target_switch_stage_log(id, request_id, stage, actor, note, logged_at) "
                        + "VALUES (nextval('app_global_seq'),?,?,?,?,?)",
                requestId, stage, actor, note, LocalDateTime.now());
    }
}
