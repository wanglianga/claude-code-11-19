package com.bookstore.migration.legacy;

import com.bookstore.migration.support.ApiException;
import com.bookstore.migration.support.Hashes;
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
 * 模拟旧端：
 * - 在同一逻辑截点（同一事务、同一 snapshot_time、同一 cutoff_seq）导出会员/卡片/余额/交易快照；
 * - 提供有递增序号的变更事件拉取；
 * - 继续接受营业写入（停写确认后拒绝）；
 * - 支持断档补齐、同序号不同内容的冲突演示。
 */
@Service
public class LegacySimulatorService {

    public static final LocalDateTime SNAPSHOT_TIME = LocalDateTime.of(2025, 7, 1, 0, 0);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LegacySimulatorService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------
    // 快照：同一逻辑截点导出，会员/卡片/余额不允许取不同营业时刻
    // ---------------------------------------------------------------
    @Transactional(readOnly = true)
    public Map<String, Object> exportManifest() {
        // 快照明确截止序号：基线仅含序号 <=100 的原件，其后全部走变更流
        long cutoff = LegacyDataSeeder.SNAPSHOT_CUTOFF_SEQ;
        List<Map<String, Object>> sections = new ArrayList<>();
        long totalRows = 0;
        for (SectionDef def : SectionDef.values()) {
            List<Map<String, Object>> rows = loadSectionRows(def, Long.MAX_VALUE, 0L);
            String sectionHash = Hashes.sha256(rows);
            Map<String, Object> summary = summarize(def, rows);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("section_name", def.name());
            s.put("ordinal", def.ordinal());
            s.put("expected_rows", (long) rows.size());
            s.put("section_summary", summary);
            s.put("section_hash", sectionHash);
            sections.add(s);
            totalRows += rows.size();
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("source_id", LegacyDataSeeder.SOURCE_ID);
        manifest.put("snapshot_time", SNAPSHOT_TIME.toString());
        manifest.put("cutoff_seq", cutoff);
        manifest.put("total_sections", sections.size());
        manifest.put("total_rows", totalRows);
        manifest.put("sections", sections);
        manifest.put("checksum", Hashes.sha256(sections));
        return manifest;
    }

    /** 分段取数：按 id 顺序游标分页，保证可重复读、可续传 */
    @Transactional(readOnly = true)
    public Map<String, Object> fetchSectionRows(String sectionName, long afterId, int limit) {
        SectionDef def;
        try {
            def = SectionDef.valueOf(sectionName);
        } catch (Exception e) {
            throw ApiException.badRequest("未知快照分段: " + sectionName);
        }
        List<Map<String, Object>> rows = loadSectionRows(def, limit, afterId);
        long lastId = rows.isEmpty() ? afterId : ((Number) rows.get(rows.size() - 1).get("id")).longValue();
        long total = jdbc.queryForObject("SELECT count(*) FROM " + def.table
                + " WHERE updated_seq <= " + LegacyDataSeeder.SNAPSHOT_CUTOFF_SEQ, Long.class);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("section_name", def.name());
        resp.put("rows", rows);
        resp.put("last_id", lastId);
        resp.put("limit", limit);
        resp.put("has_more", lastId < (total == 0 ? 0 : maxId(def)));
        resp.put("total_rows", total);
        return resp;
    }

    private List<Map<String, Object>> loadSectionRows(SectionDef def, long limit, long afterId) {
        // 仅取快照截止序号之前的原件，变更引入的行（updated_seq>cutoff）不进基线
        String sql = "SELECT " + def.columns + " FROM " + def.table
                + " WHERE id > ? AND updated_seq <= " + LegacyDataSeeder.SNAPSHOT_CUTOFF_SEQ
                + " ORDER BY id ASC" + (limit == Long.MAX_VALUE ? "" : " LIMIT " + limit);
        return jdbc.queryForList(sql, afterId);
    }

    private long maxId(SectionDef def) {
        return jdbc.queryForObject("SELECT coalesce(max(id),0) FROM " + def.table
                + " WHERE updated_seq <= " + LegacyDataSeeder.SNAPSHOT_CUTOFF_SEQ, Long.class);
    }

    private Map<String, Object> summarize(SectionDef def, List<Map<String, Object>> rows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rows", rows.size());
        switch (def) {
            case MEMBER -> {
                summary.put("distinct_store_count", rows.stream().map(r -> r.get("store_code")).distinct().count());
                summary.put("same_phone_groups", countSamePhoneGroups(rows));
            }
            case CARD -> summary.put("distinct_card_count",
                    rows.stream().map(r -> r.get("card_no")).distinct().count());
            case PURCHASE -> {
                long points = rows.stream().mapToLong(r -> ((Number) r.get("points_delta")).longValue()).sum();
                summary.put("total_points_delta", points);
                summary.put("distinct_card_count", rows.stream().map(r -> r.get("card_no")).distinct().count());
            }
            case OPENING -> {
                long points = rows.stream().mapToLong(r -> ((Number) r.get("opening_points")).longValue()).sum();
                summary.put("total_opening_points", points);
            }
        }
        return summary;
    }

    private long countSamePhoneGroups(List<Map<String, Object>> members) {
        Map<Object, Long> phoneCount = new LinkedHashMap<>();
        members.stream().filter(m -> m.get("phone") != null)
                .forEach(m -> phoneCount.merge(m.get("phone"), 1L, Long::sum));
        return phoneCount.values().stream().filter(c -> c > 1).count();
    }

    // ---------------------------------------------------------------
    // 变更事件：严格按序号拉取
    // ---------------------------------------------------------------
    @Transactional(readOnly = true)
    public List<Map<String, Object>> fetchChanges(long afterSeq, int limit) {
        return jdbc.queryForList("SELECT seq, source_id, entity_type, entity_key, op, payload, content_hash, "
                + "business_time, linked_trans_no FROM legacy_change_event WHERE seq > ? ORDER BY seq ASC LIMIT ?",
                afterSeq, limit);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> sourceState() {
        return jdbc.queryForMap("SELECT source_id, last_seq, frozen, frozen_seq, frozen_at "
                + "FROM legacy_source_state WHERE source_id=?", LegacyDataSeeder.SOURCE_ID);
    }

    // ---------------------------------------------------------------
    // 旧端继续营业：新增一笔购书并登记同序号变更；停写后拒绝
    // ---------------------------------------------------------------
    @Transactional
    public Map<String, Object> acceptBusinessPurchase(String transNo, String cardNo, String store,
                                                      LocalDateTime transTime, String amount, int points) {
        Map<String, Object> state = sourceState();
        if (Boolean.TRUE.equals(state.get("frozen"))) {
            throw ApiException.conflict("旧端已确认停写（frozen_seq=" + state.get("frozen_seq")
                    + "），禁止继续接收新营业写入");
        }
        long seq = jdbc.queryForObject("SELECT last_seq + 1 FROM legacy_source_state WHERE source_id=?",
                Long.class, LegacyDataSeeder.SOURCE_ID);
        long purchaseId = jdbc.queryForObject("SELECT coalesce(max(id),0)+1 FROM legacy_purchase", Long.class);
        jdbc.update("INSERT INTO legacy_purchase(id, trans_no, card_no, store_code, trans_time, amount, "
                + "points_delta, updated_seq) VALUES (?,?,?,?,?,?,?,?)",
                purchaseId, transNo, cardNo, store, transTime, new java.math.BigDecimal(amount), points, seq);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trans_no", transNo);
        payload.put("card_no", cardNo);
        payload.put("store_code", store);
        payload.put("trans_time", transTime.toString());
        payload.put("amount", amount);
        payload.put("points_delta", points);
        jdbc.update("INSERT INTO legacy_change_event(seq, source_id, entity_type, entity_key, op, payload, "
                + "content_hash, business_time) VALUES (?,?,?,?,?,?,?,?)",
                seq, LegacyDataSeeder.SOURCE_ID, "PURCHASE", transNo, "UPSERT",
                writeJson(payload), Hashes.sha256(payload), transTime);
        jdbc.update("UPDATE legacy_source_state SET last_seq=? WHERE source_id=?",
                seq, LegacyDataSeeder.SOURCE_ID);
        return Map.of("trans_no", transNo, "seq", seq, "accepted", true);
    }

    /** 断档补齐：补发缺失的 104 号事件 */
    @Transactional
    public Map<String, Object> fillGap104() {
        Long exists = jdbc.queryForObject("SELECT count(*) FROM legacy_change_event WHERE seq=104", Long.class);
        if (exists != null && exists > 0) {
            throw ApiException.conflict("序号 104 已存在，不能重复补齐");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trans_no", "T2003");
        payload.put("card_no", "C9004");
        payload.put("store_code", "S02");
        payload.put("trans_time", LocalDateTime.of(2025, 7, 20, 10, 0).toString());
        payload.put("amount", "55.00");
        payload.put("points_delta", 7);
        jdbc.update("INSERT INTO legacy_change_event(seq, source_id, entity_type, entity_key, op, payload, "
                + "content_hash, business_time) VALUES (?,?,?,?,?,?,?,?)",
                104L, LegacyDataSeeder.SOURCE_ID, "PURCHASE", "T2003", "UPSERT",
                writeJson(payload), Hashes.sha256(payload), LocalDateTime.of(2025, 7, 20, 10, 0));
        long purchaseId = jdbc.queryForObject("SELECT coalesce(max(id),0)+1 FROM legacy_purchase", Long.class);
        jdbc.update("INSERT INTO legacy_purchase(id, trans_no, card_no, store_code, trans_time, amount, "
                + "points_delta, updated_seq) VALUES (?,?,?,?,?,?,?,?)",
                purchaseId, "T2003", "C9004", "S02", LocalDateTime.of(2025, 7, 20, 10, 0),
                new java.math.BigDecimal("55.00"), 7, 104L);
        return Map.of("seq", 104L, "trans_no", "T2003", "filled", true);
    }

    /**
     * 冲突演示：构造与已登记序号内容不同的重发报文（模拟来源用相同序号发送不同内容）。
     * 不写入旧端事件表——旧端原件只有一份；迁移侧据此报告 CONFLICT。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> conflictingReplay(long seq) {
        Map<String, Object> original = jdbc.queryForMap("SELECT * FROM legacy_change_event WHERE seq=?", seq);
        Map<String, Object> tampered = new LinkedHashMap<>(original);
        tampered.put("payload", "{\"trans_no\":\"T9999\",\"note\":\"同序号不同内容\"}");
        tampered.put("content_hash", Hashes.sha256(Map.of("trans_no", "T9999", "note", "同序号不同内容")));
        tampered.put("replay", true);
        return tampered;
    }

    @Transactional
    public void markFrozen(long finalSeq) {
        Long last = jdbc.queryForObject("SELECT last_seq FROM legacy_source_state WHERE source_id=?",
                Long.class, LegacyDataSeeder.SOURCE_ID);
        if (finalSeq != last) {
            throw ApiException.precondition("最终序号 " + finalSeq + " 与旧端当前最大序号 " + last + " 不一致");
        }
        jdbc.update("UPDATE legacy_source_state SET frozen=TRUE, frozen_seq=?, frozen_at=? WHERE source_id=?",
                finalSeq, LocalDateTime.now(), LegacyDataSeeder.SOURCE_ID);
    }

    @Transactional
    public void unfreeze() {
        jdbc.update("UPDATE legacy_source_state SET frozen=FALSE, frozen_seq=NULL, frozen_at=NULL WHERE source_id=?",
                LegacyDataSeeder.SOURCE_ID);
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 快照分段定义：列顺序即原件行结构，参与哈希 */
    public enum SectionDef {
        MEMBER(1, "legacy_member",
                "id, member_code, name, phone, store_code, created_at"),
        CARD(2, "legacy_card",
                "id, card_no, holder_id, valid_from, valid_to, store_code"),
        PURCHASE(3, "legacy_purchase",
                "id, trans_no, card_no, store_code, trans_time, amount, points_delta"),
        OPENING(4, "legacy_point_opening",
                "id, member_code, store_code, opening_points");

        public final int ordinal;
        public final String table;
        public final String columns;

        SectionDef(int ordinal, String table, String columns) {
            this.ordinal = ordinal;
            this.table = table;
            this.columns = columns;
        }
    }
}
