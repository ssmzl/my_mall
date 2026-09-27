package com.mall.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.BizException;
import com.mall.common.PageResult;
import com.mall.order.dto.OrderItemVO;
import com.mall.order.dto.OrderPageQueryDTO;
import com.mall.order.dto.OrderVO;
import com.mall.order.service.OrderService;
import com.mall.search.dto.SearchProductVO;
import com.mall.search.dto.SearchQueryDTO;
import com.mall.search.service.ProductSearchService;
import com.mall.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 工具执行器单元测试：覆盖 execute 统一入口（未知工具、登录上下文借还、
 * 业务异常转错误 JSON）、queryProduct 参数解析与高亮清理、queryUserOrders
 * 分页/状态过滤与状态文案映射、queryOrderDetail 参数校验与明细序列化。
 * mock ProductSearchService / OrderService，ObjectMapper 用真实实例。
 */
@ExtendWith(MockitoExtension.class)
class AiToolExecutorTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ProductSearchService productSearchService;
    @Mock
    private OrderService orderService;

    @Captor
    private ArgumentCaptor<SearchQueryDTO> searchQueryCaptor;
    @Captor
    private ArgumentCaptor<OrderPageQueryDTO> orderQueryCaptor;

    private ObjectMapper objectMapper;
    private AiToolExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        executor = new AiToolExecutor(productSearchService, orderService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        LoginUser.remove();
    }

    // ---------- execute 统一入口 ----------

    @Test
    void execute未知工具抛业务异常() {
        assertThatThrownBy(() -> executor.execute(null, USER_ID, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知");
    }

    @Test
    void execute为无上下文线程补充登录身份并在结束后清理() {
        assertThat(LoginUser.get()).isNull();
        String result = executor.execute(
                tool((userId, args) -> String.valueOf(LoginUser.get().getUserId())), USER_ID, argsOf("{}"));

        assertThat(result).isEqualTo(String.valueOf(USER_ID));
        assertThat(LoginUser.get()).isNull();
    }

    @Test
    void execute已有登录上下文时不覆盖且结束后保留() {
        LoginUser.set(new LoginUser(9L, "other", 0));

        String result = executor.execute(
                tool((userId, args) -> String.valueOf(LoginUser.get().getUserId())), USER_ID, argsOf("{}"));

        assertThat(result).isEqualTo("9");
        assertThat(LoginUser.get()).isNotNull();
        assertThat(LoginUser.get().getUserId()).isEqualTo(9L);
    }

    @Test
    void execute业务异常转为错误JSON不外抛() {
        String result = executor.execute(
                tool((userId, args) -> {
                    throw new BizException("商品不存在");
                }), USER_ID, argsOf("{}"));

        JsonNode node = readTree(result);
        assertThat(node.path("error").asText()).isEqualTo("商品不存在");
    }

    // ---------- queryProduct ----------

    @Test
    void queryProduct参数解析与高亮清理() {
        SearchProductVO p = new SearchProductVO();
        p.setId(990001L);
        p.setName("手机A");
        p.setSubTitle("<em>手机</em>旗舰新品");
        p.setPrice(new BigDecimal("1999.00"));
        p.setMainPic("http://img/a.jpg");
        PageResult<SearchProductVO> page = new PageResult<>();
        page.setRecords(List.of(p));
        page.setTotal(2L);
        when(productSearchService.search(any())).thenReturn(page);

        String result = executor.queryProduct(argsOf("{\"keyword\":\"手机\",\"sort\":\"priceAsc\"}"));

        ArgumentCaptor<SearchQueryDTO> captor = ArgumentCaptor.forClass(SearchQueryDTO.class);
        verify(productSearchService).search(captor.capture());
        assertThat(captor.getValue().getKeyword()).isEqualTo("手机");
        assertThat(captor.getValue().getSort()).isEqualTo("priceAsc");
        assertThat(captor.getValue().getPageNum()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(3); // 上下文体积控制

        JsonNode node = readTree(result);
        assertThat(node.path("total").asLong()).isEqualTo(2);
        JsonNode product = node.path("products").get(0);
        assertThat(product.path("id").asLong()).isEqualTo(990001L);
        assertThat(product.path("name").asText()).isEqualTo("手机A");
        assertThat(product.path("subTitle").asText()).isEqualTo("手机旗舰新品");
        assertThat(product.path("mainPic").asText()).isEqualTo("http://img/a.jpg");
        assertThat(product.path("price").decimalValue().compareTo(new BigDecimal("1999.00"))).isZero();
        assertThat(result).doesNotContain("<em>");
    }

    @Test
    void queryProduct无关键词时默认综合排序() {
        PageResult<SearchProductVO> page = new PageResult<>();
        page.setRecords(List.of());
        page.setTotal(0L);
        when(productSearchService.search(any())).thenReturn(page);

        executor.queryProduct(argsOf("{}"));

        verify(productSearchService).search(searchQueryCaptor.capture());
        assertThat(searchQueryCaptor.getValue().getKeyword()).isNull();
        assertThat(searchQueryCaptor.getValue().getSort()).isEqualTo("default");
    }

    // ---------- queryUserOrders ----------

    @Test
    void queryUserOrders状态过滤与状态文案映射() {
        OrderVO o = new OrderVO();
        o.setOrderNo("20260901120000000001");
        o.setStatus(2);
        o.setTotalAmount(new BigDecimal("99.50"));
        o.setCreateTime(LocalDateTime.of(2026, 9, 1, 12, 30, 45));
        PageResult<OrderVO> page = new PageResult<>();
        page.setRecords(List.of(o));
        page.setTotal(1L);
        when(orderService.pageMyOrders(any())).thenReturn(page);

        String result = executor.queryUserOrders(argsOf("{\"status\":2}"));

        verify(orderService).pageMyOrders(orderQueryCaptor.capture());
        assertThat(orderQueryCaptor.getValue().getStatus()).isEqualTo(2);
        assertThat(orderQueryCaptor.getValue().getPageNum()).isEqualTo(1);
        assertThat(orderQueryCaptor.getValue().getPageSize()).isEqualTo(5); // 上下文体积控制

        JsonNode order = readTree(result).path("orders").get(0);
        assertThat(order.path("orderNo").asText()).isEqualTo("20260901120000000001");
        assertThat(order.path("statusText").asText()).isEqualTo("已发货");
        assertThat(order.path("createTime").asText()).isEqualTo("2026-09-01 12:30"); // 秒被裁剪
    }

    @Test
    void queryUserOrders分页参数透传() {
        PageResult<OrderVO> page = new PageResult<>();
        page.setRecords(List.of());
        page.setTotal(0L);
        when(orderService.pageMyOrders(any())).thenReturn(page);

        executor.queryUserOrders(argsOf("{\"pageNum\":3}"));

        verify(orderService).pageMyOrders(orderQueryCaptor.capture());
        assertThat(orderQueryCaptor.getValue().getPageNum()).isEqualTo(3);
        assertThat(orderQueryCaptor.getValue().getStatus()).isNull();
    }

    // ---------- queryOrderDetail ----------

    @Test
    void queryOrderDetail缺少订单号抛业务异常() {
        assertThatThrownBy(() -> executor.queryOrderDetail(argsOf("{}")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("orderNo");
    }

    @Test
    void queryOrderDetail序列化订单项与支付时间() {
        OrderVO o = new OrderVO();
        o.setOrderNo("20260902100000000002");
        o.setStatus(1);
        o.setTotalAmount(new BigDecimal("2998.00"));
        o.setPayTime(LocalDateTime.of(2026, 9, 2, 10, 0, 0));
        o.setCreateTime(LocalDateTime.of(2026, 9, 2, 9, 58, 0));
        OrderItemVO item = new OrderItemVO();
        item.setProductName("手机A");
        item.setSkuSpec("12G+256G 曜石黑");
        item.setPrice(new BigDecimal("1499.00"));
        item.setQuantity(2);
        o.setItems(List.of(item));
        when(orderService.detail("20260902100000000002")).thenReturn(o);

        String result = executor.queryOrderDetail(argsOf("{\"orderNo\":\"20260902100000000002\"}"));

        verify(orderService).detail(eq("20260902100000000002")); // 归属校验在 OrderService 内部
        JsonNode order = readTree(result).path("order");
        assertThat(order.path("statusText").asText()).isEqualTo("已支付");
        assertThat(order.path("payTime").asText()).isEqualTo("2026-09-02 10:00");
        JsonNode itemNode = order.path("items").get(0);
        assertThat(itemNode.path("productName").asText()).isEqualTo("手机A");
        assertThat(itemNode.path("skuSpec").asText()).isEqualTo("12G+256G 曜石黑");
        assertThat(itemNode.path("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void queryOrderDetail订单项为空时返回空数组() {
        OrderVO o = new OrderVO();
        o.setOrderNo("NO1");
        o.setStatus(0);
        o.setTotalAmount(new BigDecimal("1.00"));
        o.setItems(null);
        when(orderService.detail("NO1")).thenReturn(o);

        JsonNode order = readTree(executor.queryOrderDetail(argsOf("{\"orderNo\":\"NO1\"}"))).path("order");
        assertThat(order.path("items").isArray()).isTrue();
        assertThat(order.path("items")).isEmpty();
    }

    // ---------- 辅助 ----------

    /** 构造测试用工具定义（lambda 签名与注册表中一致） */
    private AiToolRegistry.AiTool tool(AiToolRegistry.ToolFunction function) {
        return new AiToolRegistry.AiTool("testTool", "测试工具", null, function);
    }

    /** 构造工具参数树 */
    private com.fasterxml.jackson.databind.JsonNode argsOf(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试参数 JSON 非法", e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("工具结果 JSON 解析失败: " + json, e);
        }
    }
}
