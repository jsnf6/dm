package com.damai.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "会话记录")
public class ConversationDto {
    private String id;           // sessionId
    private String title;
    private Long updatedAt;      // 毫秒时间戳，给前端分时间组
    private Integer messageCount;
}
