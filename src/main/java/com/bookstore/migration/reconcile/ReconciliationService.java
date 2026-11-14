package com.bookstore.migration.reconcile;

import com.bookstore.migration.legacy.LegacyDataSeeder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核对：从总数下钻到门店及会员。
 * 差异四类（每条必须对应具体原行 ROW 或事件 EVENT）：
 *  SOURCE_MISSING      源端存在但未迁入
 *  TARGET_EXTRA        新端多出
 *  OWNERSHIP_MISMATCH  归属不一致/待人工判定
 *  BALANCE_GAP         积分余额差
 * 另含序号滞后 SEQ_LAG、断档 GAP_PAUSED、冲突 CONFLICT、缺期初 MISSING_OPENING。
 */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReconciliationService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> runCheck() {
        List<Map<String, Object>> progressRows = jdbc.queryForList(
                "SELECT * FROM mig_source_progress WHERE source_id=?", LegacyDataSeeder.SOURCE_ID);
        if (progressRows.isEmpty()) {
            throw new com.bookstore.migration.support.ApiException(412,
                    "快照基线尚未完成，无法核对（缺少截止序号与迁移原件）");
        }
        long runId = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
        List<Map<String, Object>> diffs = new ArrayList<>();

        Map<String, Object> progress = progressRows.get(0);
        long appliedSeq = ((Number) progress.get("contiguous_seq")).longValue();
        Map<String, Object> sourceState = jdbc.queryForMap(
                "SELECT * FROM legacy_source_state WHERE source_id=?", LegacyDataSeeder.SOURCE_ID);
        long sourceLast = ((Number) sourceState.get("last_seq")).longValue();

        // 1) 序号滞后 / 断档暂停 / 冲突
        if (appliedSeq < sourceLast) {
            diffs.add(diff("TOTAL", null, "SEQ_LAG", "EVENT",
                    appliedSeq + "->" + sourceLast,
                    "已应用序号 " + appliedSeq + " 落后旧端最终序号 " + sourceLast
                            + "，积压 " + (sourceLast - appliedSeq) + " 个事件"));
        }
        if (Boolean.TRUE.equals(progress.get("paused"))) {
            String type = progress.get("conflict_seq") != null ? "CONFLICT" : "GAP_PAUSED";
            diffs.add(diff("TOTAL", null, type, "EVENT",
                    String.valueOf(progress.get("conflict_seq") == null
                            ? progress.get("pause_reason") : progress.get("conflict_seq")),
                    "来源暂停：" + progress.get("pause_reason")));
        }

        // 2) 会员：源端存在但未迁入 / 新端多出
        List<Map<String, Object>> sourceMembers = jdbc.queryForList(
                "SELECT member_code, store_code FROM mig_raw_member ORDER BY member_code");
        List<Map<String, Object>> targetMembers = jdbc.queryForList(
                "SELECT member_code, store_code FROM target_member ORDER BY member_code");
        diffMembers(sourceMembers, targetMembers, diffs);

        // 3) 交易：SOURCE_MISSING / TARGET_EXTRA
        List<Map<String, Object>> sourceTxns = jdbc.queryForList(
                "SELECT trans_no, store_code, card_no, points_delta, source_seq FROM mig_raw_purchase");
        List<Map<String, Object>> targetTxns = jdbc.queryForList(
                "SELECT trans_no, store_code, owner_code, ownership_kind, issue_type, valid_flag, points_delta "
                        + "FROM target_purchase");
        diffTransactions(sourceTxns, targetTxns, diffs);

        // 4) 归属不一致：阻断交易（重叠/缺失/撤销）逐条列出
        for (Map<String, Object> t : targetTxns) {
            if ("BLOCKED".equals(t.get("ownership_kind")) && Boolean.TRUE.equals(t.get("valid_flag"))) {
                diffs.add(diff("MEMBER", String.valueOf(t.get("owner_code")), "OWNERSHIP_MISMATCH", "ROW",
                        (String) t.get("trans_no"), "交易 " + t.get("trans_no") + " 归属待人工判定（"
                                + t.get("issue_type") + "），候选与区间见判定接口"));
            }
        }

        // 5) 缺期初 & 余额差：逐会员重算 期初+逐笔有效变动
        List<Map<String, Object>> balances = jdbc.queryForList(
                "SELECT member_code, store_code, opening_points, balance_points, blocked, block_reason "
                        + "FROM target_point_balance ORDER BY member_code");
        long totalPointGap = 0;
        for (Map<String, Object> b : balances) {
            String code = (String) b.get("member_code");
            if (b.get("opening_points") == null) {
                diffs.add(diff("MEMBER", code, "MISSING_OPENING", "ROW", code,
                        "会员 " + code + " 源端无积分期初行，余额阻断，禁止造调整填平"));
                continue;
            }
            int opening = ((Number) b.get("opening_points")).intValue();
            Integer sumDelta = jdbc.queryForObject(
                    "SELECT coalesce(sum(points_delta),0) FROM target_purchase "
                            + "WHERE owner_code=? AND valid_flag=TRUE AND ownership_kind IN ('AUTO','MANUAL')",
                    Integer.class, code);
            int expected = opening + (sumDelta == null ? 0 : sumDelta);
            int actual = ((Number) b.get("balance_points")).intValue();
            if (expected != actual) {
                totalPointGap += Math.abs(expected - actual);
                diffs.add(diff("MEMBER", code, "BALANCE_GAP", "ROW", code,
                        "会员 " + code + " 余额差：期初 " + opening + " + 有效变动 " + sumDelta
                                + " = 期望 " + expected + "，新端实际 " + actual));
            }
        }

        // 6) 汇总：人数 / 交易数 / 积分差额（总数 + 门店下钻）
        Map<String, Object> summary = buildSummary(sourceMembers, targetMembers, sourceTxns, targetTxns,
                balances, appliedSeq, sourceLast);
        boolean passed = diffs.isEmpty();
        jdbc.update("INSERT INTO target_reconcile_run(id, run_time, source_last_seq, applied_seq, "
                        + "overall_passed, summary_json) VALUES (?,?,?,?,?,?)",
                runId, LocalDateTime.now(), sourceLast, appliedSeq, passed, toJson(summary));
        for (Map<String, Object> d : diffs) {
            jdbc.update("INSERT INTO target_reconcile_diff(id, run_id, scope_level, scope_key, diff_type, "
                            + "ref_kind, ref_key, detail) VALUES (nextval('app_global_seq'),?,?,?,?,?,?,?)",
                    runId, d.get("scope_level"), d.get("scope_key"), d.get("diff_type"),
                    d.get("ref_kind"), d.get("ref_key"), d.get("detail"));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("run_id", runId);
        resp.put("run_time", LocalDateTime.now().toString());
        resp.put("overall_passed", passed);
        resp.put("summary", summary);
        resp.put("diff_count", diffs.size());
        resp.put("diffs", diffs);
        return resp;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> drillStore(String storeCode) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("store_code", storeCode);
        resp.put("members", jdbc.queryForList(
                "SELECT member_code, name, phone FROM target_member WHERE store_code=? ORDER BY member_code",
                storeCode));
        resp.put("source_purchases", jdbc.queryForList(
                "SELECT trans_no, card_no, points_delta, source_seq FROM mig_raw_purchase WHERE store_code=? "
                        + "ORDER BY trans_no", storeCode));
        resp.put("target_purchases", jdbc.queryForList(
                "SELECT trans_no, card_no, owner_code, ownership_kind, issue_type, valid_flag, points_delta "
                        + "FROM target_purchase WHERE store_code=? ORDER BY trans_no", storeCode));
        resp.put("balances", jdbc.queryForList(
                "SELECT member_code, opening_points, balance_points, blocked, block_reason "
                        + "FROM target_point_balance WHERE store_code=? ORDER BY member_code", storeCode));
        return resp;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> drillMember(String memberCode) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("member", jdbc.queryForList(
                "SELECT member_code, name, phone, store_code, created_at FROM target_member WHERE member_code=?",
                memberCode));
        resp.put("source_opening", jdbc.queryForList(
                "SELECT * FROM mig_raw_opening WHERE member_code=?", memberCode));
        resp.put("owned_purchases", jdbc.queryForList(
                "SELECT trans_no, card_no, trans_time, points_delta, ownership_kind, issue_type, valid_flag, "
                        + "source_seq FROM target_purchase WHERE owner_code=? ORDER BY trans_time", memberCode));
        resp.put("candidate_pending", jdbc.queryForList(
                "SELECT p.trans_no, p.card_no, p.trans_time, p.issue_type FROM target_purchase p "
                        + "JOIN target_ownership_candidate c ON c.trans_no = p.trans_no "
                        + "WHERE c.candidate_code=? AND p.ownership_kind='BLOCKED'", memberCode));
        resp.put("ledger", jdbc.queryForList(
                "SELECT entry_type, trans_no, points_delta, balance_after, source_seq "
                        + "FROM target_point_ledger WHERE member_code=? ORDER BY id", memberCode));
        resp.put("balance", jdbc.queryForList(
                "SELECT opening_points, balance_points, blocked, block_reason, rebuilt_at "
                        + "FROM target_point_balance WHERE member_code=?", memberCode));
        return resp;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRuns() {
        return jdbc.queryForList("SELECT id, run_time, source_last_seq, applied_seq, overall_passed "
                + "FROM target_reconcile_run ORDER BY id DESC");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(long runId) {
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT * FROM target_reconcile_run WHERE id=?", runId);
        List<Map<String, Object>> diffs = jdbc.queryForList(
                "SELECT scope_level, scope_key, diff_type, ref_kind, ref_key, detail "
                        + "FROM target_reconcile_diff WHERE run_id=? ORDER BY id", runId);
        Map<String, Object> resp = new LinkedHashMap<>(run);
        resp.put("diffs", diffs);
        return resp;
    }

    // ---------------------------------------------------------------
    private void diffMembers(List<Map<String, Object>> source, List<Map<String, Object>> target,
                             List<Map<String, Object>> out) {
        Map<String, Map<String, Object>> targetMap = new LinkedHashMap<>();
        target.forEach(m -> targetMap.put((String) m.get("member_code"), m));
        for (Map<String, Object> m : source) {
            String code = (String) m.get("member_code");
            if (!targetMap.containsKey(code)) {
                out.add(diff("MEMBER", code, "SOURCE_MISSING", "ROW", code,
                        "源端会员 " + code + "（门店 " + m.get("store_code") + "）未迁入新端"));
            }
        }
        Map<String, Map<String, Object>> sourceMap = new LinkedHashMap<>();
        source.forEach(m -> sourceMap.put((String) m.get("member_code"), m));
        for (Map<String, Object> m : target) {
            String code = (String) m.get("member_code");
            if (!sourceMap.containsKey(code)) {
                out.add(diff("MEMBER", code, "TARGET_EXTRA", "ROW", code,
                        "新端多出会员 " + code + "，迁移原件区无对应原行"));
            }
        }
    }

    private void diffTransactions(List<Map<String, Object>> source, List<Map<String, Object>> target,
                                  List<Map<String, Object>> out) {
        Map<String, Map<String, Object>> targetMap = new LinkedHashMap<>();
        target.forEach(t -> targetMap.put((String) t.get("trans_no"), t));
        for (Map<String, Object> t : source) {
            String transNo = (String) t.get("trans_no");
            if (!targetMap.containsKey(transNo)) {
                out.add(diff("STORE", (String) t.get("store_code"), "SOURCE_MISSING", "ROW", transNo,
                        "源端交易 " + transNo + "（卡号 " + t.get("card_no") + "，序号 "
                                + t.get("source_seq") + "）未迁入新端"));
            }
        }
        Map<String, Map<String, Object>> sourceMap = new LinkedHashMap<>();
        source.forEach(t -> sourceMap.put((String) t.get("trans_no"), t));
        for (Map<String, Object> t : target) {
            String transNo = (String) t.get("trans_no");
            if (!sourceMap.containsKey(transNo)) {
                out.add(diff("STORE", (String) t.get("store_code"), "TARGET_EXTRA", "ROW", transNo,
                        "新端多出交易 " + transNo + "，迁移原件区无对应原行"));
            }
        }
    }

    private Map<String, Object> buildSummary(List<Map<String, Object>> sourceMembers,
                                             List<Map<String, Object>> targetMembers,
                                             List<Map<String, Object>> sourceTxns,
                                             List<Map<String, Object>> targetTxns,
                                             List<Map<String, Object>> balances,
                                             long appliedSeq, long sourceLast) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source_member_count", sourceMembers.size());
        summary.put("target_member_count", targetMembers.size());
        summary.put("source_transaction_count", sourceTxns.size());
        summary.put("target_transaction_count", targetTxns.size());
        long validTargetTxns = targetTxns.stream().filter(t -> Boolean.TRUE.equals(t.get("valid_flag"))).count();
        summary.put("target_valid_transaction_count", validTargetTxns);
        long blockedCount = targetTxns.stream()
                .filter(t -> "BLOCKED".equals(t.get("ownership_kind")) && Boolean.TRUE.equals(t.get("valid_flag")))
                .count();
        summary.put("blocked_transaction_count", blockedCount);
        long missingOpening = balances.stream().filter(b -> b.get("opening_points") == null).count();
        summary.put("missing_opening_member_count", missingOpening);
        long totalOpening = balances.stream().filter(b -> b.get("opening_points") != null)
                .mapToLong(b -> ((Number) b.get("opening_points")).longValue()).sum();
        long totalBalance = balances.stream()
                .mapToLong(b -> ((Number) b.get("balance_points")).longValue()).sum();
        summary.put("total_opening_points", totalOpening);
        summary.put("total_balance_points", totalBalance);
        summary.put("applied_seq", appliedSeq);
        summary.put("source_last_seq", sourceLast);
        summary.put("seq_backlog", sourceLast - appliedSeq);

        Map<String, Map<String, Object>> byStore = new LinkedHashMap<>();
        java.util.function.BiFunction<Object, Object, Object> longSum =
                (a, b) -> ((Long) a) + ((Long) b);
        for (Map<String, Object> m : sourceMembers) {
            byStore.computeIfAbsent((String) m.get("store_code"), k -> storeBucket(k))
                    .merge("source_members", 1L, longSum);
        }
        for (Map<String, Object> m : targetMembers) {
            byStore.computeIfAbsent((String) m.get("store_code"), k -> storeBucket(k))
                    .merge("target_members", 1L, longSum);
        }
        for (Map<String, Object> t : sourceTxns) {
            Map<String, Object> bucket = byStore.computeIfAbsent((String) t.get("store_code"),
                    k -> storeBucket(k));
            bucket.merge("source_transactions", 1L, longSum);
            bucket.merge("source_points", ((Number) t.get("points_delta")).longValue(), longSum);
        }
        for (Map<String, Object> t : targetTxns) {
            Map<String, Object> bucket = byStore.computeIfAbsent((String) t.get("store_code"),
                    k -> storeBucket(k));
            bucket.merge("target_transactions", 1L, longSum);
            if (Boolean.TRUE.equals(t.get("valid_flag"))) {
                bucket.merge("target_valid_transactions", 1L, longSum);
                bucket.merge("target_points", ((Number) t.get("points_delta")).longValue(), longSum);
            }
        }
        summary.put("by_store", new ArrayList<>(byStore.values()));
        return summary;
    }

    private Map<String, Object> storeBucket(String store) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("store_code", store);
        m.put("source_members", 0L);
        m.put("target_members", 0L);
        m.put("source_transactions", 0L);
        m.put("target_transactions", 0L);
        m.put("target_valid_transactions", 0L);
        m.put("source_points", 0L);
        m.put("target_points", 0L);
        return m;
    }

    private Map<String, Object> diff(String level, String scopeKey, String type, String refKind,
                                     String refKey, String detail) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("scope_level", level);
        d.put("scope_key", scopeKey);
        d.put("diff_type", type);
        d.put("ref_kind", refKind);
        d.put("ref_key", refKey);
        d.put("detail", detail);
        return d;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
