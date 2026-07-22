package com.damai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.damai.Mapper.ConversationMapper;
import com.damai.Mapper.MessageMapper;
import com.damai.common.ApiResponse;
import com.damai.context.UserContext;
import com.damai.entity.AiMessage;
import com.damai.entity.Conversation;
import com.damai.entity.ConversationDto;
import com.damai.entity.MessageDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationService {

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;


    public ApiResponse<List<ConversationDto>> getConversations() {
        String userid= UserContext.getUserId();
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Conversation::getUserId, userid)
                .orderByDesc(Conversation::getUpdatedAt);
        List<Conversation> list = conversationMapper.selectList(wrapper);
        List<ConversationDto> dtos = list.stream().map(c -> {
            ConversationDto dto = new ConversationDto();
            dto.setId(c.getId());
            dto.setTitle(c.getTitle());
            dto.setUpdatedAt(c.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            dto.setMessageCount(c.getMessageCount());
            return dto;
        }).collect(Collectors.toList());
        return ApiResponse.ok(dtos);
    }

    public ApiResponse<List<MessageDto>> getConversationdetail(String id) {
        String userId = UserContext.getUserId();
        // 校验归属
        Conversation conv = conversationMapper.selectById(id);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return ApiResponse.error("会话不存在");
        }

        LambdaQueryWrapper<AiMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiMessage::getConversationId, id)
                .orderByAsc(AiMessage::getCreateAt);
        List<AiMessage> messages = messageMapper.selectList(wrapper);

        List<MessageDto> dtos = messages.stream().map(m -> {
            MessageDto dto = new MessageDto();
            dto.setRole(m.getRole());
            dto.setContent(m.getContent());
            dto.setTime(m.getCreateTime());
            return dto;
        }).collect(Collectors.toList());
        return ApiResponse.ok(dtos);
    }

    public ApiResponse<Void> delConversationdetail(String id) {
        Conversation conv = conversationMapper.selectById(id);
        if (conv == null || !conv.getUserId().equals(UserContext.getUserId())) {
            return ApiResponse.error("无权限");
        }
        conversationMapper.deleteById(id);
        return ApiResponse.ok(null);
    }
}
