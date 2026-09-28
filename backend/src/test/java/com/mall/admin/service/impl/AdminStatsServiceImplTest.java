package com.mall.admin.service.impl;

import com.mall.admin.dto.AdminStatsSummaryVO;
import com.mall.admin.dto.AdminTopProductVO;
import com.mall.admin.dto.ProductSalesDTO;
import com.mall.admin.mapper.AdminStatsMapper;
import com.mall.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理端数据看板统计单元测试：汇总 VO 组装（今日/累计窗口）、
 * Top10 增幅计算（正/负/四舍五入 1 位小数、上期销量 0 或无记录 → null）、
 * 时间窗口自然日对齐（先查上期后查本期）与非法 range 校验。
 */
@ExtendWith(MockitoExtension.class)
class AdminStatsServiceImplTest {

    @Mock
    private AdminStatsMapper adminStatsMapper;

    private AdminStatsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminStatsServiceImpl(adminStatsMapper);
    }

    // ---------- summary ----------

    @Test
    void summaryShouldAssembleTodayAndTotalFromMapper() {
        ProductSalesDTO today = salesDto(null, 30L, "300.00");
        ProductSalesDTO total = salesDto(null, 900L, "9000.00");
        // 后声明的 stub 优先匹配：any/any 命中今日窗口，isNull/isNull 命中累计口径
        when(adminStatsMapper.selectOrderSales(any(), any())).thenReturn(today);
        when(adminStatsMapper.selectOrderSales(isNull(), isNull())).thenReturn(total);
        when(adminStatsMapper.selectAiRounds(any(), any())).thenReturn(3L);
        when(adminStatsMapper.selectAiRounds(isNull(), isNull())).thenReturn(9L);
        when(adminStatsMapper.selectAiTokens(any(), any())).thenReturn(120L);
        when(adminStatsMapper.selectAiTokens(isNull(), isNull())).thenReturn(480L);

        AdminStatsSummaryVO vo = service.summary();

        assertThat(vo.getTodaySales()).isEqualTo(30L);
        assertThat(vo.getTodayAmount()).isEqualByComparingTo("300.00");
        assertThat(vo.getTotalSales()).isEqualTo(900L);
        assertThat(vo.getTotalAmount()).isEqualByComparingTo("9000.00");
        assertThat(vo.getTodayAiRounds()).isEqualTo(3L);
        assertThat(vo.getTotalAiRounds()).isEqualTo(9L);
        assertThat(vo.getTodayAiTokens()).isEqualTo(120L);
        assertThat(vo.getTotalAiTokens()).isEqualTo(480L);

        // 销售窗口：第一个调用为今日 [今天 00:00, 明天 00:00)，第二个为累计（null, null）
        ArgumentCaptor<LocalDateTime> starts = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> ends = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(adminStatsMapper, times(2)).selectOrderSales(starts.capture(), ends.capture());
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        assertThat(starts.getAllValues().get(0)).isEqualTo(todayStart);
        assertThat(ends.getAllValues().get(0)).isEqualTo(todayStart.plusDays(1));
        assertThat(starts.getAllValues().get(1)).isNull();
        assertThat(ends.getAllValues().get(1)).isNull();
    }

    // ---------- topProducts：窗口与增幅 ----------

    @Test
    void topProductsShouldUseAlignedWindowsAndPreviousPeriodFirst() {
        when(adminStatsMapper.selectTopProducts(any(), any())).thenReturn(List.of());

        service.topProducts("7d");

        // 7d = 含今天共 7 个自然日；调用顺序：先上期 [今天-13d, 今天-6d)，后本期 [今天-6d, 明天)
        ArgumentCaptor<LocalDateTime> starts = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> ends = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(adminStatsMapper, times(2)).selectTopProducts(starts.capture(), ends.capture());
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        assertThat(starts.getAllValues().get(0)).isEqualTo(todayStart.minusDays(13));
        assertThat(ends.getAllValues().get(0)).isEqualTo(todayStart.minusDays(6));
        assertThat(starts.getAllValues().get(1)).isEqualTo(todayStart.minusDays(6));
        assertThat(ends.getAllValues().get(1)).isEqualTo(todayStart.plusDays(1));
    }

    @Test
    void topProductsShouldComputeGrowthWithRoundingAndNewMarkers() {
        // 本期：p1=15 p2=8 p3=1 p4=5 p5=1；上期：p1=10 p2=0（NEW）p4=10 p5=3，p3 无上期记录（NEW）
        List<ProductSalesDTO> current = List.of(
                salesDto(1L, 15L, "150.00"),
                salesDto(2L, 8L, "80.00"),
                salesDto(3L, 1L, "10.00"),
                salesDto(4L, 5L, "50.00"),
                salesDto(5L, 1L, "10.00"));
        List<ProductSalesDTO> previous = List.of(
                salesDto(1L, 10L, "100.00"),
                salesDto(2L, 0L, "0.00"),
                salesDto(4L, 10L, "100.00"),
                salesDto(5L, 3L, "30.00"));
        // 7d 的上期窗口起点 = 本期起点（今天-6d）再前移 7 天 = 今天-13d
        LocalDateTime previousStart = LocalDate.now().minusDays(13).atStartOfDay();
        when(adminStatsMapper.selectTopProducts(any(), any())).thenAnswer(inv -> {
            LocalDateTime start = inv.getArgument(0);
            return start.equals(previousStart) ? previous : current;
        });

        List<AdminTopProductVO> result = service.topProducts("7d");

        assertThat(result).extracting(AdminTopProductVO::getProductId)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        // 正增幅：(15-10)/10*100 = 50.0
        assertThat(result.get(0).getGrowth()).isEqualByComparingTo("50.0");
        // 上期销量为 0 → null（前端显示 NEW）
        assertThat(result.get(1).getGrowth()).isNull();
        // 上期无记录 → null（NEW 边界）
        assertThat(result.get(2).getGrowth()).isNull();
        // 负增幅：(5-10)/10*100 = -50.0
        assertThat(result.get(3).getGrowth()).isEqualByComparingTo("-50.0");
        // 四舍五入 1 位小数：(1-3)/3*100 = -66.666... → -66.7
        assertThat(result.get(4).getGrowth()).isEqualByComparingTo("-66.7");
    }

    @Test
    void topProductsShouldRejectInvalidRange() {
        assertThatThrownBy(() -> service.topProducts("7x"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("range");
        assertThatThrownBy(() -> service.topProducts(null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("range");
        verifyNoInteractions(adminStatsMapper);
    }

    // ---------- 辅助 ----------

    private static ProductSalesDTO salesDto(Long productId, Long sales, String amount) {
        ProductSalesDTO dto = new ProductSalesDTO();
        dto.setProductId(productId);
        dto.setProductName(productId == null ? null : "商品" + productId);
        dto.setSales(sales);
        dto.setAmount(new BigDecimal(amount));
        return dto;
    }
}
