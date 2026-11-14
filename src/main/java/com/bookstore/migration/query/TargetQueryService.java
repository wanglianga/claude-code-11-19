package com.bookstore.migration.query;

import com.bookstore.migration.support.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新端业务查询：开放守卫。
 * 未开放时新端数据范围不完整，拒绝业务查询并指引到迁移侧报告；
 * 开放后所有查询来自同一侧（target_*）的完整数据范围。
 */
@Service
public class TargetQueryService {

    private final JdbcTemplate jdbc;

    public TargetQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isOpened() {
        Long cnt = jdbc.queryForObject(
                "SELECT count(*) FROM target_switch_request WHERE status='OPENED'", Long.class);
        return cnt != null && cnt > 0;
    }

    private void ensureOpened() {
        if (!isOpened()) {
            throw ApiException.precondition("新端尚未开放，数据范围不完整，禁止按新端对外查询；"
                    + "迁移过程请使用 /api/reports/members/{memberCode} 迁移侧报告");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> querySide() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("read_side", isOpened() ? "TARGET" : "LEGACY_PENDING_TARGET_OPEN");
        resp.put("target_opened", isOpened());
        if (isOpened()) {
            Map<String, Object> opened = jdbc.queryForMap(
                    "SELECT request_no, final_seq, opened_at FROM target_switch_request "
                            + "WHERE status='OPENED' ORDER BY id DESC LIMIT 1");
            resp.put("opened_request", opened);
        }
        return resp;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> members() {
        ensureOpened();
        return jdbc.queryForList(
                "SELECT member_code, name, phone, store_code, created_at FROM target_member ORDER BY member_code");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> purchases(String memberCode) {
        ensureOpened();
        if (memberCode == null || memberCode.isBlank()) {
            return jdbc.queryForList(
                    "SELECT trans_no, card_no, store_code, trans_time, amount, points_delta, owner_code, "
                            + "ownership_kind, valid_flag FROM target_purchase ORDER BY trans_time");
        }
        return jdbc.queryForList(
                "SELECT trans_no, card_no, store_code, trans_time, amount, points_delta, owner_code, "
                        + "ownership_kind, valid_flag FROM target_purchase WHERE owner_code=? ORDER BY trans_time",
                memberCode);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> balances() {
        ensureOpened();
        return jdbc.queryForList(
                "SELECT b.member_code, b.store_code, b.opening_points, b.balance_points, b.blocked, "
                        + "b.block_reason, m.name FROM target_point_balance b "
                        + "JOIN target_member m ON m.member_code = b.member_code ORDER BY b.member_code");
    }
}
