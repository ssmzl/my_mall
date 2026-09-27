package com.mall.ai.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.ai.config.AiConfigProperties;
import com.mall.ai.llm.OpenAiClient;
import com.mall.ai.service.AiMessageService;
import com.mall.ai.tool.AiToolExecutor;
import com.mall.ai.tool.AiToolRegistry;
import com.mall.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 聊天编排服务单元测试：覆盖普通文本流式回复、tool_calls 工具调用链路、
 * 工具调用轮数上限强制收敛、LLM 异常兜底 error 事件、未启用快速失败、
 * 上下文 Redis 读写（分轮 FIFO 截断 / 损坏重置）与客户端断开中断。
 * mock OpenAiClient / AiToolExecutor / AiMessageService / RedisTemplate；
 * AiToolRegistry 用真实实例（Schema 为代码常量，lambda 委托给 mock 的 Executor）。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final String CONTEXT_KEY = "mall:ai:context:" + USER_ID;

    @Mock
    private OpenAiClient openAiClient;
    @Mock
    private AiToolExecutor toolExecutor;
    @Mock
    private AiMessageService aiMessageService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @Captor
    private ArgumentCaptor<List<Map<String, Object>>> messagesCaptor;
    @Captor
    private ArgumentCaptor<List<Map<String, Object>>> toolsCaptor;
    @Captor
    private ArgumentCaptor<String> jsonCaptor;

    private AiConfigProperties props;
    private ObjectMapper objectMapper;
    private ChatServiceImpl chatService;

    /** mock 的 SseEmitter 与捕获到的 SseEventBuilder（按发送顺序，断言时再解析） */
    private SseEmitter emitter;
    private List<SseEmitter.SseEventBuilder> builders;

    @BeforeEach
    void setUp() throws IOException {
        props = new AiConfigProperties();
        props.setEnabled(true);
        props.getLlm().setBaseUrl("https://api.deepseek.com/v1/chat/completions");
        props.getLlm().setApiKey("test-key");
        props.getLlm().setModel("deepseek-chat");
        objectMapper = new ObjectMapper();
        // Registry 是纯常量定义（Schema + 委托 lambda），用真实实例
        AiToolRegistry toolRegistry = new AiToolRegistry(toolExecutor, objectMapper);
        chatService = new ChatServiceImpl(props, openAiClient, toolRegistry, toolExecutor,
                aiMessageService, stringRedisTemplate, objectMapper);

        emitter = mock(SseEmitter.class);
        builders = new ArrayList<>();
        // lenient：断开用例会用抛异常的 stub 覆盖此行为
        lenient().doAnswer(inv -> {
            builders.add(inv.getArgument(0));
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ---------- 用例 ----------

    @Test
    void 普通文本流式回复并写回上下文() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CONTEXT_KEY)).thenReturn(null);
        when(openAiClient.streamChat(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> delta = inv.getArgument(2);
            delta.accept("你好");
            delta.accept("！");
            return chatResult("你好！", "stop", null);
        });

        chatService.streamChat(USER_ID, "你好", emitter);

        // SSE 事件序列：两个 message 增量 + done
        List<SseEvent> events = capturedEvents();
        assertThat(events).extracting(SseEvent::event).containsExactly("message", "message", "done");
        assertThat(events.get(0).data()).isEqualTo("你好");
        assertThat(events.get(1).data()).isEqualTo("！");
        assertThat(events.get(2).data()).isEqualTo("[DONE]");
        verify(emitter).complete();

        // 请求 messages：system 在最前，最后是当前 user 消息
        verify(openAiClient).streamChat(messagesCaptor.capture(), any(), any());
        List<Map<String, Object>> messages = messagesCaptor.getValue();
        assertThat(messages.get(0)).containsEntry("role", "system");
        assertThat(messages.get(messages.size() - 1))
                .containsEntry("role", "user")
                .containsEntry("content", "你好");

        // 上下文写回 Redis：user + assistant 两消息，TTL 取配置值
        verify(valueOps).set(eq(CONTEXT_KEY), jsonCaptor.capture(),
                eq(Duration.ofMinutes(props.getContext().getTtlMinutes())));
        List<Map<String, Object>> saved = readContext(jsonCaptor.getValue());
        assertThat(saved).hasSize(2)
                .extracting(m -> m.get("role"))
                .containsExactly("user", "assistant");
        assertThat(saved.get(1)).containsEntry("content", "你好！");

        verify(aiMessageService).saveRound(USER_ID, "你好", "你好！", null, null, null, null);
    }

    @Test
    void tool_calls触发工具执行后回填再请求总结() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CONTEXT_KEY)).thenReturn(null);
        String toolResultJson = "{\"total\":1,\"products\":[{\"id\":990001,\"name\":\"手机A\"}]}";
        AtomicInteger calls = new AtomicInteger();
        when(openAiClient.streamChat(anyList(), any(), any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                return chatResult("", "tool_calls",
                        List.of(new OpenAiClient.ToolCall("call-1", "queryProduct", "{\"keyword\":\"手机\"}")));
            }
            // 真实 OpenAiClient 会把增量文本回调给 deltaConsumer，这里保持一致
            Consumer<String> delta = inv.getArgument(2);
            delta.accept("为您找到");
            delta.accept("以下手机");
            return chatResult("为您找到以下手机", "stop", null);
        });
        when(toolExecutor.execute(any(), eq(USER_ID), any())).thenReturn(toolResultJson);

        chatService.streamChat(USER_ID, "有什么手机", emitter);

        // 事件序列：tool_call → tool_result → message（总结增量）→ done
        List<SseEvent> events = capturedEvents();
        assertThat(events).extracting(SseEvent::event)
                .containsExactly("tool_call", "tool_result", "message", "message", "done");
        assertThat(events.get(0).data()).isEqualTo("{\"name\":\"queryProduct\"}");
        assertThat(events.get(1).data()).isEqualTo(toolResultJson);
        assertThat(events.get(2).data()).isEqualTo("为您找到");
        assertThat(events.get(3).data()).isEqualTo("以下手机");

        // 第二次 LLM 请求：messages 含 assistant(tool_calls) 与 tool 结果回填
        verify(openAiClient, times(2)).streamChat(messagesCaptor.capture(), any(), any());
        List<Map<String, Object>> second = messagesCaptor.getAllValues().get(1);
        Map<String, Object> assistantWithCalls = second.stream()
                .filter(m -> "assistant".equals(m.get("role"))).findFirst().orElseThrow();
        assertThat(assistantWithCalls).containsKey("tool_calls");
        Map<String, Object> toolMsg = second.stream()
                .filter(m -> "tool".equals(m.get("role"))).findFirst().orElseThrow();
        assertThat(toolMsg)
                .containsEntry("tool_call_id", "call-1")
                .containsEntry("content", toolResultJson);

        // 持久化：toolCallsJson 含工具名、toolResultJson 为工具结果
        verify(aiMessageService).saveRound(eq(USER_ID), eq("有什么手机"), eq("为您找到以下手机"),
                jsonCaptor.capture(), eq(toolResultJson), isNull(), isNull());
        assertThat(jsonCaptor.getValue()).contains("queryProduct");

        // 上下文写回：user → assistant(tool_calls) → tool → assistant
        verify(valueOps).set(eq(CONTEXT_KEY), jsonCaptor.capture(), any(Duration.class));
        List<Map<String, Object>> saved = readContext(jsonCaptor.getValue());
        assertThat(saved).extracting(m -> m.get("role"))
                .containsExactly("user", "assistant", "tool", "assistant");
    }

    @Test
    void 工具调用超过上限后去掉tools强制收敛() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CONTEXT_KEY)).thenReturn(null);
        // LLM 每轮都返回 tool_calls，验证最多 3 轮后第 4 次请求不带 tools
        when(openAiClient.streamChat(anyList(), any(), any()))
                .thenAnswer(inv -> chatResult("", "tool_calls",
                        List.of(new OpenAiClient.ToolCall("call-x", "queryProduct", "{}"))));
        when(toolExecutor.execute(any(), eq(USER_ID), any())).thenReturn("{}");

        chatService.streamChat(USER_ID, "随便查查", emitter);

        verify(openAiClient, times(4)).streamChat(anyList(), toolsCaptor.capture(), any());
        assertThat(toolsCaptor.getAllValues().get(2)).isNotNull();
        assertThat(toolsCaptor.getAllValues().get(3)).isNull();
        List<SseEvent> events = capturedEvents();
        assertThat(events).hasSize(3 * 2 + 1); // 3 轮 tool_call/tool_result + done
        assertThat(events.get(events.size() - 1).event()).isEqualTo("done");
        verify(emitter).complete();
    }

    @Test
    void LLM异常时发送error事件且不持久化() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CONTEXT_KEY)).thenReturn(null);
        when(openAiClient.streamChat(anyList(), any(), any()))
                .thenThrow(new BizException("AI 服务连接失败，请稍后重试"));

        chatService.streamChat(USER_ID, "你好", emitter);

        List<SseEvent> events = capturedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("error");
        assertThat(events.get(0).data()).isEqualTo("AI 服务异常，请稍后重试");
        verify(emitter).complete();
        verify(aiMessageService, never()).saveRound(anyLong(), anyString(), anyString(), any(), any(), any(), any());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 未启用时快速失败不请求LLM() {
        props.setEnabled(false);

        chatService.streamChat(USER_ID, "你好", emitter);

        List<SseEvent> events = capturedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("error");
        assertThat(events.get(0).data()).contains("未启用");
        verify(emitter).complete();
        verifyNoInteractions(openAiClient, aiMessageService);
    }

    @Test
    void 上下文按轮FIFO截断保留最近N轮() throws Exception {
        props.getContext().setMaxRounds(2);
        List<Map<String, Object>> history = List.of(
                Map.of("role", "user", "content", "第一问"),
                Map.of("role", "assistant", "content", "第一答"),
                Map.of("role", "user", "content", "第二问"),
                Map.of("role", "assistant", "content", "第二答"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CONTEXT_KEY)).thenReturn(objectMapper.writeValueAsString(history));
        when(openAiClient.streamChat(anyList(), any(), any()))
                .thenReturn(chatResult("第三答", "stop", null));

        chatService.streamChat(USER_ID, "第三问", emitter);

        verify(valueOps).set(eq(CONTEXT_KEY), jsonCaptor.capture(), any(Duration.class));
        List<Map<String, Object>> saved = readContext(jsonCaptor.getValue());
        // 保留：第二问/第二答 + 第三问/新回复，共 2 轮；第一轮被丢弃
        assertThat(saved).extracting(m -> m.get("role"))
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(saved.get(0)).containsEntry("content", "第二问");
        assertThat(saved.get(2)).containsEntry("content", "第三问");
        // 发送给 LLM 的历史只含保留轮次 + system
        verify(openAiClient).streamChat(messagesCaptor.capture(), any(), any());
        assertThat(messagesCaptor.getValue()).hasSize(1 + 4 + 1);
    }

    @Test
    void 上下文缓存损坏时重置为新会话() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CONTEXT_KEY)).thenReturn("{bad json");
        when(openAiClient.streamChat(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> delta = inv.getArgument(2);
            delta.accept("好的");
            return chatResult("好的", "stop", null);
        });

        chatService.streamChat(USER_ID, "你好", emitter);

        List<SseEvent> events = capturedEvents();
        assertThat(events).extracting(SseEvent::event).containsExactly("message", "done");
        verify(valueOps).set(eq(CONTEXT_KEY), jsonCaptor.capture(), any(Duration.class));
        List<Map<String, Object>> saved = readContext(jsonCaptor.getValue());
        assertThat(saved).extracting(m -> m.get("role")).containsExactly("user", "assistant");
    }

    @Test
    void 客户端断开时中断流程不再持久化() throws IOException {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CONTEXT_KEY)).thenReturn(null);
        // 首个 message 事件发送即失败（连接断开），error 事件发送同样失败
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        when(openAiClient.streamChat(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> delta = inv.getArgument(2);
            delta.accept("你");
            return chatResult("你好", "stop", null);
        });

        chatService.streamChat(USER_ID, "你好", emitter);

        verify(emitter).complete();
        verify(aiMessageService, never()).saveRound(anyLong(), anyString(), anyString(), any(), any(), any(), any());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    // ---------- 辅助 ----------

    /** 断言时把捕获到的 builder 解析为事件（保持发送顺序） */
    private List<SseEvent> capturedEvents() {
        return builders.stream().map(ChatServiceImplTest::toSseEvent).toList();
    }

    private List<Map<String, Object>> readContext(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    /**
     * 反射构造 {@link OpenAiClient.ChatResult}（appendContent 为包级私有，字段无 setter）。
     */
    private static OpenAiClient.ChatResult chatResult(String content, String finishReason,
                                                      List<OpenAiClient.ToolCall> toolCalls) {
        try {
            OpenAiClient.ChatResult result = new OpenAiClient.ChatResult();
            var append = OpenAiClient.ChatResult.class.getDeclaredMethod("appendContent", String.class);
            append.setAccessible(true);
            append.invoke(result, content);
            var finish = OpenAiClient.ChatResult.class.getDeclaredField("finishReason");
            finish.setAccessible(true);
            finish.set(result, finishReason);
            if (toolCalls != null) {
                var calls = OpenAiClient.ChatResult.class.getDeclaredField("toolCalls");
                calls.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<OpenAiClient.ToolCall> list = (List<OpenAiClient.ToolCall>) calls.get(result);
                list.addAll(toolCalls);
            }
            return result;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("构造 ChatResult 失败", e);
        }
    }

    /**
     * 从 SseEventBuilder 提取事件名与数据：调用实现类的 build() 得到按序 wire 文本，
     * 再按 SSE 语法解析（不依赖 Spring 内部 token 布局）。
     */
    private static SseEvent toSseEvent(SseEmitter.SseEventBuilder builder) {
        try {
            var build = builder.getClass().getMethod("build");
            build.setAccessible(true); // SseEventBuilderImpl 为包级私有类
            Set<?> tokens = (Set<?>) build.invoke(builder);
            StringBuilder wire = new StringBuilder();
            for (Object token : tokens) {
                wire.append(((ResponseBodyEmitter.DataWithMediaType) token).getData());
            }
            String text = wire.toString();
            Matcher eventMatcher = Pattern.compile("event:(.+)\\n").matcher(text);
            Matcher dataMatcher = Pattern.compile("data:(.*)\\n").matcher(text);
            String event = eventMatcher.find() ? eventMatcher.group(1) : "message";
            StringBuilder data = new StringBuilder();
            while (dataMatcher.find()) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(dataMatcher.group(1));
            }
            return new SseEvent(event, data.toString());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("解析 SseEventBuilder 失败", e);
        }
    }

    /** 解析后的 SSE 事件（事件名 + 数据文本） */
    private record SseEvent(String event, String data) {
    }
}
