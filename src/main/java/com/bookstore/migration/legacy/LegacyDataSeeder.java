package com.bookstore.migration.legacy;

import com.bookstore.migration.support.Hashes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旧端样例数据播种器（幂等）。
 * 覆盖场景：
 *  1) 卡号换人：C9001 先属 M001 后回收发给 M002；
 *  2) 持卡区间重叠：C9002 在 2025-05~08 同时挂在 M003/M004 名下；
 *  3) 区间缺失：C9003 交易时无任何有效持卡区间；
 *  4) 停业门店缺期初：S02 的 M007 无积分期初行；
 *  5) 同手机号家庭成员：M004/M005 同号，不得自动合并；
 *  6) 快照后变更：序号 101+，含断档(104)、撤销先到(106 先于原交易 107)。
 */
@Component
public class LegacyDataSeeder {

    public static final String SOURCE_ID = "LEGACY-PRIMARY";
    public static final long SNAPSHOT_CUTOFF_SEQ = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LegacyDataSeeder(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void reseed() {
        jdbc.update("TRUNCATE legacy_change_event, legacy_point_opening, legacy_purchase, "
                + "legacy_card, legacy_member, legacy_source_state RESTART IDENTITY CASCADE");

        // 会员
        Object[][] members = {
                {1L, "M001", "张伟", "13800000001", "S01", dt(2025, 1, 1, 9, 0)},
                {2L, "M002", "李娜", "13800000002", "S01", dt(2025, 1, 2, 9, 0)},
                {3L, "M003", "王芳", "13800000003", "S01", dt(2025, 1, 3, 9, 0)},
                {4L, "M004", "刘强", "13800000004", "S01", dt(2025, 1, 4, 9, 0)},
                {5L, "M005", "刘敏", "13800000004", "S03", dt(2025, 1, 5, 9, 0)},
                {6L, "M006", "陈静", "13900000006", "S02", dt(2025, 1, 6, 9, 0)},
                {7L, "M007", "赵磊", "13900000007", "S02", dt(2025, 1, 7, 9, 0)},
        };
        for (Object[] m : members) {
            jdbc.update("INSERT INTO legacy_member(id, member_code, name, phone, store_code, created_at, updated_seq) "
                            + "VALUES (?,?,?,?,?,?,0)",
                    m[0], m[1], m[2], m[3], m[4], m[5]);
        }

        // 卡片持卡有效区间（C9001 回收重发；C9002 区间重叠；C9003 已失效）
        Object[][] cards = {
                {11L, "C9001", 1L, dt(2025, 1, 1, 0, 0), dt(2025, 6, 30, 23, 59), "S01"},
                {12L, "C9001", 2L, dt(2025, 7, 1, 0, 0), null, "S01"},
                {13L, "C9002", 3L, dt(2025, 2, 1, 0, 0), dt(2025, 8, 31, 23, 59), "S01"},
                {14L, "C9002", 4L, dt(2025, 5, 1, 0, 0), null, "S01"},
                {15L, "C9003", 5L, dt(2025, 3, 1, 0, 0), dt(2025, 4, 30, 23, 59), "S03"},
                {16L, "C9004", 6L, dt(2025, 2, 10, 0, 0), null, "S02"},
                {17L, "C9005", 7L, dt(2025, 3, 1, 0, 0), null, "S02"},
        };
        for (Object[] c : cards) {
            jdbc.update("INSERT INTO legacy_card(id, card_no, holder_id, valid_from, valid_to, store_code, updated_seq) "
                            + "VALUES (?,?,?,?,?,?,0)",
                    c[0], c[1], c[2], c[3], c[4], c[5]);
        }

        // 基线购书（updated_seq=0，属于快照基线）
        Object[][] purchases = {
                {101L, "T1001", "C9001", "S01", dt(2025, 2, 10, 10, 0), new BigDecimal("88.00"), 10},
                {102L, "T1002", "C9001", "S01", dt(2025, 6, 20, 11, 0), new BigDecimal("160.00"), 20},
                {103L, "T1003", "C9001", "S01", dt(2025, 7, 5, 10, 30), new BigDecimal("120.00"), 15},
                {104L, "T1004", "C9002", "S01", dt(2025, 6, 15, 15, 0), new BigDecimal("96.00"), 12},
                {105L, "T1005", "C9003", "S03", dt(2025, 5, 10, 9, 30), new BigDecimal("50.00"), 5},
                {106L, "T1006", "C9004", "S02", dt(2025, 3, 11, 14, 0), new BigDecimal("70.00"), 8},
                {107L, "T1007", "C9005", "S02", dt(2025, 4, 12, 16, 20), new BigDecimal("45.00"), 6},
                {108L, "T1008", "C9001", "S01", dt(2025, 6, 30, 20, 0), new BigDecimal("40.00"), 5},
                {109L, "T1009", "C9002", "S01", dt(2025, 9, 10, 19, 0), new BigDecimal("130.00"), 14},
        };
        for (Object[] p : purchases) {
            jdbc.update("INSERT INTO legacy_purchase(id, trans_no, card_no, store_code, trans_time, amount, "
                            + "points_delta, updated_seq) VALUES (?,?,?,?,?,?,?,0)",
                    p[0], p[1], p[2], p[3], p[4], p[5], p[6]);
        }

        // 积分期初：M007 故意缺行（停业门店 S02 只找到部分期初）
        Object[][] openings = {
                {201L, "M001", "S01", 100},
                {202L, "M002", "S01", 50},
                {203L, "M003", "S01", 200},
                {204L, "M004", "S01", 30},
                {205L, "M005", "S03", 80},
                {206L, "M006", "S02", 40},
        };
        for (Object[] o : openings) {
            jdbc.update("INSERT INTO legacy_point_opening(id, member_code, store_code, opening_points, updated_seq) "
                            + "VALUES (?,?,?,?,0)",
                    o[0], o[1], o[2], o[3]);
        }

        // 快照后的变更事件（104 序号故意缺失，模拟断档；106 撤销先于 107 原交易）
        insertEvent(101L, "PURCHASE", "T2001", "UPSERT", purchasePayload(
                "T2001", "C9001", "S01", dt(2025, 7, 15, 10, 0), "128.00", 16), null,
                dt(2025, 7, 15, 10, 0));
        insertEvent(102L, "MEMBER", "M008", "UPSERT", memberPayload(
                8L, "M008", "孙磊", "13700000008", "S01", dt(2025, 7, 16, 9, 0)), null,
                dt(2025, 7, 16, 9, 0));
        insertEvent(103L, "PURCHASE", "T2002", "UPSERT", purchasePayload(
                "T2002", "C9004", "S02", dt(2025, 7, 18, 11, 0), "60.00", 7), null,
                dt(2025, 7, 18, 11, 0));
        // seq=104 断档：稍后由 fillGap() 补齐
        insertEvent(105L, "PURCHASE", "T2004", "UPSERT", purchasePayload(
                "T2004", "C9001", "S01", dt(2025, 8, 1, 10, 0), "210.00", 25), null,
                dt(2025, 8, 1, 10, 0));
        Map<String, Object> reversal = new LinkedHashMap<>();
        reversal.put("trans_no", "T2005");
        insertEvent(106L, "PURCHASE", "T2005", "DELETE", reversal, "T2005",
                dt(2025, 9, 2, 10, 0));
        insertEvent(107L, "PURCHASE", "T2005", "UPSERT", purchasePayload(
                "T2005", "C9001", "S01", dt(2025, 9, 1, 9, 0), "300.00", 35), null,
                dt(2025, 9, 1, 9, 0));
        insertEvent(108L, "PURCHASE", "T2006", "UPSERT", purchasePayload(
                "T2006", "C9002", "S01", dt(2025, 9, 20, 18, 0), "75.00", 9), null,
                dt(2025, 9, 20, 18, 0));
        insertEvent(109L, "CARD", "C9003", "UPSERT", cardPayload(
                "C9003", "M005", dt(2025, 10, 1, 0, 0), null, "S03"), null,
                dt(2025, 9, 25, 9, 0));
        insertEvent(110L, "OPENING", "M008", "UPSERT", openingPayload("M008", "S01", 100), null,
                dt(2025, 9, 26, 9, 0));

        jdbc.update("INSERT INTO legacy_source_state(source_id, last_seq, frozen) VALUES (?,?,FALSE)",
                SOURCE_ID, 110L);
    }

    private static LocalDateTime dt(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi);
    }

