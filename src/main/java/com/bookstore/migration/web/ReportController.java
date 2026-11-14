package com.bookstore.migration.web;

import com.bookstore.migration.query.TargetQueryService;
import com.bookstore.migration.report.MemberReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 报告与查询：迁移侧会员报告全程可查；新端业务查询仅开放后可用且来自同一侧完整范围 */
@RestController
@RequestMapping("/api")
@Tag(name = "07-报告与查询", description = "会员迁移报告与新端开放守卫查询")
public class ReportController {

    private final MemberReportService reports;
    private final TargetQueryService targetQuery;

    public ReportController(MemberReportService reports, TargetQueryService targetQuery) {
        this.reports = reports;
        this.targetQuery = targetQuery;
    }

    @GetMapping("/reports/members/{memberCode}")
    @Operation(summary = "单会员报告：旧记录/采用归属/余额形成过程/阻断原因/判定历史")
    public Map<String, Object> memberReport(@PathVariable String memberCode) {
        return reports.memberReport(memberCode);
    }

    @GetMapping("/reports/blocked")
    @Operation(summary = "全部阻断交易及候选")
    public List<Map<String, Object>> blocked() {
        return reports.blockedList();
    }

    @GetMapping("/target/side")
    @Operation(summary = "当前读取侧（未开放为旧端侧，开放后为新端完整数据范围）")
    public Map<String, Object> side() {
        return targetQuery.querySide();
    }

    @GetMapping("/target/members")
    @Operation(summary = "新端会员查询（仅开放后）")
    public List<Map<String, Object>> members() {
        return targetQuery.members();
    }

    @GetMapping("/target/purchases")
    @Operation(summary = "新端交易查询（仅开放后，可按会员过滤）")
    public List<Map<String, Object>> purchases(@RequestParam(required = false) String member_code) {
        return targetQuery.purchases(member_code);
    }

    @GetMapping("/target/balances")
    @Operation(summary = "新端积分余额查询（仅开放后）")
    public List<Map<String, Object>> balances() {
        return targetQuery.balances();
    }
}
