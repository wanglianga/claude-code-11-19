package com.bookstore.migration.bootstrap;

import com.bookstore.migration.legacy.LegacyDataSeeder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全量重置：清掉迁移侧与新端全部产物并重新播种旧端样例，
 * 供端到端演示与自动化测试反复使用。旧端原件、迁移结果、新端数据三层全部回到初始状态。
 */
@Service
public class ResetService {

    private final JdbcTemplate jdbc;
    private final LegacyDataSeeder seeder;

    public ResetService(JdbcTemplate jdbc, LegacyDataSeeder seeder) {
        this.jdbc = jdbc;
        this.seeder = seeder;
    }

    @Transactional
    public Map<String, Object> resetAll() {
        jdbc.update("TRUNCATE target_switch_stage_log, target_switch_request, target_reconcile_diff, "
                + "target_reconcile_run, target_point_ledger, target_point_balance, target_ownership_decision, "
                + "target_ownership_candidate, target_purchase, target_card, target_member, "
                + "mig_chunk_cursor, mig_source_progress, mig_change_event, mig_raw_opening, mig_raw_purchase, "
                + "mig_raw_card, mig_raw_member, mig_opening_evidence, mig_snapshot_section, mig_snapshot_manifest "
                + "RESTART IDENTITY CASCADE");
        seeder.reseed();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("reset", true);
        resp.put("legacy_members", jdbc.queryForObject("SELECT count(*) FROM legacy_member", Long.class));
        resp.put("legacy_cards", jdbc.queryForObject("SELECT count(*) FROM legacy_card", Long.class));
        resp.put("legacy_purchases", jdbc.queryForObject("SELECT count(*) FROM legacy_purchase", Long.class));
        resp.put("legacy_openings", jdbc.queryForObject("SELECT count(*) FROM legacy_point_opening", Long.class));
        resp.put("legacy_events", jdbc.queryForObject("SELECT count(*) FROM legacy_change_event", Long.class));
        resp.put("snapshot_cutoff_seq", LegacyDataSeeder.SNAPSHOT_CUTOFF_SEQ);
        return resp;
    }
}