    private void insertEvent(long seq, String entityType, String entityKey, String op,
                             Map<String, Object> payload, String linkedTransNo, LocalDateTime businessTime) {
        jdbc.update("INSERT INTO legacy_change_event(seq, source_id, entity_type, entity_key, op, payload, "
                        + "content_hash, business_time, linked_trans_no) VALUES (?,?,?,?,?,?,?,?,?)",
                seq, SOURCE_ID, entityType, entityKey, op,
                writeJson(payload), Hashes.sha256(payload), businessTime, linkedTransNo);
    }

    private Map<String, Object> purchasePayload(String transNo, String cardNo, String store,
                                                LocalDateTime time, String amount, int points) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trans_no", transNo);
        m.put("card_no", cardNo);
        m.put("store_code", store);
        m.put("trans_time", time.toString());
        m.put("amount", amount);
        m.put("points_delta", points);
        return m;
    }

    private Map<String, Object> memberPayload(long id, String code, String name, String phone,
                                              String store, LocalDateTime createdAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("member_code", code);
        m.put("name", name);
        m.put("phone", phone);
        m.put("store_code", store);
        m.put("created_at", createdAt.toString());
        return m;
    }

    private Map<String, Object> cardPayload(String cardNo, String holderCode, LocalDateTime from,
                                            LocalDateTime to, String store) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("card_no", cardNo);
        m.put("holder_code", holderCode);
        m.put("valid_from", from.toString());
        m.put("valid_to", to == null ? null : to.toString());
        m.put("store_code", store);
        return m;
    }

    private Map<String, Object> openingPayload(String memberCode, String store, int points) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("member_code", memberCode);
        m.put("store_code", store);
        m.put("opening_points", points);
        return m;
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Long> knownSeqs() {
        return List.of(101L, 102L, 103L, 105L, 106L, 107L, 108L, 109L, 110L);
    }
}
