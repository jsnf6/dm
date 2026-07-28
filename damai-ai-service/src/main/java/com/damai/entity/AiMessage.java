package com.damai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_message")
public class AiMessage {
    private Long id;
    private String conversationId;
    private String role;
    private String content;
    private String createTime;
    private LocalDateTime createAt;
    private Integer satisfaction;
}