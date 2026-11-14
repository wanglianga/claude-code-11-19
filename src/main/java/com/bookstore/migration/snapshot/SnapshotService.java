package com.bookstore.migration.snapshot;

import com.bookstore.migration.legacy.LegacyDataSeeder;
import com.bookstore.migration.legacy.LegacySimulatorService;
import com.bookstore.migration.support.ApiException;
import com.bookstore.migration.support.Hashes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照基线复制：
 * 1. 先取得清单（分段、行数、摘要、总校验和、截止序号）；
 * 2. 再分段拉取接收，每段落 mig_raw_* 原件区并记录续接游标；
 * 3. 逐段重算哈希验证；清单未齐/未验不得宣布基线复制完成。
 */
@Service
public class SnapshotService {

    private final JdbcTemplate jdbc;
    private final LegacySimulatorService legacy;

    public SnapshotService(JdbcTemplate jdbc, LegacySimulatorService legacy) {
        this.jdbc = jdbc;
        this.legacy = legacy;
    }

    /** 第一步：取得清单并落账（幂等） */
    @Transactional
    public Map<String, Object> pullManifest() {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM mig_snapshot_manifest WHERE source_id=?", LegacyDataSeeder.SOURCE_ID);
        if (!existing.isEmpty()) {
            return status();
        }
        Map<String, Object> manifest = legacy.exportManifest();
        long manifestId = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
        jdbc.update("INSERT INTO mig_snapshot_manifest(id, source_id, cutoff_seq, snapshot_time, total_sections, "
                        + "total_rows, checksum, status, created_at) VALUES (?,?,?,?,?,?,?,'LISTED',?)",
                manifestId, manifest.get("source_id"), ((Number) manifest.get("cutoff_seq")).longValue(),
                LocalDateTime.parse((String) manifest.get("snapshot_time")),
                ((Number) manifest.get("total_sections")).intValue(),
                ((Number) manifest.get("total_rows")).longValue(),
                manifest.get("checksum"), LocalDateTime.now());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) manifest.get("sections");
        for (Map<String, Object> s : sections) {
            long sectionId = jdbc.queryForObject("SELECT nextval('app_global_seq')", Long.class);
            jdbc.update("INSERT INTO mig_snapshot_section(id, manifest_id, section_name, ordinal, expected_rows, "
                            + "section_summary, section_hash, status) VALUES (?,?,?,?,?,?,?,'PENDING')",
                    sectionId, manifestId, s.get("section_name"), ((Number) s.get("ordinal")).intValue(),
                    ((Number) s.get("expected_rows")).longValue(),
                    String.valueOf(s.get("section_summary")), s.get("section_hash"));
        }
        return status();
    }

    /** 第二步：分段接收一页（游标续接，进程中断后从 last_id 继续） */
    @Transactional
    public Map<String, Object> receiveSection(String sectionName, int limit) {
        ensureManifestListed();
        long manifestId = currentManifestId();
        Map<String, Object> section = getSection(manifestId, sectionName);
        if ("VERIFIED".equals(section.get("status"))) {
            return Map.of("section_name", sectionName, "status", "VERIFIED", "message", "该段已验证，无需重复接收");
        }
        Long afterId = jdbc.queryForObject(
                "SELECT coalesce(max(last_key),0) FROM mig_chunk_cursor WHERE stream_kind='SNAPSHOT' "
                        + "AND source_id=? AND section_name=?",
                Long.class, LegacyDataSeeder.SOURCE_ID, sectionName);
        if (afterId == null) {
            afterId = 0L;
        }
        Map<String, Object> page = legacy.fetchSectionRows(sectionName, afterId, limit);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
        for (Map<String, Object> row : rows) {
            insertRawRow(sectionName, row, manifestId);
        }
        long lastId = ((Number) page.get("last_id")).longValue();
        jdbc.update("INSERT INTO mig_chunk_cursor(id, stream_kind, source_id, section_name, last_key, committed_at) "
                        + "VALUES (nextval('app_global_seq'),'SNAPSHOT',?,?,?,?) "
                        + "ON CONFLICT (stream_kind, source_id, section_name) "
                        + "DO UPDATE SET last_key=EXCLUDED.last_key, committed_at=EXCLUDED.committed_at",
                LegacyDataSeeder.SOURCE_ID, sectionName, lastId, LocalDateTime.now());
        long received = jdbc.queryForObject(
                "SELECT count(*) FROM " + rawTable(sectionName) + " WHERE source_seq=0", Long.class);
        jdbc.update("UPDATE mig_snapshot_section SET received_rows=?, status='RECEIVED' WHERE id=?",
                received, section.get("id"));
        jdbc.update("UPDATE mig_snapshot_manifest SET status='RECEIVING' WHERE id=?", manifestId);
        return Map.of("section_name", sectionName, "received_rows", received,
                "expected_rows", section.get("expected_rows"), "last_id", lastId,
                "has_more", page.get("has_more"));
    }

    /** 第三步：整段重算哈希并与清单摘要比对 */
    @Transactional
    public Map<String, Object> verifySection(String sectionName) {
        long manifestId = currentManifestId();
        Map<String, Object> section = getSection(manifestId, sectionName);
        // 从旧端重新全量拉取该段（多页续接），独立重算
        List<Map<String, Object>> sourceRows = pullAllFromLegacy(sectionName);
        String actualHash = Hashes.sha256(sourceRows);
        long actualCount = sourceRows.size();
        long storedCount = jdbc.queryForObject(
                "SELECT count(*) FROM " + rawTable(sectionName) + " WHERE source_seq=0", Long.class);
        boolean hashOk = actualHash.equals(section.get("section_hash"));
        boolean countOk = actualCount == ((Number) section.get("expected_rows")).longValue()
                && storedCount == actualCount;
        if (!hashOk || !countOk) {
            throw ApiException.precondition("分段 " + sectionName + " 校验失败：hashOk=" + hashOk
                    + ", countOk=" + countOk + "（期望 " + section.get("expected_rows")
                    + " 行/哈希 " + section.get("section_hash") + "，实际 " + storedCount
                    + " 行/哈希 " + actualHash + "）");
        }
        jdbc.update("UPDATE mig_snapshot_section SET status='VERIFIED', verified_at=? WHERE id=?",
                LocalDateTime.now(), section.get("id"));
        return Map.of("section_name", sectionName, "status", "VERIFIED", "rows", actualCount,
                "section_hash", actualHash);
    }

    /** 第四步：全部段验证齐备后才允许完成基线 */
    @Transactional
    public Map<String, Object> completeBaseline() {
        long manifestId = currentManifestId();
        List<Map<String, Object>> sections = jdbc.queryForList(
                "SELECT section_name, status, expected_rows, received_rows FROM mig_snapshot_section WHERE manifest_id=?",
                manifestId);
        List<String> notReady = sections.stream()
                .filter(s -> !"VERIFIED".equals(s.get("status")))
                .map(s -> s.get("section_name") + "(" + s.get("status") + ")")
                .toList();
        if (!notReady.isEmpty()) {
            throw ApiException.precondition("清单未齐，不得宣布基线复制完成；未验证分段：" + notReady);
        }
        long cutoffSeq = jdbc.queryForObject("SELECT cutoff_seq FROM mig_snapshot_manifest WHERE id=?",
                Long.class, manifestId);
        jdbc.update("UPDATE mig_snapshot_manifest SET status='COMPLETED', completed_at=? WHERE id=?",
                LocalDateTime.now(), manifestId);
        // 建立来源进度：仅应用截止序号之后的变更
        jdbc.update("INSERT INTO mig_source_progress(source_id, cutoff_seq, contiguous_seq, paused) "
                        + "VALUES (?,?,?,FALSE) ON CONFLICT (source_id) DO NOTHING",
                LegacyDataSeeder.SOURCE_ID, cutoffSeq, cutoffSeq);
        return status();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        List<Map<String, Object>> manifests = jdbc.queryForList(
                "SELECT * FROM mig_snapshot_manifest WHERE source_id=?", LegacyDataSeeder.SOURCE_ID);
        if (manifests.isEmpty()) {
            return Map.of("status", "NOT_STARTED", "message", "尚未取得快照清单，请先调用 POST /api/snapshot/manifest");
        }
        Map<String, Object> manifest = manifests.get(0);
        List<Map<String, Object>> sections = jdbc.queryForList(
                "SELECT section_name, ordinal, expected_rows, received_rows, status, section_summary, section_hash "
                        + "FROM mig_snapshot_section WHERE manifest_id=? ORDER BY ordinal", manifest.get("id"));
        Map<String, Object> resp = new LinkedHashMap<>(manifest);
        resp.put("sections", sections);
        return resp;
    }

    // ---------------------------------------------------------------
    private List<Map<String, Object>> pullAllFromLegacy(String sectionName) {
        List<Map<String, Object>> all = new java.util.ArrayList<>();
        long afterId = 0;
        while (true) {
            Map<String, Object> page = legacy.fetchSectionRows(sectionName, afterId, 500);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
            all.addAll(rows);
            afterId = ((Number) page.get("last_id")).longValue();
            if (!Boolean.TRUE.equals(page.get("has_more"))) {
                break;
            }
        }
        return all;
    }

    private void insertRawRow(String sectionName, Map<String, Object> r, long manifestId) {
        long id = ((Number) r.get("id")).longValue();
        switch (sectionName) {
            case "MEMBER" -> jdbc.update("INSERT INTO mig_raw_member(id, member_code, name, phone, store_code, "
                            + "created_at, manifest_id, source_seq) VALUES (?,?,?,?,?,?,?,0) "
                            + "ON CONFLICT (member_code) DO UPDATE SET name=EXCLUDED.name, phone=EXCLUDED.phone, "
                            + "store_code=EXCLUDED.store_code, created_at=EXCLUDED.created_at",
                    id, r.get("member_code"), r.get("name"), r.get("phone"), r.get("store_code"),
                    r.get("created_at"), manifestId);
            case "CARD" -> {
                long holderId = ((Number) r.get("holder_id")).longValue();
                String holderCode = jdbc.queryForObject("SELECT member_code FROM legacy_member WHERE id=?",
                        String.class, holderId);
                jdbc.update("INSERT INTO mig_raw_card(id, card_no, holder_code, valid_from, valid_to, store_code, "
                                + "manifest_id, source_seq) VALUES (?,?,?,?,?,?,?,0) "
                                + "ON CONFLICT (card_no, holder_code, valid_from) DO NOTHING",
                        id, r.get("card_no"), holderCode, r.get("valid_from"), r.get("valid_to"),
                        r.get("store_code"), manifestId);
            }
            case "PURCHASE" -> jdbc.update("INSERT INTO mig_raw_purchase(id, trans_no, card_no, store_code, "
                            + "trans_time, amount, points_delta, manifest_id, source_seq) "
                            + "VALUES (?,?,?,?,?,?,?,?,0) ON CONFLICT (trans_no) DO NOTHING",
                    id, r.get("trans_no"), r.get("card_no"), r.get("store_code"), r.get("trans_time"),
                    r.get("amount"), r.get("points_delta"), manifestId);
            case "OPENING" -> jdbc.update("INSERT INTO mig_raw_opening(id, member_code, store_code, opening_points, "
                            + "manifest_id, source_seq) VALUES (?,?,?,?,?,0) ON CONFLICT (member_code) DO NOTHING",
                    id, r.get("member_code"), r.get("store_code"), r.get("opening_points"), manifestId);
            default -> throw ApiException.badRequest("未知分段: " + sectionName);
        }
    }

    private String rawTable(String sectionName) {
        return switch (sectionName) {
            case "MEMBER" -> "mig_raw_member";
            case "CARD" -> "mig_raw_card";
            case "PURCHASE" -> "mig_raw_purchase";
            case "OPENING" -> "mig_raw_opening";
            default -> throw ApiException.badRequest("未知分段: " + sectionName);
        };
    }

    private Map<String, Object> getSection(long manifestId, String name) {
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT * FROM mig_snapshot_section WHERE manifest_id=? AND section_name=?", manifestId, name);
        if (list.isEmpty()) {
            throw ApiException.notFound("清单中不存在分段: " + name);
        }
        return list.get(0);
    }

    private long currentManifestId() {
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT id FROM mig_snapshot_manifest WHERE source_id=?", LegacyDataSeeder.SOURCE_ID);
        if (list.isEmpty()) {
            throw ApiException.precondition("尚未取得快照清单，请先调用 manifest 接口");
        }
        return ((Number) list.get(0).get("id")).longValue();
    }

    private void ensureManifestListed() {
        currentManifestId();
    }

    /** 供其他服务判断基线是否完成 */
    public boolean isBaselineCompleted() {
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT status FROM mig_snapshot_manifest WHERE source_id=?", LegacyDataSeeder.SOURCE_ID);
        return !list.isEmpty() && "COMPLETED".equals(list.get(0).get("status"));
    }

    // 供变更应用复用：金额转换
    public static BigDecimal bd(Object v) {
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }
}
