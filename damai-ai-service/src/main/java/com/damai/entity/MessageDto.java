package com.damai.entity;

import lombok.Data;

@Data
public class MessageDto {
    private String role;     // user / ai
    private String content;
    private String time;     // "14:30"
}
