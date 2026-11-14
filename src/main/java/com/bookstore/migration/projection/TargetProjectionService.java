package com.bookstore.migration.projection;

import com.bookstore.migration.ownership.OwnershipService;
import com.bookstore.migration.support.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 新端投影与积分余额重建。
 * 余额 = 期初 + 逐笔有效变动；缺期初只标记阻断，绝不造 ADJUST 填平。
 * 归属修正后仅重算受影响会员（原归属 + 新归属）。
 */
@Service
public class TargetProjectionService {

    private final JdbcTemplate jdbc;
    private final OwnershipService ownership;

    public TargetProjectionService(JdbcTemplate jdbc, OwnershipService ownership) {
        this.jdbc = jdbc;
        this.ownership = ownership;
    }

    // =========================================================
    // 基线投影：快照原件 -> 新端
    // =========================================================
    @Transactional
    public Map<String, Object> projectBaseline() {
        jdbc.update("TRUNCATE target_point_ledger, target_point_balance, target_ownership_candidate, "
                + "target_ownership_decision, target_purchase, target_card, target_member RESTART IDENTITY CASCADE");

        List<Map<String, Object>> members = jdbc.queryForList(
                "SELECT member_code, name, phone, store_code, created_at FROM mig_raw_member WHERE source_seq=0");
        for (Map<String, Object> m : members) {
            long id = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
            jdbc.update("INSERT INTO target_member(id, member_code, name, phone, store_code, created_at) "
                            + "VALUES (?,?,?,?,?,?)",
                    id, m.get("member_code"), m.get("name"), m.get("phone"), m.get("store_code"), m.get("created_at"));
        }
        List<Map<String, Object>> cards = jdbc.queryForList(
                "SELECT card_no, holder_code, valid_from, valid_to, store_code FROM mig_raw_card WHERE source_seq=0");
        for (Map<String, Object> c : cards) {
            long id = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
            jdbc.update("INSERT INTO target_card(id, card_no, holder_code, valid_from, valid_to, store_code) "
                            + "VALUES (?,?,?,?,?,?)",
                    id, c.get("card_no"), c.get("holder_code"), c.get("valid_from"), c.get("valid_to"),
                    c.get("store_code"));
        }
        List<Map<String, Object>> purchases = jdbc.queryForList(
                "SELECT trans_no, card_no, store_code, trans_time, amount, points_delta "
                        + "FROM mig_raw_purchase WHERE source_seq=0 ORDER BY trans_time, trans_no");
        for (Map<String, Object> p : purchases) {
            insertTargetPurchase(p, 0L, true, null);
        }
        rebuildAllBalances();
        return Map.of("members", members.size(), "cards", cards.size(), "purchases", purchases.size(),
                "projected", true);
    }

    /** 插入/更新一笔新端交易并解析归属。入参时间允许 String 或 LocalDateTime（JSON 事件/数据库行两种来源） */
    public void insertTargetPurchase(Map<String, Object> p, long sourceSeq, boolean valid, String linkedTransNo) {
        String transNo = (String) p.get("trans_no");
        String cardNo = (String) p.get("card_no");
        LocalDateTime transTime = toLdt(p.get("trans_time"));
        OwnershipService.Resolution r = ownership.resolve(cardNo, transTime);
        ownership.saveCandidates(transNo, r.candidates());

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM target_purchase WHERE trans_no=?", transNo);
        java.math.BigDecimal amount = p.get("amount") instanceof java.math.BigDecimal bd
                ? bd : new java.math.BigDecimal(String.valueOf(p.get("amount")));
        if (existing.isEmpty()) {
            long id = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
            jdbc.update("INSERT INTO target_purchase(id, trans_no, card_no, store_code, trans_time, amount, "
                            + "points_delta, owner_code, ownership_kind, issue_type, source_seq, valid_flag, "
                            + "linked_trans_no, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, transNo, cardNo, p.get("store_code"), transTime, amount,
                    p.get("points_delta"), r.ownerCode(), r.kind(), r.issueType(), sourceSeq, valid,
                    linkedTransNo, LocalDateTime.now());
        } else {
            jdbc.update("UPDATE target_purchase SET card_no=?, store_code=?, trans_time=?, amount=?, "
                            + "points_delta=?, owner_code=?, ownership_kind=?, issue_type=?, source_seq=?, "
                            + "valid_flag=?, linked_trans_no=?, updated_at=? WHERE trans_no=?",
                    cardNo, p.get("store_code"), transTime, amount, p.get("points_delta"),
                    r.ownerCode(), r.kind(), r.issueType(), sourceSeq, valid, linkedTransNo,
                    LocalDateTime.now(), transNo);
        }
    }

