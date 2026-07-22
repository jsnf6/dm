package com.damai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;

@Data
public class ProgramPageListDtoV2 extends BasePageDto{
    private Long areaId;

    private Long parentProgramCategoryId;

    private Long programCategoryId;

    @NotNull
    private Integer timeType;

    private Date startDateTime;

    private Date endDateTime;

    private Integer type = 1;
}
