package com.bookstore.migration.web;

import com.bookstore.migration.switchflow.SwitchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 切换：停写申请 -> 最终序号确认 -> 目标应用完成 -> 开放确认 */
@RestController
@RequestMapping("/api/switch")
@Tag(name = "06-受控切换", description = "四阶段状态机、共同条件、超时查询与取消规则")
@Validated
public class SwitchController {

    private final SwitchService switchService;

    public SwitchController(SwitchService switchService) {
        this.switchService = switchService;
    }

    public record SwitchRequest(@NotBlank String requested_by,
                                @NotNull Long requested_cutoff) {
    }

    public record CancelRequest(@NotBlank String actor,
                                @NotBlank String reason) {
    }

    @PostMapping("/requests")
    @Operation(summary = "阶段1：保存停写申请（同时只允许一份活动申请，不同截点只确认一份）")
    public Map<String, Object> request(@Validated @RequestBody SwitchRequest body) {
        return switchService.requestSwitch(body.requested_by(), body.requested_cutoff());
    }

    @PostMapping("/requests/{id}/confirm-final-seq")
    @Operation(summary = "阶段2：旧端确认最终序号并停写，之后拒绝新营业写入")
    public Map<String, Object> confirmFinalSeq(@PathVariable long id,
                                               @RequestParam(defaultValue = "operator") String actor) {
        return switchService.confirmFinalSeq(id, actor);
    }

    @PostMapping("/requests/{id}/apply-target")
    @Operation(summary = "阶段3：目标应用完成（追平/冲突/阻断/核对共同校验，失败给恢复步骤）")
    public Map<String, Object> applyTarget(@PathVariable long id,
                                           @RequestParam(defaultValue = "operator") String actor) {
        return switchService.applyTarget(id, actor);
    }

    @PostMapping("/requests/{id}/open")
    @Operation(summary = "阶段4：开放确认，四项共同条件全部满足才开放新端")
    public Map<String, Object> open(@PathVariable long id,
                                    @RequestParam(defaultValue = "operator") String actor) {
        return switchService.confirmOpen(id, actor);
    }

    @PostMapping("/requests/{id}/cancel")
    @Operation(summary = "未开放可取消并重新开放旧端；已开放不得自动退回")
    public Map<String, Object> cancel(@PathVariable long id, @Validated @RequestBody CancelRequest body) {
        return switchService.cancel(id, body.actor(), body.reason());
    }

    @GetMapping("/requests/{id}")
    @Operation(summary = "超时后先查询实际阶段、条件面板与允许动作")
    public Map<String, Object> status(@PathVariable long id) {
        return switchService.status(id);
    }

    @GetMapping("/requests")
    @Operation(summary = "全部切换申请")
    public List<Map<String, Object>> list() {
        return switchService.listRequests();
    }

    @GetMapping("/conditions")
    @Operation(summary = "条件面板：预计积压与四项共同条件满足情况")
    public Map<String, Object> conditions() {
        return switchService.conditionsPanel();
    }
}