    // =========================================================
    // 变更事件应用（由 ChangeStreamService 调用）
    // =========================================================
    @Transactional
    public Set<String> applyPurchaseUpsert(Map<String, Object> payload, long seq) {
        String transNo = (String) payload.get("trans_no");
        // 原件区登记
        jdbc.update("INSERT INTO mig_raw_purchase(id, trans_no, card_no, store_code, trans_time, amount, "
                        + "points_delta, source_seq) VALUES (nextval('app_global_seq'),?,?,?,?,?,?,?) "
                        + "ON CONFLICT (trans_no) DO UPDATE SET card_no=EXCLUDED.card_no, store_code=EXCLUDED.store_code, "
                        + "trans_time=EXCLUDED.trans_time, amount=EXCLUDED.amount, points_delta=EXCLUDED.points_delta, "
                        + "source_seq=EXCLUDED.source_seq",
                transNo, payload.get("card_no"), payload.get("store_code"),
                toLdt(payload.get("trans_time")),
                new java.math.BigDecimal((String) payload.get("amount")),
                ((Number) payload.get("points_delta")).intValue(), seq);
        String oldOwner = querySingleString(
                "SELECT owner_code FROM target_purchase WHERE trans_no=?", transNo);
        insertTargetPurchase(payload, seq, true, null);
        String newOwner = querySingleString(
                "SELECT owner_code FROM target_purchase WHERE trans_no=?", transNo);
        Set<String> affected = new LinkedHashSet<>();
        if (oldOwner != null) {
            affected.add(oldOwner);
        }
        if (newOwner != null) {
            affected.add(newOwner);
        }
        if (affected.isEmpty()) {
            // 归属阻断的交易：候选会员也要重算阻断状态
            ownership.getCandidates(transNo).forEach(c -> affected.add((String) c.get("candidate_code")));
        }
        rebuildBalances(affected);
        return affected;
    }

