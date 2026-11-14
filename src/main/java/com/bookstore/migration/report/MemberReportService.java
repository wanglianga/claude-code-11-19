package com.bookstore.migration.report;

import com.bookstore.migration.support.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单会员迁移报告（迁移侧，任何阶段可查）：
 * 旧记录、采用归属、余额形成过程（逐笔台账）、被阻断原因、人工判定历史。
 */
@Service
public class MemberReportService {

    private final JdbcTemplate jdbc;

    public MemberReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> memberReport(String memberCode) {
        List<Map<String, Object>> members = jdbc.queryForList(
                "SELECT * FROM target_member WHERE member_code=?", memberCode);
        List<Map<String, Object>> rawMembers = jdbc.queryForList(
                "SELECT * FROM mig_raw_member WHERE member_code=?", memberCode);
        if (members.isEmpty() && rawMembers.isEmpty()) {
            throw ApiException.notFound("会员不存在: " + memberCode);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("member_code", memberCode);
        resp.put("target_member", members);
        resp.put("legacy_raw_member", rawMembers);

        // 旧端原件中该会员持有的卡片区间
        resp.put("legacy_cards", jdbc.queryForList(
                "SELECT card_no, holder_code, valid_from, valid_to, store_code, source_seq "
                        + "FROM mig_raw_card WHERE holder_code=? ORDER BY valid_from", memberCode));

        // 旧记录：源端按卡号关联的全部交易原件（含尚未归属给本人的）
        resp.put("legacy_purchases_by_cards", jdbc.queryForList("""
                SELECT p.trans_no, p.card_no, p.store_code, p.trans_time, p.amount, p.points_delta, p.source_seq
                FROM mig_raw_purchase p
                WHERE p.card_no IN (SELECT card_no FROM mig_raw_card WHERE holder_code=?)
                ORDER BY p.trans_time
                """, memberCode));

        // 采用归属：新端采纳为本人的交易
        resp.put("adopted_purchases", jdbc.queryForList(
                "SELECT trans_no, card_no, trans_time, points_delta, ownership_kind, issue_type, valid_flag, "
                        + "source_seq, linked_trans_no FROM target_purchase WHERE owner_code=? ORDER BY trans_time",
                memberCode));

        // 被阻断原因：本人作为候选但归属未决的交易
        resp.put("blocked_as_candidate", jdbc.queryForList("""
                SELECT p.trans_no, p.card_no, p.trans_time, p.issue_type, p.ownership_kind,
                       c.valid_from, c.valid_to
                FROM target_purchase p
                JOIN target_ownership_candidate c ON c.trans_no = p.trans_no
                WHERE c.candidate_code = ? AND p.ownership_kind = 'BLOCKED' AND p.valid_flag = TRUE
                ORDER BY p.trans_time
                """, memberCode));

        // 人工判定历史（含已被取代的原判定，仍可查）
        resp.put("ownership_decisions", jdbc.queryForList(
                "SELECT trans_no, decided_owner, evidence, decided_by, decided_at, superseded, prev_decision_id "
                        + "FROM target_ownership_decision WHERE trans_no IN "
                        + "(SELECT trans_no FROM target_ownership_candidate WHERE candidate_code=?) "
                        + "ORDER BY decided_at", memberCode));

        // 余额形成过程：期初 + 逐笔有效变动
        resp.put("point_ledger", jdbc.queryForList(
                "SELECT entry_type, trans_no, points_delta, balance_after, source_seq, created_at "
                        + "FROM target_point_ledger WHERE member_code=? ORDER BY id", memberCode));
        resp.put("balance", jdbc.queryForList(
                "SELECT opening_points, balance_points, blocked, block_reason, rebuilt_at "
                        + "FROM target_point_balance WHERE member_code=?", memberCode));
        return resp;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> blockedList() {
        List<Map<String, Object>> txns = jdbc.queryForList("""
                SELECT p.trans_no, p.card_no, p.store_code, p.trans_time, p.issue_type
                FROM target_purchase p
                WHERE p.ownership_kind='BLOCKED' AND p.valid_flag=TRUE
                ORDER BY p.trans_time
                """);
        for (Map<String, Object> txn : txns) {
            txn.put("candidates", jdbc.queryForList(
                    "SELECT candidate_code, card_no, valid_from, valid_to "
                            + "FROM target_ownership_candidate WHERE trans_no=? ORDER BY candidate_code",
                    txn.get("trans_no")));
        }
        return txns;
    }
}
