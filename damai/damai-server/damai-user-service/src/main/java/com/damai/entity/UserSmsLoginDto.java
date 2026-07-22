package com.damai.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserSmsLoginDto {

    @Schema(description ="用户手机号")
    @NotNull(message = "用户手机号不能为空")
    private String mobile;

    @Schema(description ="短信验证码")
    @NotNull(message = "短信验证码不能为空")
    private String smsCode;

    private String code="0001";
}
