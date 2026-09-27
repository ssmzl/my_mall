package com.mall.ai.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 工具（Function Calling）注册表。
 * <p>集中定义每个工具的 name / description / JSON Schema parameters / 执行 lambda，
 * 并负责产出 OpenAI 兼容的 {@code tools} 数组供 {@code OpenAiClient} 请求携带。</p>
 * <p>执行 lambda 统一签名 {@code (userId, args) -> JSON 字符串}，实际业务委托给
 * {@link AiToolExecutor}（依赖方向：Registry → Executor，避免循环依赖）。</p>
 */
@Component
public class AiToolRegistry {

    /** 工具执行函数：入参为当前用户 ID 与 LLM 生成的参数树，返回 JSON 字符串结果 */
    @FunctionalInterface
    public interface ToolFunction {
        String execute(Long userId, JsonNode args);
    }

    /**
     * 单个工具定义。
     */
    public record AiTool(String name, String description, JsonNode parameters, ToolFunction function) {
    }

    private final ObjectMapper objectMapper;

    /** 保持定义顺序，便于日志与排查 */
    private final Map<String, AiTool> toolMap = new LinkedHashMap<>();

    public AiToolRegistry(AiToolExecutor executor, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register("queryProduct",
                "按关键词搜索商城在售商品，返回前 3 条摘要（id/name/price/mainPic）。"
                        + "当用户想找商品、比价、询问\"有什么XX\"时调用。",
                """
                {
                  "type": "object",
                  "properties": {
                    "keyword": {"type": "string", "description": "商品关键词，如：手机、连衣裙"},
                    "sort": {"type": "string", "enum": ["default", "sales", "priceAsc", "priceDesc"],
                             "description": "排序：default 综合 / sales 销量 / priceAsc 价格升序 / priceDesc 价格降序"}
                  },
                  "required": []
                }
                """,
                (userId, args) -> executor.queryProduct(args));

        register("queryUserOrders",
                "查询当前登录用户的订单列表（分页，可按状态过滤），返回订单号/状态/金额/时间/商品摘要。"
                        + "当用户询问\"我的订单\"\"最近买了什么\"时调用。",
                """
                {
                  "type": "object",
                  "properties": {
                    "status": {"type": "integer", "description": "订单状态过滤：0待支付 1已支付 2已发货 3已完成 4已取消，不传表示全部"},
                    "pageNum": {"type": "integer", "description": "页码，默认 1"}
                  },
                  "required": []
                }
                """,
                (userId, args) -> executor.queryUserOrders(args));

        register("queryOrderDetail",
                "按订单号查询订单详情（归属校验，只能查自己的订单），返回状态/金额/支付时间/订单项明细。",
                """
                {
                  "type": "object",
                  "properties": {
                    "orderNo": {"type": "string", "description": "订单编号"}
                  },
                  "required": ["orderNo"]
                }
                """,
                (userId, args) -> executor.queryOrderDetail(args));
    }

    private void register(String name, String description, String parametersJson, ToolFunction function) {
        JsonNode parameters;
        try {
            parameters = objectMapper.readTree(parametersJson);
        } catch (JsonProcessingException e) {
            // Schema 是代码内常量，解析失败属于启动期致命错误，快速失败
            throw new IllegalStateException("AI 工具 " + name + " 的 JSON Schema 非法: " + e.getOriginalMessage(), e);
        }
        toolMap.put(name, new AiTool(name, description, parameters, function));
    }

    /**
     * 按名称获取工具定义，不存在返回 null。
     */
    public AiTool get(String name) {
        return toolMap.get(name);
    }

    /**
     * 产出 OpenAI 兼容的 tools 数组：{@code [{type:"function", function:{name, description, parameters}}]}。
     */
    public List<Map<String, Object>> toolSchemas() {
        return toolMap.values().stream()
                .map(t -> Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of(
                                "name", t.name(),
                                "description", t.description(),
                                "parameters", t.parameters())))
                .toList();
    }
}
