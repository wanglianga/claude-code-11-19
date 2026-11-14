package com.bookstore.migration.web;

import com.bookstore.migration.bootstrap.ResetService;
import com.bookstore.migration.legacy.LegacySimulatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/** 模拟旧端：营业写入、断档补齐、冲突重发、停写控制 */
@RestController
@RequestMapping("/api/legacy")
@Tag(name = "01-模拟旧端", description = "旧系统继续营业记账的模拟接口")
public class LegacyController {

    private final LegacySimulatorService legacy;
    private final ResetService reset;

    public LegacyController(LegacySimulatorService legacy, ResetService reset) {
        this.legacy = legacy;
        this.reset = reset;
    }

    @GetMapping("/state")
    @Operation(summary = "旧端来源状态（最大序号/是否停写）")
    public Map<String, Object> state() {
        return legacy.sourceState();
    }

    @PostMapping("/business/purchase")
    @Operation(summary = "旧端继续营业：登记一笔购书并产生下一序号变更（停写后拒绝）")
    public Map<String, Object> businessPurchase(@RequestBody Map<String, Object> body) {
        return legacy.acceptBusinessPurchase(
                (String) body.get("trans_no"),
                (String) body.get("card_no"),
                (String) body.get("store_code"),
                LocalDateTime.parse((String) body.get("trans_time")),
                String.valueOf(body.get("amount")),
                ((Number) body.get("points_delta")).intValue());
    }

    @PostMapping("/gap/fill-104")
    @Operation(summary = "断档补齐：补发缺失的 104 号事件")
    public Map<String, Object> fillGap() {
        return legacy.fillGap104();
    }

    @GetMapping("/conflict/replay/{seq}")
    @Operation(summary = "构造同序号不同内容的冲突报文（投递用 /api/changes/ingest）")
    public Map<String, Object> conflictReplay(@PathVariable long seq) {
        return legacy.conflictingReplay(seq);
    }

    @PostMapping("/freeze")
    @Operation(summary = "直接停写旧端（一般由切换流程内部调用）")
    public Map<String, Object> freeze(@RequestBody Map<String, Object> body) {
        long finalSeq = ((Number) body.get("final_seq")).longValue();
        legacy.markFrozen(finalSeq);
        return legacy.sourceState();
    }

    @PostMapping("/unfreeze")
    @Operation(summary = "重新开放旧端写入（取消切换时联动）")
    public Map<String, Object> unfreeze() {
        legacy.unfreeze();
        return legacy.sourceState();
    }

    @PostMapping("/system/reset")
    @Operation(summary = "全量重置三层数据并重新播种样例")
    public Map<String, Object> resetAll() {
        return reset.resetAll();
    }
}
