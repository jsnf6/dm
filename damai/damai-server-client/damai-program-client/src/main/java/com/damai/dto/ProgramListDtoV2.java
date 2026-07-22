package com.damai.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProgramListDtoV2 {

    private Long areaId;

    @NotNull
    @Size(max=4)
    private List<Long> parentProgramCategoryIds;
}
