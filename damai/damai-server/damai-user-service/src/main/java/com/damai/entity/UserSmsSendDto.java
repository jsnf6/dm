package com.damai.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserSmsSendDto {
    @Schema(description ="用户手机号")
    @NotNull(message = "用户手机号不能为空")
    private String mobile;

    @Schema(description ="用户id")
    private Long userId;
}
