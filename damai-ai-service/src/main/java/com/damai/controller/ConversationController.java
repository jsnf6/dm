package com.damai.controller;

import com.damai.common.ApiResponse;
import com.damai.entity.ConversationDto;
import com.damai.entity.MessageDto;
import com.damai.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ai")
@Tag(name = "ai-conversation", description = "AI智能客服")
public class ConversationController {

    @Autowired
    private ConversationService conversationService;

    @Operation(summary = "查询历史对话")
    @GetMapping("/conversations")
    public ApiResponse<List<ConversationDto>> getConversations() {
        return conversationService.getConversations();
    }

    @Operation(summary = "查询会话详情")
    @GetMapping("/conversations/{id}")
    public ApiResponse<List<MessageDto>> getConversationdetail(@PathVariable String id) {
        return conversationService.getConversationdetail(id);
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(@PathVariable String id) {
        conversationService.delConversationdetail(id);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "消息满意度反馈")
    @PutMapping("/message/{messageId}/satisfaction")
    public ApiResponse<Void> updateSatisfaction(@PathVariable Long messageId, @RequestBody Map<String, Integer> body) {
        Integer satisfaction = body.get("satisfaction");
        return conversationService.updateSatisfaction(messageId, satisfaction);
    }
}