package com.damai.entity;

import lombok.Data;

@Data
public class MessageDto {
    private Long id;
    private String role;
    private String content;
    private String time;
    private Integer satisfaction;
}