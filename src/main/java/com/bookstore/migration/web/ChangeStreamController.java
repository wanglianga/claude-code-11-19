package com.bookstore.migration.web;

import com.bookstore.migration.changestream.ChangeStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 变更流：拉取续接、手工投递（重复/冲突/乱序）、暂停恢复、冲突处置 */
@RestController
@RequestMapping("/api/changes")
@Tag(name = "03-变更流", description = "递增序号变更的接入规则与续接位置")
public class ChangeStreamController {

    private final ChangeStreamService changes;

    public ChangeStreamController(ChangeStreamService changes) {
        this.changes = changes;
    }

    @PostMapping("/pull")
    @Operation(summary = "按已提交位置拉取一批并应用（断档即暂停，记录已提交位置可续接）")
    public Map<String, Object> pull(@RequestParam(defaultValue = "50") int limit) {
        return changes.pullChunk(limit);
    }

    @PostMapping("/ingest")
    @Operation(summary = "手工投递事件报文（重复/冲突/乱序/撤销先到均按规则处理）")
    public Map<String, Object> ingest(@RequestBody Map<String, Object> event) {
        return changes.ingest(event);
    }

    @PostMapping("/resume")
    @Operation(summary = "断档补齐后续接来源")
    public Map<String, Object> resume() {
        return changes.resume();
    }

    @PostMapping("/conflicts/{seq}/resolve")
    @Operation(summary = "冲突处置：仅支持 KEEP_ORIGINAL（以旧端原件为准）")
    public Map<String, Object> resolveConflict(@PathVariable long seq,
                                               @RequestParam(defaultValue = "KEEP_ORIGINAL") String action) {
        return changes.resolveConflict(seq, action);
    }

    @GetMapping("/status")
    @Operation(summary = "变更流状态：截止/连续序号、暂停原因、预计积压")
    public Map<String, Object> status() {
        return changes.status();
    }
}
