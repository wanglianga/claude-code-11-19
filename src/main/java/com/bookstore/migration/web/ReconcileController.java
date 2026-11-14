package com.bookstore.migration.web;

import com.bookstore.migration.reconcile.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 核对：总数 -> 门店 -> 会员下钻，差异对应具体原行/事件 */
@RestController
@RequestMapping("/api/reconcile")
@Tag(name = "05-核对", description = "人数/交易数/积分差额核对与逐级下钻")
public class ReconcileController {

    private final ReconciliationService reconciliation;

    public ReconcileController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/check")
    @Operation(summary = "执行一次全量核对并留档（差异逐条对应原行 ROW 或事件 EVENT）")
    public Map<String, Object> check() {
        return reconciliation.runCheck();
    }

    @GetMapping("/runs")
    @Operation(summary = "历次核对记录")
    public List<Map<String, Object>> runs() {
        return reconciliation.listRuns();
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "某次核对的全部差异明细")
    public Map<String, Object> run(@PathVariable long runId) {
        return reconciliation.getRun(runId);
    }

    @GetMapping("/stores/{storeCode}")
    @Operation(summary = "下钻到门店：会员、双侧交易、余额")
    public Map<String, Object> store(@PathVariable String storeCode) {
        return reconciliation.drillStore(storeCode);
    }

    @GetMapping("/members/{memberCode}")
    @Operation(summary = "下钻到会员：旧记录、归属交易、台账与余额")
    public Map<String, Object> member(@PathVariable String memberCode) {
        return reconciliation.drillMember(memberCode);
    }
}
