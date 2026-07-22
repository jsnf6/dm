package com.damai.service;

import com.damai.Mapper.ConversationMapper;
import com.damai.Mapper.MessageMapper;
import com.damai.common.ApiResponse;
import com.damai.context.UserContext;
import com.damai.entity.AiMessage;
import com.damai.entity.ChatDto;
import com.damai.entity.Conversation;
import com.damai.tool.ScopedToolWrapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Service
public class AiService {

    private final ChatClient baseClient;
    private final ChatMemory chatMemory;
    private final ProgramService programService;
    private final OrderService orderService;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    private static final String SYSTEM_PROMPT = """
            你是"大麦票务"的智能客服，名叫"麦小助"。你拥有搜索演出、查询订单、取消订单等工具能力，根据用户需求自主判断调用哪个工具。

            行为准则：
            1. 主动服务，用户说"周杰伦"你就搜，说"我的订单"你就查，不要等用户提供ID。
            2. 充分利用对话上下文。上一轮查到的信息本轮直接引用，不要重复查询或假装不知道。
            3. 如果用户需求超出你的工具能力，诚实说明并给出替代方案，严禁编造"未登录""系统错误"等虚假理由。
            4. 同一轮对话中已成功调过 getMyOrders，说明用户已登录，禁止后续说"未识别登录状态"。
            5. 遇到工具返回空结果或错误时，解释原因并给出替代建议。
            6. 不要编造任何你无法从工具获取的数据（订单编号、演出信息等）。

            回复风格：热情不啰嗦，专业不死板，用分段和emoji让信息易读，涉及金钱/日期/状态务必准确。
            """;

    public AiService(ChatModel chatModel, ChatMemory chatMemory,
                     ProgramService programService, OrderService orderService) {
        this.chatMemory = chatMemory;
        this.programService = programService;
        this.orderService = orderService;
        this.baseClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    private ChatClient buildClient(String userId, String sessionId) {
        return baseClient.mutate()
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory)
                                .conversationId(sessionId)
                                .build()
                )
                .defaultTools(new ScopedToolWrapper(userId, programService, orderService))
                .build();
    }

    public Flux<String> chatStream(ChatDto chatDto) {
        String userId = UserContext.getUserId();
        String sessionId = chatDto.getSessionId();
        String userMessage = chatDto.getMessage();
        String userTime = nowTimeStr();
        saveMessage(userId, sessionId, "user", userMessage, userTime);

        ChatClient client = buildClient(userId, sessionId);

        StringBuilder fullResponse = new StringBuilder();
        return client.prompt()
                .user(chatDto.getMessage())
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    saveMessage(userId, sessionId, "ai", fullResponse.toString(), nowTimeStr());
                })
                .onErrorResume(e -> {
                    System.err.println("===== chatStream error: " + e.getMessage());
                    e.printStackTrace();
                    String errorMsg = "Sorry, service unavailable. Please try again.";
                    saveMessage(userId, sessionId, "ai", errorMsg, nowTimeStr());
                    return Flux.just(errorMsg);
                });
    }

    public ApiResponse<String> chat(ChatDto chatDto) {
        try {
            String userId = UserContext.getUserId();
            ChatClient client = buildClient(userId, chatDto.getSessionId());
            String content = client.prompt()
                    .user(chatDto.getMessage())
                    .call()
                    .content();
            return ApiResponse.ok(content);
        } catch (Exception e) {
            return ApiResponse.error("AI服务暂不可用，请稍后再试。");
        }
    }

    private void saveMessage(String userId, String sessionId, String role, String content, String time) {
        Conversation conv = conversationMapper.selectById(sessionId);
        if (conv == null) {
            conv = new Conversation();
            conv.setId(sessionId);
            conv.setUserId(userId);
            conv.setTitle(content.length() > 30 ? content.substring(0, 30) + "..." : content);
            conv.setMessageCount(1);
            conv.setCreatedAt(LocalDateTime.now());
            conv.setUpdatedAt(LocalDateTime.now());
            conversationMapper.insert(conv);
        } else {
            conv.setMessageCount(conv.getMessageCount() + 1);
            conv.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conv);
        }

        AiMessage msg = new AiMessage();
        msg.setConversationId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setCreateTime(time);
        msg.setCreateAt(LocalDateTime.now());
        messageMapper.insert(msg);
    }

    public String nowTimeStr() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }
}