package com.bookstore.migration.web;

import com.bookstore.migration.ownership.OwnershipService;
import com.bookstore.migration.projection.OpeningEvidenceService;
import com.bookstore.migration.projection.TargetProjectionService;
import com.bookstore.migration.report.MemberReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 人工判定：候选持有人/持卡起止/涉及交易，凭补充证据选择，原判定留痕可查 */
@RestController
@RequestMapping("/api/ownership")
@Tag(name = "04-归属判定", description = "持卡区间匹配、候选列表与人工证据判定")
@Validated
public class OwnershipController {

    private final OwnershipService ownership;
    private final TargetProjectionService projection;
    private final MemberReportService reports;
    private final JdbcTemplate jdbc;
    private final OpeningEvidenceService openingEvidence;

    public OwnershipController(OwnershipService ownership, TargetProjectionService projection,
                               MemberReportService reports, JdbcTemplate jdbc,
                               OpeningEvidenceService openingEvidence) {
        this.ownership = ownership;
        this.projection = projection;
        this.reports = reports;
        this.jdbc = jdbc;
        this.openingEvidence = openingEvidence;
    }

    public record DecisionRequest(@NotBlank String trans_no,
                                  @NotBlank String owner_code,
                                  @NotBlank @Size(min = 5, message = "补充证据至少 5 个字符") String evidence,
                                  @NotBlank String decided_by,
                                  String valid_from,
                                  String valid_to) {
    }

    public record OpeningEvidenceRequest(@NotBlank String member_code,
                                         @NotNull Integer opening_points,
                                         @NotBlank @Size(min = 5, message = "补充证据至少 5 个字符") String evidence,
                                         @NotBlank String recorded_by) {
    }

    @GetMapping("/blocked")
    @Operation(summary = "全部待判定交易（重叠/缺失/撤销），含候选持卡起止")
    public List<Map<String, Object>> blocked() {
        return reports.blockedList();
    }

    @GetMapping("/transactions/{transNo}/candidates")
    @Operation(summary = "单笔交易的候选持有人、持卡起止区间与交易明细")
    public Map<String, Object> candidates(@PathVariable String transNo) {
        List<Map<String, Object>> txn = jdbc.queryForList(
                "SELECT trans_no, card_no, store_code, trans_time, amount, points_delta, issue_type, "
                        + "ownership_kind, valid_flag FROM target_purchase WHERE trans_no=?", transNo);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("transaction", txn);
        resp.put("candidates", ownership.getCandidates(transNo));
        resp.put("decisions", jdbc.queryForList(
                "SELECT id, decided_owner, evidence, decided_by, decided_at, superseded, prev_decision_id "
                        + "FROM target_ownership_decision WHERE trans_no=? ORDER BY id", transNo));
        return resp;
    }

    @PostMapping("/decisions")
    @Operation(summary = "人工判定：只能选择候选持有人且必须附补充证据；修正后局部重算，原判定可查")
    public Map<String, Object> decide(@Validated @RequestBody DecisionRequest req) {
        Map<String, Object> result = ownership.decide(req.trans_no(), req.owner_code(),
                req.evidence(), req.decided_by(), req.valid_from(), req.valid_to());
        String previousOwner = String.valueOf(result.getOrDefault("previous_owner", ""));
        // 修正后仅重算受影响交易涉及的会员（原归属 + 新归属）
        projection.recomputeAfterDecision(req.trans_no(),
                previousOwner.isBlank() ? null : previousOwner, req.owner_code());
        return result;
    }

    @GetMapping("/decisions/{transNo}")
    @Operation(summary = "某交易全部判定历史（含已被取代的原判定）")
    public List<Map<String, Object>> decisionHistory(@PathVariable String transNo) {
        return jdbc.queryForList("SELECT id, trans_no, decided_owner, evidence, decided_by, decided_at, "
                + "superseded, prev_decision_id FROM target_ownership_decision WHERE trans_no=? ORDER BY id", transNo);
    }

    @PostMapping("/openings/evidence")
    @Operation(summary = "缺期初处置：凭补充证据补录真实期初（留痕，非调整填平）并重算余额")
    public Map<String, Object> recordOpening(@Validated @RequestBody OpeningEvidenceRequest req) {
        return openingEvidence.recordOpening(req.member_code(), req.opening_points(),
                req.evidence(), req.recorded_by());
    }

    @GetMapping("/openings/evidence/{memberCode}")
    @Operation(summary = "某会员的期初补录证据历史")
    public List<Map<String, Object>> openingEvidence(@PathVariable String memberCode) {
        return openingEvidence.evidenceHistory(memberCode);
    }
}
