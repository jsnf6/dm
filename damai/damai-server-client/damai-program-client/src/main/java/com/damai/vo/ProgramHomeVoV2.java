package com.damai.vo;

import lombok.Data;

import java.util.List;

@Data
public class ProgramHomeVoV2 {

    private Long categoryId;

    private String categoryName;

    private List<ProgramListVo> programListVoList;
}
