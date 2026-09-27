package com.mall.admin.service.impl;

import com.mall.admin.dto.AdminStatsSummaryVO;
import com.mall.admin.dto.AdminTopProductVO;
import com.mall.admin.dto.ProductSalesDTO;
import com.mall.admin.mapper.AdminStatsMapper;
import com.mall.admin.service.AdminStatsService;
import com.mall.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端数据看板统计实现。
 * <p>时间窗口按自然日对齐：今日 = [今天 00:00, 明天 00:00)；近 N 天 = [今天-(N-1) 00:00, 明天 00:00)；
 * 上一同期 = 本期窗口整体前移一个等长周期。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminStatsServiceImpl implements AdminStatsService {

    private final AdminStatsMapper adminStatsMapper;

    @Override
    public AdminStatsSummaryVO summary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);

        ProductSalesDTO todaySales = adminStatsMapper.selectOrderSales(todayStart, tomorrowStart);
        ProductSalesDTO totalSales = adminStatsMapper.selectOrderSales(null, null);

        AdminStatsSummaryVO vo = new AdminStatsSummaryVO();
        vo.setTodaySales(todaySales.getSales());
        vo.setTodayAmount(todaySales.getAmount());
        vo.setTotalSales(totalSales.getSales());
        vo.setTotalAmount(totalSales.getAmount());
        vo.setTodayAiRounds(adminStatsMapper.selectAiRounds(todayStart, tomorrowStart));
        vo.setTotalAiRounds(adminStatsMapper.selectAiRounds(null, null));
        vo.setTodayAiTokens(adminStatsMapper.selectAiTokens(todayStart, tomorrowStart));
        vo.setTotalAiTokens(adminStatsMapper.selectAiTokens(null, null));
        return vo;
    }

    @Override
    public List<AdminTopProductVO> topProducts(String range) {
        // 本期窗口 [currentStart, currentEnd) 与上一同期窗口 [previousStart, currentStart)
        LocalDateTime currentStart;
        LocalDateTime currentEnd;
        int periodDays;
        switch (range == null ? "" : range) {
            case "today" -> {
                periodDays = 1;
                currentStart = LocalDate.now().atStartOfDay();
                currentEnd = currentStart.plusDays(1);
            }
            case "7d" -> {
                periodDays = 7;
                currentStart = LocalDate.now().minusDays(6).atStartOfDay();
                currentEnd = LocalDate.now().plusDays(1).atStartOfDay();
            }
            case "30d" -> {
                periodDays = 30;
                currentStart = LocalDate.now().minusDays(29).atStartOfDay();
                currentEnd = LocalDate.now().plusDays(1).atStartOfDay();
            }
            default -> throw new BizException("非法的时间范围参数 range: " + range + "（可选 today / 7d / 30d）");
        }
        LocalDateTime previousStart = currentStart.minusDays(periodDays);

        Map<Long, ProductSalesDTO> previousByProduct = adminStatsMapper
                .selectTopProducts(previousStart, currentStart).stream()
                .collect(Collectors.toMap(ProductSalesDTO::getProductId, Function.identity()));

        List<AdminTopProductVO> result = new ArrayList<>();
        for (ProductSalesDTO current : adminStatsMapper.selectTopProducts(currentStart, currentEnd)) {
            AdminTopProductVO vo = new AdminTopProductVO();
            vo.setProductId(current.getProductId());
            vo.setProductName(current.getProductName());
            vo.setSales(current.getSales());
            vo.setAmount(current.getAmount());
            vo.setGrowth(growth(current.getSales(), previousByProduct.get(current.getProductId())));
            result.add(vo);
        }
        return result;
    }

    /**
     * 销量增幅 =（本期-上期)/上期*100，保留 1 位小数；上期销量为 0（含无记录）时返回 null（前端显示 NEW）。
     */
    private BigDecimal growth(Long currentSales, ProductSalesDTO previous) {
        long previousSales = previous == null || previous.getSales() == null ? 0 : previous.getSales();
        if (previousSales == 0) {
            return null;
        }
        long current = currentSales == null ? 0 : currentSales;
        return BigDecimal.valueOf((current - previousSales) * 100L)
                .divide(BigDecimal.valueOf(previousSales), 1, RoundingMode.HALF_UP);
    }
}
