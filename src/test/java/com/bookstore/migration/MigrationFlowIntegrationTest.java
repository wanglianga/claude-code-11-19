package com.bookstore.migration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试：随机端口启动完整上下文，对内置旧端模拟与迁移主链路做端到端校验。
 * 依赖 application.yml 配置的本地 PostgreSQL（与 docker-compose 同构）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        return h;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path) {
        return rest.postForObject(url(path), null, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) {
        return rest.postForObject(url(path), new HttpEntity<>(body, json()), Map.class);
    }

    @Test
    @Order(1)
    void fullMigrationAndSwitchFlow() {
        // 0. 重置
        Map<String, Object> reset = post("/api/legacy/system/reset");
        assertThat(reset.get("reset")).isEqualTo(true);

        // 1. 清单先行，未齐不得完成
        Map<String, Object> manifest = post("/api/snapshot/manifest");
        assertThat(manifest.get("cutoff_seq")).isEqualTo(100);
        assertThat(manifest.get("total_sections")).isEqualTo(4);
        ResponseEntity<Map> blocked = rest.postForEntity(url("/api/snapshot/complete"), null, Map.class);
        assertThat(blocked.getStatusCode().value()).isEqualTo(412);

        // 2. 分段接收 + 验证 + 完成
        for (String sec : List.of("MEMBER", "CARD", "PURCHASE", "OPENING")) {
            Map<String, Object> page = post("/api/snapshot/sections/" + sec + "/receive?limit=3");
            while (Boolean.TRUE.equals(page.get("has_more"))) {
                page = post("/api/snapshot/sections/" + sec + "/receive?limit=3");
            }
            Map<String, Object> verified = post("/api/snapshot/sections/" + sec + "/verify");
            assertThat(verified.get("status")).isEqualTo("VERIFIED");
        }
        assertThat(post("/api/snapshot/complete").get("status")).isEqualTo("COMPLETED");

        // 3. 基线投影，卡号换人正确归属，重叠/缺失进阻断
        Map<String, Object> projected = post("/api/snapshot/project-baseline");
        assertThat(projected.get("members")).isEqualTo(7);
        assertThat(projected.get("purchases")).isEqualTo(9);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blockedList = rest.getForObject(url("/api/ownership/blocked"), List.class);
        assertThat(blockedList).extracting(t -> t.get("trans_no"))
                .contains("T1004", "T1005");

        // 4. 变更流断档暂停 -> 补齐 -> 续接追平
        Map<String, Object> firstPull = post("/api/changes/pull?limit=50");
        assertThat(firstPull.get("paused")).isEqualTo(true);
        assertThat(firstPull.get("contiguous_seq")).isEqualTo(103);
        assertThat(post("/api/legacy/gap/fill-104").get("filled")).isEqualTo(true);
        assertThat(post("/api/changes/resume").get("paused")).isEqualTo(false);
        assertThat(post("/api/changes/pull?limit=50").get("contiguous_seq")).isEqualTo(110);

        // 5. 撤销先到联动：T2005 已失效
        @SuppressWarnings("unchecked")
        Map<String, Object> t2005 = rest.getForObject(
                url("/api/ownership/transactions/T2005/candidates"), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> t2005Row = ((List<Map<String, Object>>) t2005.get("transaction")).get(0);
        assertThat(t2005Row.get("valid_flag")).isEqualTo(false);

        // 6. 人工判定必须有证据
        Map<String, Object> badDecision = Map.of(
                "trans_no", "T1004", "owner_code", "M004", "evidence", "abc", "decided_by", "op");
        ResponseEntity<Map> badResp = rest.postForEntity(
                url("/api/ownership/decisions"), new HttpEntity<>(badDecision, json()), Map.class);
        assertThat(badResp.getStatusCode().value()).isEqualTo(400);
        Map<String, Object> decision = post("/api/ownership/decisions", Map.of(
                "trans_no", "T1004", "owner_code", "M003",
                "evidence", "门店纸质签购单核对签名确认", "decided_by", "op1"));
        assertThat(decision.get("decided_owner")).isEqualTo("M003");
        post("/api/ownership/decisions", Map.of(
                "trans_no", "T1005", "owner_code", "M005",
                "evidence", "停业门店留存的纸质签购单凭证", "decided_by", "op1"));

        // 7. 缺期初阻断，凭证据补录真实期初
        Map<String, Object> firstCheck = post("/api/reconcile/check");
        assertThat(firstCheck.get("overall_passed")).isEqualTo(false);
        post("/api/ownership/openings/evidence", Map.of(
                "member_code", "M007", "opening_points", 60,
                "evidence", "停业门店交接清单登记期初60分", "recorded_by", "op1"));

        // 8. 核对通过
        Map<String, Object> finalCheck = post("/api/reconcile/check");
        assertThat(finalCheck.get("overall_passed")).isEqualTo(true);
        assertThat(finalCheck.get("diff_count")).isEqualTo(0);

        // 9. 切换四阶段与共同条件
        Map<String, Object> req = post("/api/switch/requests", Map.of(
                "requested_by", "alice", "requested_cutoff", 110));
        long rid = ((Number) req.get("id")).longValue();
        assertThat(req.get("status")).isEqualTo("FREEZE_REQUESTED");
        ResponseEntity<Map> secondReq = rest.postForEntity(url("/api/switch/requests"),
                new HttpEntity<>(Map.of("requested_by", "bob", "requested_cutoff", 108), json()), Map.class);
        assertThat(secondReq.getStatusCode().value()).isEqualTo(409);
        assertThat(post("/api/switch/requests/" + rid + "/confirm-final-seq?actor=alice")
                .get("status")).isEqualTo("FINAL_SEQ_CONFIRMED");
        // 停写后拒绝营业写入
        ResponseEntity<Map> writeBlocked = rest.postForEntity(url("/api/legacy/business/purchase"),
                new HttpEntity<>(Map.of("trans_no", "TX", "card_no", "C9001", "store_code", "S01",
                        "trans_time", "2025-10-01T10:00:00", "amount", "10.00", "points_delta", 1), json()),
                Map.class);
        assertThat(writeBlocked.getStatusCode().value()).isEqualTo(409);
        assertThat(post("/api/switch/requests/" + rid + "/apply-target?actor=alice")
                .get("status")).isEqualTo("TARGET_APPLIED");
        assertThat(post("/api/switch/requests/" + rid + "/open?actor=alice")
                .get("status")).isEqualTo("OPENED");
        // 开放后不得自动退回
        ResponseEntity<Map> cancelBlocked = rest.exchange(
                url("/api/switch/requests/" + rid + "/cancel"), HttpMethod.POST,
                new HttpEntity<>(Map.of("actor", "alice", "reason", "x"), json()), Map.class);
        assertThat(cancelBlocked.getStatusCode().value()).isEqualTo(409);

        // 10. 开放后查询固定走新端同一侧
        @SuppressWarnings("unchecked")
        Map<String, Object> side = rest.getForObject(url("/api/target/side"), Map.class);
        assertThat(side.get("read_side")).isEqualTo("TARGET");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> balances = rest.getForObject(url("/api/target/balances"), List.class);
        Map<String, Object> m007 = balances.stream()
                .filter(b -> "M007".equals(b.get("member_code"))).findFirst().orElseThrow();
        assertThat(m007.get("balance_points")).isEqualTo(66);
    }
}
