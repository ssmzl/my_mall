package com.mall.ai.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 工具执行器：承接 {@link AiToolRegistry} 定义的各工具 lambda，注入现有业务服务执行查询，
 * 执行结果精简为小体积 JSON 字符串（直接回填给 LLM 作 tool 消息）。
 * <p>订单类工具内部依赖 {@link LoginUser} ThreadLocal 做归属校验，而工具可能在
 * SSE 异步线程中执行，因此 {@link #execute} 统一为无上下文线程补充当前用户身份。</p>
 */
@Component
public class AiToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiToolExecutor.class);

    /** 传给 LLM 的时间格式（去掉秒，节省上下文） */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 订单状态码 → 文案 */
    private static final Map<Integer, String> STATUS_TEXT = Map.of(
            0, "待支付", 1, "已支付", 2, "已发货", 3, "已完成", 4, "已取消");

    /** queryProduct 返回的商品条数上限（控制上下文体积） */
    private static final int PRODUCT_LIMIT = 3;

    /** 查订单列表时每页条数上限（控制上下文体积） */
    private static final int ORDER_PAGE_SIZE = 5;

    private final ProductSearchService productSearchService;
    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    public AiToolExecutor(ProductSearchService productSearchService,
                          OrderService orderService,
                          ObjectMapper objectMapper) {
        this.productSearchService = productSearchService;
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行单个工具调用：补充用户上下文后委托工具 lambda，返回 JSON 字符串结果。
     * <p>工具执行失败不抛出到 SSE 主流程外——返回错误 JSON 让 LLM 自行向用户解释。</p>
     *
     * @param tool   注册表中的工具定义
     * @param userId 当前登录用户 ID
     * @param args   LLM 生成的参数树
     * @return 工具结果 JSON 字符串（成功结果或 {"error": "..."}）
     */
    public String execute(AiToolRegistry.AiTool tool, Long userId, JsonNode args) {
        if (tool == null) {
            throw new BizException("未知的 AI 工具调用");
        }
        boolean borrowedContext = LoginUser.get() == null;
        if (borrowedContext) {
            LoginUser.set(new LoginUser(userId, null, 0));
        }
        try {
            String result = tool.function().execute(userId, args);
            log.info("[AI] 工具执行成功, tool={}, userId={}, args={}", tool.name(), userId, args);
            return result;
        } catch (BizException e) {
            log.warn("[AI] 工具执行业务失败, tool={}, userId={}, args={}, reason={}", tool.name(), userId, args, e.getMessage());
            return toJson(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[AI] 工具执行异常, tool={}, userId={}, args={}", tool.name(), userId, args, e);
            return toJson(Map.of("error", "工具执行失败，请稍后重试"));
        } finally {
            if (borrowedContext) {
                LoginUser.remove();
            }
        }
    }

    /**
     * queryProduct：按关键词/排序搜索在售商品，返回前 {@value PRODUCT_LIMIT} 条摘要。
     */
    public String queryProduct(JsonNode args) {
        SearchQueryDTO query = new SearchQueryDTO();
        if (args.hasNonNull("keyword") && !args.get("keyword").asText().isBlank()) {
            query.setKeyword(args.get("keyword").asText());
        }
        if (args.hasNonNull("categoryId")) {
            query.setCategoryId(args.get("categoryId").asLong());
        }
        query.setSort(args.path("sort").asText("default"));
        query.setPageNum(1);
        query.setPageSize(PRODUCT_LIMIT);

        PageResult<SearchProductVO> page = productSearchService.search(query);
        List<Map<String, Object>> products = page.getRecords().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    // 副标题去掉 ES 高亮 <em> 标签后再喂给 LLM
                    m.put("subTitle", p.getSubTitle() == null ? null : p.getSubTitle().replaceAll("</?em>", ""));
                    m.put("price", p.getPrice());
                    m.put("mainPic", p.getMainPic());
                    return m;
                })
                .toList();
        return toJson(Map.of("total", page.getTotal(), "products", products));
    }

    /**
     * queryUserOrders：查询当前用户订单列表（归属由 OrderService 基于 LoginUser 校验）。
     */
    public String queryUserOrders(JsonNode args) {
        OrderPageQueryDTO query = new OrderPageQueryDTO();
        if (args.hasNonNull("status")) {
            query.setStatus(args.get("status").asInt());
        }
        if (args.hasNonNull("pageNum") && args.get("pageNum").asInt() > 0) {
            query.setPageNum(args.get("pageNum").asInt());
        }
        query.setPageSize(ORDER_PAGE_SIZE);

        PageResult<OrderVO> page = orderService.pageMyOrders(query);
        List<Map<String, Object>> orders = page.getRecords().stream()
                .map(this::toOrderSummary)
                .toList();
        return toJson(Map.of("total", page.getTotal(), "orders", orders));
    }

    /**
     * queryOrderDetail：按订单号查询详情（OrderService 内部校验归属，防越权）。
     */
    public String queryOrderDetail(JsonNode args) {
        String orderNo = args.path("orderNo").asText(null);
        if (orderNo == null || orderNo.isBlank()) {
            throw new BizException("缺少参数 orderNo");
        }
        OrderVO order = orderService.detail(orderNo);
        Map<String, Object> detail = toOrderSummary(order);
        detail.put("payTime", formatTime(order.getPayTime()));
        detail.put("items", order.getItems() == null ? List.of() : order.getItems().stream()
                .map(this::toItemSummary)
                .toList());
        return toJson(Map.of("order", detail));
    }

    /** 订单摘要（列表与详情共用的公共字段） */
    private Map<String, Object> toOrderSummary(OrderVO o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", o.getOrderNo());
        m.put("status", o.getStatus());
        m.put("statusText", STATUS_TEXT.getOrDefault(o.getStatus(), "未知"));
        m.put("totalAmount", o.getTotalAmount());
        m.put("createTime", formatTime(o.getCreateTime()));
        return m;
    }

    /** 订单项摘要 */
    private Map<String, Object> toItemSummary(OrderItemVO it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productName", it.getProductName());
        m.put("skuSpec", it.getSkuSpec());
        m.put("price", it.getPrice());
        m.put("quantity", it.getQuantity());
        return m;
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FMT);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("[AI] 工具结果序列化失败", e);
            throw new BizException("AI 工具结果序列化失败");
        }
    }
}
