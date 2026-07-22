package com.damai.controller;

import com.damai.common.ApiResponse;
import com.damai.entity.ChatDto;
import com.damai.entity.ConversationDto;
import com.damai.service.AiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/ai")
@Tag(name = "ai-chat", description = "AI智能客服")
public class AiController {

    @Autowired
    private AiService aiService;


    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> sendChatStream(@RequestBody ChatDto chatDto) {
        return aiService.chatStream(chatDto);
    }

    @Operation(summary = "发送聊天消息")
    @PostMapping("/chat/send")
    public ApiResponse<String> sendChat(@RequestBody ChatDto chatDto) {
        return aiService.chat(chatDto);
    }

}