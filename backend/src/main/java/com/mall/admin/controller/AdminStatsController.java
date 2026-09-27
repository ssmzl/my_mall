package com.mall.admin.controller;

import com.mall.admin.dto.AdminStatsSummaryVO;
import com.mall.admin.dto.AdminTopProductVO;
import com.mall.admin.service.AdminStatsService;
import com.mall.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端数据看板接口（/admin/** 由登录拦截器要求管理员角色）。
 */
@Tag(name = "管理端-数据看板", description = "销量/销售额/AI 对话轮数/AI token 汇总与 Top10 商品销量榜")
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    @Operation(summary = "汇总统计", description = "今日/累计销量与销售额、今日/累计 AI 对话轮数与 token 消耗")
    @GetMapping("/summary")
    public Result<AdminStatsSummaryVO> summary() {
        return Result.ok(adminStatsService.summary());
    }

    @Operation(summary = "Top10 商品销量榜", description = "range=today|7d|30d，含对比上一同期的销量增幅（上期为 0 时为 null，前端显示 NEW）")
    @GetMapping("/top-products")
    public Result<List<AdminTopProductVO>> topProducts(@RequestParam String range) {
        return Result.ok(adminStatsService.topProducts(range));
    }
}
