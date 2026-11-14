package com.bookstore.migration.ownership;

import com.bookstore.migration.support.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 持卡归属解析：按交易时刻匹配持卡有效区间。
 * - 唯一命中：自动归属 AUTO；
 * - 多段命中（区间重叠）：OVERLAP，进人工判定；
 * - 无命中（区间缺失）：MISSING，进人工判定。
 * 同手机号家庭成员在数据层始终是不同会员，本服务不做任何合并。
 */
@Service
public class OwnershipService {

    private final JdbcTemplate jdbc;

    public OwnershipService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 解析某卡号在某时刻的有效持卡候选（来自迁移原件区 mig_raw_card） */
    public List<Map<String, Object>> candidatesAt(String cardNo, LocalDateTime transTime) {
        return jdbc.queryForList("""
                SELECT card_no, holder_code, valid_from, valid_to, store_code
                FROM mig_raw_card
                WHERE card_no = ?
                  AND valid_from <= ?
                  AND (valid_to IS NULL OR valid_to >= ?)
                ORDER BY valid_from ASC, holder_code ASC
                """, cardNo, transTime, transTime);
    }

    public record Resolution(String kind, String ownerCode, String issueType,
                             List<Map<String, Object>> candidates) {
    }

    /** 不写库的纯解析，供投影、变更应用、核对复用 */
    public Resolution resolve(String cardNo, LocalDateTime transTime) {
        List<Map<String, Object>> candidates = candidatesAt(cardNo, transTime);
        if (candidates.isEmpty()) {
            return new Resolution("BLOCKED", null, "MISSING", candidates);
        }
        if (candidates.size() > 1) {
            return new Resolution("BLOCKED", null, "OVERLAP", candidates);
        }
        return new Resolution("AUTO", (String) candidates.get(0).get("holder_code"), "NONE", candidates);
    }

    /** 落候选明细到 target_ownership_candidate（先清旧候选） */
    public void saveCandidates(String transNo, List<Map<String, Object>> candidates) {
        jdbc.update("DELETE FROM target_ownership_candidate WHERE trans_no=?", transNo);
        long id = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
        for (Map<String, Object> c : candidates) {
            jdbc.update("INSERT INTO target_ownership_candidate(id, trans_no, candidate_code, card_no, valid_from, valid_to) "
                            + "VALUES (nextval('app_global_seq'),?,?,?,?,?)",
                    transNo, c.get("holder_code"), c.get("card_no"), c.get("valid_from"), c.get("valid_to"));
        }
    }

    public List<Map<String, Object>> getCandidates(String transNo) {
        return jdbc.queryForList("SELECT candidate_code, card_no, valid_from, valid_to "
                + "FROM target_ownership_candidate WHERE trans_no=? ORDER BY candidate_code", transNo);
    }

    /** 人工判定：凭补充证据选择归属；候选内选择或对区间缺失交易凭证据指定，拒绝按余额接近自动配对 */
    public Map<String, Object> decide(String transNo, String ownerCode, String evidence, String decidedBy,
                                      String validFrom, String validTo) {
        if (evidence == null || evidence.strip().length() < 5) {
            throw ApiException.badRequest("人工判定必须提供不少于 5 个字符的补充证据；系统不提供按积分余额接近自动配对");
        }
        List<Map<String, Object>> txn = jdbc.queryForList(
                "SELECT trans_no, ownership_kind, owner_code, issue_type, card_no FROM target_purchase WHERE trans_no=?",
                transNo);
        if (txn.isEmpty()) {
            throw ApiException.notFound("新端不存在该交易: " + transNo);
        }
        List<Map<String, Object>> candidates = getCandidates(transNo);
        boolean amongCandidates = candidates.stream().anyMatch(c -> ownerCode.equals(c.get("candidate_code")));
        String issueType = (String) txn.get(0).get("issue_type");
        if (!amongCandidates) {
            // 区间缺失：无任何候选区间，允许运营凭补充证据指定一位真实会员（仍须证据，且会员必须存在）
            if ("MISSING".equals(issueType)) {
                Long memberExists = jdbc.queryForObject(
                        "SELECT count(*) FROM target_member WHERE member_code=?", Long.class, ownerCode);
                if (memberExists == null || memberExists == 0) {
                    throw ApiException.precondition("区间缺失交易只能凭证据指定给已存在的会员，"
                            + ownerCode + " 不在新端会员名册");
                }
                jdbc.update("INSERT INTO target_ownership_candidate(id, trans_no, candidate_code, card_no, "
                                + "valid_from, valid_to) VALUES (nextval('app_global_seq'),?,?,?,?,?)",
                        transNo, ownerCode, txn.get(0).get("card_no"),
                        validFrom == null ? null : LocalDateTime.parse(validFrom),
                        validTo == null ? null : LocalDateTime.parse(validTo));
            } else {
                throw ApiException.precondition("选定会员不在候选持有人范围内，不能判定归属；候选："
                        + candidates.stream().map(c -> String.valueOf(c.get("candidate_code"))).toList());
            }
        }
        Long prevId = null;
        List<Map<String, Object>> prev = jdbc.queryForList(
                "SELECT id FROM target_ownership_decision WHERE trans_no=? AND superseded=FALSE ORDER BY id DESC",
                transNo);
        if (!prev.isEmpty()) {
            prevId = ((Number) prev.get(0).get("id")).longValue();
            jdbc.update("UPDATE target_ownership_decision SET superseded=TRUE WHERE id=?", prevId);
        }
        long decisionId = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
        jdbc.update("INSERT INTO target_ownership_decision(id, trans_no, decided_owner, evidence, decided_by, "
                        + "decided_at, superseded, prev_decision_id) VALUES (?,?,?,?,?,? ,FALSE,?)",
                decisionId, transNo, ownerCode, evidence.strip(), decidedBy, LocalDateTime.now(), prevId);
        String previousOwner = (String) txn.get(0).get("owner_code");
        jdbc.update("UPDATE target_purchase SET owner_code=?, ownership_kind='MANUAL', issue_type='NONE', updated_at=? "
                        + "WHERE trans_no=?",
                ownerCode, LocalDateTime.now(), transNo);
        return Map.of("trans_no", transNo, "decided_owner", ownerCode, "decision_id", decisionId,
                "previous_owner", previousOwner == null ? "" : previousOwner,
                "superseded_decision_id", prevId == null ? -1 : prevId);
    }
}
