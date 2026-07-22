package com.damai.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class ProgramListVoV2 {
//    showTime	演出时间	string
//    showDayTime	演出时间(精确到天)	string
//    showWeekTime	演出时间所在的星期	string
//    minPrice	最低价格	number
//    maxPrice	最高价格	number
//            esId
    private Long id;
    private String title;
    private String actor;
    private String place;
    private String itemPicture;
    private Long areaId;
    private String areaName;
    private Long programCategoryId;
    private String programCategoryName;
    private Long parentProgramCategoryId;
    private String parentProgramCategoryName;
    private Date showTime;
    private Date showDayTime;
    private String showWeekTime;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private String esId;
}
