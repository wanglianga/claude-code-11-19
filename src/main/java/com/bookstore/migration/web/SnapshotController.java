package com.bookstore.migration.web;

import com.bookstore.migration.projection.TargetProjectionService;
import com.bookstore.migration.snapshot.SnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 快照基线：清单 -> 分段接收 -> 分段验证 -> 完成 -> 投影新端 */
@RestController
@RequestMapping("/api/snapshot")
@Tag(name = "02-快照基线", description = "同逻辑截点清单、行数摘要与分段校验接收")
public class SnapshotController {

    private final SnapshotService snapshot;
    private final TargetProjectionService projection;

    public SnapshotController(SnapshotService snapshot, TargetProjectionService projection) {
        this.snapshot = snapshot;
        this.projection = projection;
    }

    @PostMapping("/manifest")
    @Operation(summary = "第一步：取得清单（分段/行数/摘要/总校验和/截止序号）")
    public Map<String, Object> pullManifest() {
        return snapshot.pullManifest();
    }

    @GetMapping("/status")
    @Operation(summary = "快照基线整体状态")
    public Map<String, Object> status() {
        return snapshot.status();
    }

    @PostMapping("/sections/{name}/receive")
    @Operation(summary = "第二步：分段接收一页（游标续接，进程中断后继续）")
    public Map<String, Object> receive(@PathVariable String name,
                                       @RequestParam(defaultValue = "500") int limit) {
        return snapshot.receiveSection(name, limit);
    }

    @PostMapping("/sections/{name}/verify")
    @Operation(summary = "第三步：整段重算哈希与清单比对")
    public Map<String, Object> verify(@PathVariable String name) {
        return snapshot.verifySection(name);
    }

    @PostMapping("/complete")
    @Operation(summary = "第四步：清单未齐不得完成；齐备后宣布基线复制完成")
    public Map<String, Object> complete() {
        return snapshot.completeBaseline();
    }

    @PostMapping("/project-baseline")
    @Operation(summary = "将已验证原件投影到新端并重建余额")
    public Map<String, Object> projectBaseline() {
        if (!snapshot.isBaselineCompleted()) {
            throw new com.bookstore.migration.support.ApiException(412,
                    "基线复制未完成，不得投影新端");
        }
        return projection.projectBaseline();
    }
}
