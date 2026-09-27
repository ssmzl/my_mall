package com.mall.admin.service;

import com.mall.admin.dto.AdminStatsSummaryVO;
import com.mall.admin.dto.AdminTopProductVO;

import java.util.List;

/**
 * 管理端数据看板统计服务。
 */
public interface AdminStatsService {

    /**
     * 汇总统计：今日/累计销量与销售额、今日/累计 AI 对话轮数与 token 消耗。
     */
    AdminStatsSummaryVO summary();

    /**
     * 商品销量 Top10（按所选时间范围），并对比等长上一周期计算销量增幅百分比。
     *
     * @param range 时间范围：today（今日）/ 7d（近7天）/ 30d（近30天）
     * @return Top10 列表；上期销量为 0 时 growth 为 null（前端显示 NEW）
     * @throws com.mall.common.BizException range 非法
     */
    List<AdminTopProductVO> topProducts(String range);
}
