package com.mall.ai.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.ai.config.AiConfigProperties;
import com.mall.common.BizException;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 通用 LLM HTTP 客户端（OpenAI 兼容 chat completions 接口）。
 * <p>基于 JDK 17 内置 {@link HttpClient}，支持 {@code stream=true} 流式响应：
 * 逐段回调增量文本，并聚合 tool_calls 分片，最终返回完整 {@link ChatResult}。</p>
 * <p>任何失败（连接失败 / 非 200 / SSE 报错 / 解析失败）统一抛出 {@link BizException}，
 * 并在消息中携带诊断信息（状态码、错误片段）便于定位。</p>
 */
@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final AiConfigProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiClient(AiConfigProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getLlm().getConnectTimeoutSeconds()))
                .build();
    }

    /**
     * LLM 返回的单个工具调用（arguments 为 LLM 生成的 JSON 字符串，可能不完整分片已聚合）。
     */
    public record ToolCall(String id, String name, String arguments) {
    }

    /**
     * 一次流式对话的聚合结果。
     */
    @Getter
    public static class ChatResult {

        /** 完整 assistant 文本（所有增量拼接） */
        private final StringBuilder content = new StringBuilder();

        /** 聚合后的工具调用列表（无调用时为空列表） */
        private final List<ToolCall> toolCalls = new ArrayList<>();

        /** 结束原因：stop / tool_calls 等 */
        private String finishReason;

        /** 本次 LLM 调用消耗的 prompt tokens（服务端未返回 usage 时为 null） */
        private Integer promptTokens;

        /** 本次 LLM 调用消耗的 completion tokens（服务端未返回 usage 时为 null） */
        private Integer completionTokens;

        void appendContent(String delta) {
            content.append(delta);
        }
    }

    /**
     * 流式对话：发送 chat completions 请求（stream=true），边接收边回调增量文本。
     *
     * @param messages      OpenAI 消息数组（role/content，tool 消息带 tool_call_id）
     * @param tools         OpenAI tools 数组（{@code [{type:"function", function:{...}}]}），可为 null
     * @param deltaConsumer 每收到一段增量文本的回调（用于 SSE 向前端推送），可为 null
     * @return 聚合结果（全文 + tool_calls + finish_reason）
     * @throws BizException 连接失败、非 200 响应、SSE 流报错或解析失败
     */
    public ChatResult streamChat(List<Map<String, Object>> messages,
                                 List<Map<String, Object>> tools,
                                 Consumer<String> deltaConsumer) {
        AiConfigProperties.Llm llm = props.getLlm();

        Map<String, Object> body = new HashMap<>();
        body.put("model", llm.getModel());
        body.put("messages", messages);
        body.put("temperature", llm.getTemperature());
        body.put("stream", true);
        // 开启 usage 统计：最后一个 chunk（choices 为空数组）会携带 usage 节点
        body.put("stream_options", Map.of("include_usage", true));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BizException("AI 请求体序列化失败: " + e.getOriginalMessage());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(llm.getBaseUrl()))
                .timeout(Duration.ofSeconds(llm.getConnectTimeoutSeconds()))
                .header("Authorization", "Bearer " + llm.getApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<java.util.stream.Stream<String>> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            log.error("[AI] LLM 连接失败, baseUrl={}, model={}", llm.getBaseUrl(), llm.getModel(), e);
            throw new BizException("AI 服务连接失败，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("AI 请求被中断");
        }

        if (response.statusCode() != 200) {
            // 非 200 时 body 不是 SSE，仍按行收集后取片段诊断
            String errorBody = response.body().limit(20).reduce("", (a, b) -> a + b);
            log.error("[AI] LLM 响应异常, status={}, baseUrl={}, body={}", response.statusCode(), llm.getBaseUrl(), errorBody);
            throw new BizException("AI 服务响应异常(HTTP " + response.statusCode() + ")，请稍后重试");
        }

        ChatResult result = new ChatResult();
        // tool_calls 按 index 聚合分片（id/name 首个分片携带，arguments 逐段拼接）
        Map<Integer, String> idByIndex = new TreeMap<>();
        Map<Integer, String> nameByIndex = new TreeMap<>();
        Map<Integer, StringBuilder> argsByIndex = new TreeMap<>();

        try (java.util.stream.Stream<String> lines = response.body()) {
            lines.forEach(line -> parseSseLine(line, result, idByIndex, nameByIndex, argsByIndex, deltaConsumer));
        }

        idByIndex.forEach((index, id) -> result.getToolCalls().add(new ToolCall(
                id,
                nameByIndex.getOrDefault(index, ""),
                argsByIndex.getOrDefault(index, new StringBuilder()).toString())));

        if (result.getFinishReason() == null) {
            result.finishReason = "stop";
        }
        return result;
    }

    /**
     * 解析单行 SSE 数据：{@code data: {...}} / {@code data: [DONE]}，其余行（空行、注释）忽略。
     */
    private void parseSseLine(String line, ChatResult result,
                              Map<Integer, String> idByIndex, Map<Integer, String> nameByIndex,
                              Map<Integer, StringBuilder> argsByIndex, Consumer<String> deltaConsumer) {
        if (line == null || !line.startsWith("data:")) {
            return;
        }
        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new BizException("AI 流式响应解析失败: " + e.getOriginalMessage());
        }

        // 部分兼容端点以 {"error": {...}} 推送错误
        if (node.has("error")) {
            String msg = node.path("error").path("message").asText("unknown");
            log.error("[AI] LLM 流内错误: {}", msg);
            throw new BizException("AI 服务返回错误，请稍后重试");
        }

        // usage 统计（stream_options.include_usage=true 时最后一个 chunk 携带，choices 为空数组）
        JsonNode usage = node.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            if (promptTokens > 0) {
                result.promptTokens = promptTokens;
            }
            if (completionTokens > 0) {
                result.completionTokens = completionTokens;
            }
        }

        JsonNode choice = node.path("choices").path(0);
        if (choice.isMissingNode()) {
            return;
        }

        JsonNode finish = choice.path("finish_reason");
        if (!finish.isMissingNode() && !finish.isNull() && !finish.asText().isEmpty()) {
            result.finishReason = finish.asText();
        }

        JsonNode delta = choice.path("delta");
        JsonNode content = delta.path("content");
        if (!content.isMissingNode() && !content.isNull() && !content.asText().isEmpty()) {
            String text = content.asText();
            result.appendContent(text);
            if (deltaConsumer != null) {
                deltaConsumer.accept(text);
            }
        }

        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                int index = tc.path("index").asInt(0);
                JsonNode id = tc.path("id");
                if (!id.isMissingNode() && !id.isNull() && !id.asText().isEmpty()) {
                    idByIndex.put(index, id.asText());
                }
                JsonNode fnName = tc.path("function").path("name");
                if (!fnName.isMissingNode() && !fnName.isNull() && !fnName.asText().isEmpty()) {
                    nameByIndex.put(index, fnName.asText());
                }
                String argDelta = tc.path("function").path("arguments").asText("");
                if (!argDelta.isEmpty()) {
                    argsByIndex.computeIfAbsent(index, k -> new StringBuilder()).append(argDelta);
                }
            }
        }
    }
}