    @Transactional
    public Set<String> applyPurchaseDelete(String transNo, long seq) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT owner_code FROM target_purchase WHERE trans_no=?", transNo);
        Set<String> affected = new LinkedHashSet<>();
        if (!rows.isEmpty()) {
            String owner = (String) rows.get(0).get("owner_code");
            if (owner != null) {
                affected.add(owner);
            }
            jdbc.update("UPDATE target_purchase SET valid_flag=FALSE, ownership_kind='BLOCKED', issue_type='REVERSED', "
                            + "source_seq=?, updated_at=? WHERE trans_no=?",
                    seq, LocalDateTime.now(), transNo);
        }
        rebuildBalances(affected);
        return affected;
    }

    @Transactional
    public void applyMemberUpsert(Map<String, Object> payload, long seq) {
        String code = (String) payload.get("member_code");
        List<Map<String, Object>> exists = jdbc.queryForList(
                "SELECT id FROM mig_raw_member WHERE member_code=?", code);
        if (exists.isEmpty()) {
            jdbc.update("INSERT INTO mig_raw_member(id, member_code, name, phone, store_code, created_at, source_seq) "
                            + "VALUES (nextval('app_global_seq'),?,?,?,?,?,?)",
                    code, payload.get("name"), payload.get("phone"), payload.get("store_code"),
                    toLdt(payload.get("created_at")), seq);
            jdbc.update("INSERT INTO target_member(id, member_code, name, phone, store_code, created_at) "
                            + "VALUES (nextval('app_global_seq'),?,?,?,?,?)",
                    code, payload.get("name"), payload.get("phone"), payload.get("store_code"),
                    toLdt(payload.get("created_at")));
        } else {
            jdbc.update("UPDATE mig_raw_member SET name=?, phone=?, store_code=?, source_seq=? WHERE member_code=?",
                    payload.get("name"), payload.get("phone"), payload.get("store_code"), seq, code);
            jdbc.update("UPDATE target_member SET name=?, phone=?, store_code=? WHERE member_code=?",
                    payload.get("name"), payload.get("phone"), payload.get("store_code"), code);
        }
    }

    @Transactional
    public void applyCardUpsert(Map<String, Object> payload, long seq) {
        String cardNo = (String) payload.get("card_no");
        String holder = (String) payload.get("holder_code");
        LocalDateTime from = toLdt(payload.get("valid_from"));
        LocalDateTime to = toLdt(payload.get("valid_to"));
        jdbc.update("INSERT INTO mig_raw_card(id, card_no, holder_code, valid_from, valid_to, store_code, source_seq) "
                        + "VALUES (nextval('app_global_seq'),?,?,?,?,?,?) "
                        + "ON CONFLICT (card_no, holder_code, valid_from) DO UPDATE SET valid_to=EXCLUDED.valid_to, "
                        + "store_code=EXCLUDED.store_code, source_seq=EXCLUDED.source_seq",
                cardNo, holder, from, to, payload.get("store_code"), seq);
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM target_card WHERE card_no=? AND holder_code=? AND valid_from=?",
                cardNo, holder, from);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO target_card(id, card_no, holder_code, valid_from, valid_to, store_code) "
                            + "VALUES (nextval('app_global_seq'),?,?,?,?,?)",
                    cardNo, holder, from, to, payload.get("store_code"));
        } else {
            jdbc.update("UPDATE target_card SET valid_to=?, store_code=? WHERE id=?",
                    to, payload.get("store_code"), existing.get(0).get("id"));
        }
        // 新持卡区间可能改变历史交易归属：重算该卡全部交易
        reResolveCardPurchases(cardNo);
    }

    @Transactional
    public void applyOpeningUpsert(Map<String, Object> payload, long seq) {
        String memberCode = (String) payload.get("member_code");
        jdbc.update("INSERT INTO mig_raw_opening(id, member_code, store_code, opening_points, source_seq) "
                        + "VALUES (nextval('app_global_seq'),?,?,?,?) "
                        + "ON CONFLICT (member_code) DO UPDATE SET opening_points=EXCLUDED.opening_points, "
                        + "store_code=EXCLUDED.store_code, source_seq=EXCLUDED.source_seq",
                memberCode, payload.get("store_code"),
                ((Number) payload.get("opening_points")).intValue(), seq);
        rebuildBalances(Set.of(memberCode));
    }

    /** 卡片区间变化后重算该卡相关交易的归属，并局部重算余额 */
    private void reResolveCardPurchases(String cardNo) {
        List<Map<String, Object>> txns = jdbc.queryForList(
                "SELECT trans_no, card_no, store_code, trans_time, amount, points_delta, owner_code "
                        + "FROM target_purchase WHERE card_no=? ORDER BY trans_time, trans_no", cardNo);
        Set<String> affected = new LinkedHashSet<>();
        for (Map<String, Object> p : txns) {
            String oldOwner = (String) p.get("owner_code");
            if (oldOwner != null) {
                affected.add(oldOwner);
            }
            // 已有人工判定的交易不自动覆盖
            Long manual = jdbc.queryForObject(
                    "SELECT count(*) FROM target_ownership_decision WHERE trans_no=? AND superseded=FALSE",
                    Long.class, p.get("trans_no"));
            if (manual != null && manual > 0) {
                continue;
            }
            OwnershipService.Resolution r = ownership.resolve((String) p.get("card_no"),
                    (LocalDateTime) p.get("trans_time"));
            ownership.saveCandidates((String) p.get("trans_no"), r.candidates());
            jdbc.update("UPDATE target_purchase SET owner_code=?, ownership_kind=?, issue_type=?, updated_at=? "
                            + "WHERE trans_no=?",
                    r.ownerCode(), r.kind(), r.issueType(), LocalDateTime.now(), p.get("trans_no"));
            if (r.ownerCode() != null) {
                affected.add(r.ownerCode());
            }
            r.candidates().forEach(c -> affected.add((String) c.get("holder_code")));
        }
        rebuildBalances(affected);
    }

    // =========================================================
    // 余额重建
    // =========================================================
    @Transactional
    public void rebuildAllBalances() {
        List<String> codes = jdbc.queryForList("SELECT member_code FROM target_member ORDER BY member_code",
                String.class);
        rebuildBalances(new LinkedHashSet<>(codes));
    }

    @Transactional
    public void rebuildBalances(Set<String> memberCodes) {
        for (String memberCode : memberCodes) {
            List<Map<String, Object>> memberRows = jdbc.queryForList(
                    "SELECT store_code FROM target_member WHERE member_code=?", memberCode);
            String store = memberRows.isEmpty() ? "UNKNOWN" : (String) memberRows.get(0).get("store_code");

            List<Map<String, Object>> openingRows = jdbc.queryForList(
                    "SELECT opening_points FROM mig_raw_opening WHERE member_code=?", memberCode);
            Integer opening = openingRows.isEmpty() ? null
                    : ((Number) openingRows.get(0).get("opening_points")).intValue();

            // 逐笔有效变动：已采纳归属且未撤销；阻断中的交易不计入
            List<Map<String, Object>> entries = jdbc.queryForList("""
                    SELECT trans_no, points_delta, source_seq, trans_time
                    FROM target_purchase
                    WHERE owner_code = ? AND valid_flag = TRUE AND ownership_kind IN ('AUTO','MANUAL')
                    ORDER BY trans_time, trans_no
                    """, memberCode);
            List<Map<String, Object>> blockedTxns = jdbc.queryForList("""
                    SELECT trans_no, issue_type, ownership_kind
                    FROM target_purchase
                    WHERE valid_flag = TRUE AND ownership_kind = 'BLOCKED'
                      AND trans_no IN (
                        SELECT trans_no FROM target_ownership_candidate WHERE candidate_code = ?)
                    """, memberCode);

            jdbc.update("DELETE FROM target_point_ledger WHERE member_code=?", memberCode);
            int running = 0;
            if (opening != null) {
                long ledgerId = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
                running = opening;
                jdbc.update("INSERT INTO target_point_ledger(id, member_code, entry_type, trans_no, points_delta, "
                                + "balance_after, source_seq, created_at) VALUES (?,?, 'OPENING', NULL, ?, ?, 0, ?)",
                        ledgerId, memberCode, opening, running, LocalDateTime.now());
            }
            for (Map<String, Object> e : entries) {
                long ledgerId = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
                int delta = ((Number) e.get("points_delta")).intValue();
                running += delta;
                jdbc.update("INSERT INTO target_point_ledger(id, member_code, entry_type, trans_no, points_delta, "
                                + "balance_after, source_seq, created_at) VALUES (?,?, 'PURCHASE', ?, ?, ?, ?, ?)",
                        ledgerId, memberCode, e.get("trans_no"), delta, running,
                        ((Number) e.get("source_seq")).longValue(), LocalDateTime.now());
            }

            boolean blocked = false;
            List<String> reasons = new ArrayList<>();
            if (opening == null) {
                blocked = true;
                reasons.add("MISSING_OPENING：源端无积分期初行，不造调整填平，余额不可信");
            }
            if (!blockedTxns.isEmpty()) {
                blocked = true;
                reasons.add("BLOCKED_TXN：存在待人工判定交易 "
                        + blockedTxns.stream().map(t -> t.get("trans_no") + "/" + t.get("issue_type")).toList());
            }

            jdbc.update("INSERT INTO target_point_balance(member_code, store_code, opening_points, balance_points, "
                            + "rebuilt_at, blocked, block_reason) VALUES (?,?,?,?,?,?,?) "
                            + "ON CONFLICT (member_code) DO UPDATE SET store_code=EXCLUDED.store_code, "
                            + "opening_points=EXCLUDED.opening_points, balance_points=EXCLUDED.balance_points, "
                            + "rebuilt_at=EXCLUDED.rebuilt_at, blocked=EXCLUDED.blocked, "
                            + "block_reason=EXCLUDED.block_reason",
                    memberCode, store, opening, running, LocalDateTime.now(), blocked,
                    reasons.isEmpty() ? null : String.join("; ", reasons));
        }
    }

    /** 人工判定后：仅重算原归属与新归属两名会员 */
    @Transactional
    public void recomputeAfterDecision(String transNo, String previousOwner, String newOwner) {
        Set<String> affected = new LinkedHashSet<>();
        if (previousOwner != null && !previousOwner.isBlank()) {
            affected.add(previousOwner);
        }
        affected.add(newOwner);
        rebuildBalances(affected);
    }

    public void ensureBaselineProjected() {
        Long cnt = jdbc.queryForObject("SELECT count(*) FROM target_member", Long.class);
        if (cnt == null || cnt == 0) {
            throw ApiException.precondition("新端基线尚未投影，请先执行基线投影");
        }
    }

    private String querySingleString(String sql, Object... args) {
        List<String> values = jdbc.queryForList(sql, String.class, args);
        return values.isEmpty() ? null : values.get(0);
    }

    static LocalDateTime toLdt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        return LocalDateTime.parse(String.valueOf(v).replace(' ', 'T'));
    }
}
