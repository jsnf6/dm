package com.damai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_conversation")
public class Conversation {
    private String id;
    private String userId;
    private String title;
    private Integer messageCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
