package com.damai.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "聊天请求")
public class ChatDto {

    @Schema(description = "用户消息")
    private String message;

    @Schema(description = "会话ID")
    private String sessionId;
}