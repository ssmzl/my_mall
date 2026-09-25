package com.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.admin.dto.AdminSeckillActivityAddDTO;
import com.mall.admin.dto.AdminSeckillActivityUpdateDTO;
import com.mall.admin.dto.AdminSeckillProductAddDTO;
import com.mall.common.BizException;
import com.mall.product.entity.PmsProduct;
import com.mall.product.entity.PmsSku;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.seckill.entity.SmsSeckillActivity;
import com.mall.seckill.entity.SmsSeckillProduct;
import com.mall.seckill.mapper.SeckillActivityMapper;
import com.mall.seckill.mapper.SeckillProductMapper;
import com.mall.seckill.service.impl.SeckillServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端秒杀活动配置单元测试：时间校验、上线预热 Redis、活动商品归属校验与删除清理。
 */
@ExtendWith(MockitoExtension.class)
class AdminSeckillServiceImplTest {

    private static final Long ACTIVITY_ID = 1L;
    private static final Long SP_ID = 101L;
    private static final Long PRODUCT_ID = 11L;
    private static final Long SKU_ID = 21L;
    private static final String STOCK_KEY = SeckillServiceImpl.STOCK_KEY_PREFIX + SP_ID;

    @Mock
    private SeckillActivityMapper activityMapper;

    @Mock
    private SeckillProductMapper seckillProductMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private SkuMapper skuMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AdminSeckillServiceImpl service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SmsSeckillActivity.class);
        TableInfoHelper.initTableInfo(assistant, SmsSeckillProduct.class);
        service = new AdminSeckillServiceImpl(activityMapper, seckillProductMapper,
                productMapper, skuMapper, stringRedisTemplate);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 模拟自增主键回填（非所有用例都会 insert，用 lenient）
        lenient().doAnswer(inv -> {
            SmsSeckillActivity a = inv.getArgument(0);
            a.setId(ACTIVITY_ID);
            return 1;
        }).when(activityMapper).insert(any(SmsSeckillActivity.class));
        lenient().doAnswer(inv -> {
            SmsSeckillProduct sp = inv.getArgument(0);
            sp.setId(SP_ID);
            return 1;
        }).when(seckillProductMapper).insert(any(SmsSeckillProduct.class));
    }

    @Test
    void addActivityShouldDefaultOfflineWithoutRedis() {
        AdminSeckillActivityAddDTO dto = activityDTO();

        service.addActivity(dto);

        ArgumentCaptor<SmsSeckillActivity> captor = ArgumentCaptor.forClass(SmsSeckillActivity.class);
        verify(activityMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isZero();
        // 下线活动不预热 Redis
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    @Test
    void addActivityShouldWarmUpWhenOnline() {
        AdminSeckillActivityAddDTO dto = activityDTO();
        dto.setStatus(1);
        when(seckillProductMapper.selectList(any())).thenReturn(List.of(seckillProduct()));

        service.addActivity(dto);

        verify(valueOperations).set(eq(STOCK_KEY), eq("5"));
    }

    @Test
    void addActivityShouldRejectInvalidTimeRange() {
        AdminSeckillActivityAddDTO dto = activityDTO();
        dto.setEndTime(dto.getStartTime()); // 结束时间等于开始时间

        assertThatThrownBy(() -> service.addActivity(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("结束时间");
    }

    @Test
    void updateActivityShouldWarmUpWhenGoingOnline() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(0));
        when(seckillProductMapper.selectList(any())).thenReturn(List.of(seckillProduct()));
        AdminSeckillActivityUpdateDTO dto = new AdminSeckillActivityUpdateDTO();
        dto.setStatus(1);

        service.updateActivity(ACTIVITY_ID, dto);

        verify(activityMapper).updateById(any(SmsSeckillActivity.class));
        verify(valueOperations).set(eq(STOCK_KEY), eq("5"));
    }

    @Test
    void updateActivityShouldRejectInvalidStatus() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(0));
        AdminSeckillActivityUpdateDTO dto = new AdminSeckillActivityUpdateDTO();
        dto.setStatus(2);

        assertThatThrownBy(() -> service.updateActivity(ACTIVITY_ID, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法");
    }

    @Test
    void updateActivityShouldRejectWhenNotExists() {
        when(activityMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateActivity(404L, new AdminSeckillActivityUpdateDTO()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("活动不存在");
    }

    @Test
    void addSeckillProductShouldWriteRedisWhenActivityOnline() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(1));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product());
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku());

        service.addSeckillProduct(productAddDTO());

        ArgumentCaptor<SmsSeckillProduct> captor = ArgumentCaptor.forClass(SmsSeckillProduct.class);
        verify(seckillProductMapper).insert(captor.capture());
        assertThat(captor.getValue().getLimitPerUser()).isEqualTo(1); // 缺省限购 1 件
        verify(valueOperations).set(eq(STOCK_KEY), eq("5"));
    }

    @Test
    void addSeckillProductShouldNotWriteRedisWhenActivityOffline() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(0));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product());
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku());
        AdminSeckillProductAddDTO dto = productAddDTO();
        dto.setLimitPerUser(2);

        service.addSeckillProduct(dto);

        verify(seckillProductMapper).insert(any(SmsSeckillProduct.class));
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    @Test
    void addSeckillProductShouldRejectSkuNotBelongToProduct() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(1));
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product());
        PmsSku otherSku = sku();
        otherSku.setProductId(999L);
        when(skuMapper.selectById(SKU_ID)).thenReturn(otherSku);

        assertThatThrownBy(() -> service.addSeckillProduct(productAddDTO()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于该商品");
    }

    @Test
    void addSeckillProductShouldRejectWhenProductNotExists() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(1));
        when(productMapper.selectById(404L)).thenReturn(null);
        AdminSeckillProductAddDTO dto = productAddDTO();
        dto.setProductId(404L);

        assertThatThrownBy(() -> service.addSeckillProduct(dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品不存在");
    }

    @Test
    void deleteSeckillProductShouldRemoveDbAndRedisKey() {
        when(seckillProductMapper.selectById(SP_ID)).thenReturn(seckillProduct());

        service.deleteSeckillProduct(SP_ID);

        verify(seckillProductMapper).deleteById(SP_ID);
        verify(stringRedisTemplate).delete(STOCK_KEY);
    }

    @Test
    void deleteSeckillProductShouldRejectWhenNotExists() {
        when(seckillProductMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteSeckillProduct(404L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("秒杀商品不存在");
    }

    // ---------------- 构造数据 ----------------

    private static AdminSeckillActivityAddDTO activityDTO() {
        AdminSeckillActivityAddDTO dto = new AdminSeckillActivityAddDTO();
        dto.setName("双 11 尖货秒杀");
        dto.setStartTime(LocalDateTime.now().plusDays(1));
        dto.setEndTime(LocalDateTime.now().plusDays(2));
        return dto;
    }

    private static AdminSeckillProductAddDTO productAddDTO() {
        AdminSeckillProductAddDTO dto = new AdminSeckillProductAddDTO();
        dto.setActivityId(ACTIVITY_ID);
        dto.setProductId(PRODUCT_ID);
        dto.setSkuId(SKU_ID);
        dto.setSeckillPrice(new BigDecimal("1.00"));
        dto.setSeckillStock(5);
        return dto;
    }

    private static SmsSeckillActivity activity(int status) {
        SmsSeckillActivity a = new SmsSeckillActivity();
        a.setId(ACTIVITY_ID);
        a.setName("测试活动");
        a.setStartTime(LocalDateTime.now().minusHours(1));
        a.setEndTime(LocalDateTime.now().plusHours(1));
        a.setStatus(status);
        return a;
    }

    private static SmsSeckillProduct seckillProduct() {
        SmsSeckillProduct sp = new SmsSeckillProduct();
        sp.setId(SP_ID);
        sp.setActivityId(ACTIVITY_ID);
        sp.setProductId(PRODUCT_ID);
        sp.setSkuId(SKU_ID);
        sp.setSeckillPrice(new BigDecimal("1.00"));
        sp.setSeckillStock(5);
        sp.setLimitPerUser(1);
        return sp;
    }

    private static PmsProduct product() {
        PmsProduct p = new PmsProduct();
        p.setId(PRODUCT_ID);
        p.setName("测试商品");
        p.setStatus(1);
        return p;
    }

    private static PmsSku sku() {
        PmsSku s = new PmsSku();
        s.setId(SKU_ID);
        s.setProductId(PRODUCT_ID);
        s.setSkuCode("SKU-001");
        return s;
    }
}
