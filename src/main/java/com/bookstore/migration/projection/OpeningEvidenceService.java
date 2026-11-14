package com.bookstore.migration.projection;

import com.bookstore.migration.support.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 缺期初处置：停业门店只留下积分期初数时，允许运营凭补充证据补录“真实期初”。
 * 这不是凭空 ADJUST 填平——必须附证据、全程留痕，且只写入 OPENING 台账。
 */
@Service
public class OpeningEvidenceService {

    private final JdbcTemplate jdbc;
    private final TargetProjectionService projection;

    public OpeningEvidenceService(JdbcTemplate jdbc, TargetProjectionService projection) {
        this.jdbc = jdbc;
        this.projection = projection;
    }

    @Transactional
    public Map<String, Object> recordOpening(String memberCode, int openingPoints,
                                             String evidence, String recordedBy) {
        if (evidence == null || evidence.strip().length() < 5) {
            throw ApiException.badRequest("补录期初必须提供不少于 5 个字符的补充证据，禁止无证据造调整填平");
        }
        List<Map<String, Object>> members = jdbc.queryForList(
                "SELECT store_code FROM target_member WHERE member_code=?", memberCode);
        if (members.isEmpty()) {
            throw ApiException.notFound("会员不存在: " + memberCode);
        }
        String store = (String) members.get(0).get("store_code");
        long evidenceId = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO mig_opening_evidence(id, member_code, opening_points, evidence, recorded_by, "
                        + "recorded_at) VALUES (?,?,?,?,?,?)",
                evidenceId, memberCode, openingPoints, evidence.strip(), recordedBy, now);
        jdbc.update("INSERT INTO mig_raw_opening(id, member_code, store_code, opening_points, source_seq, "
                        + "evidence, recorded_by) VALUES (nextval('app_global_seq'),?,?,?,0,?,?) "
                        + "ON CONFLICT (member_code) DO UPDATE SET opening_points=EXCLUDED.opening_points, "
                        + "evidence=EXCLUDED.evidence, recorded_by=EXCLUDED.recorded_by",
                memberCode, store, openingPoints, evidence.strip(), recordedBy);
        projection.rebuildBalances(Set.of(memberCode));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("member_code", memberCode);
        resp.put("opening_points", openingPoints);
        resp.put("evidence_id", evidenceId);
        resp.put("recorded_at", now.toString());
        resp.put("note", "真实期初已凭证据补录并重算余额；非调整填平，证据留痕可查");
        return resp;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> evidenceHistory(String memberCode) {
        return jdbc.queryForList(
                "SELECT id, member_code, opening_points, evidence, recorded_by, recorded_at "
                        + "FROM mig_opening_evidence WHERE member_code=? ORDER BY id", memberCode);
    }
}
