package com.damai.controller;

import com.damai.common.ApiResponse;
import com.damai.dto.*;
import com.damai.page.PageVo;
import com.damai.service.ProgramService;
import com.damai.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 节目 控制层
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/program")
@Tag(name = "program", description = "节目")
public class ProgramController {
    
    @Autowired
    private ProgramService programService;
    
    
    @Operation(summary  = "添加")
    @PostMapping(value = "/add")
    public ApiResponse<Long> add(@Valid @RequestBody ProgramAddDto programAddDto) {
        return ApiResponse.ok(programService.add(programAddDto));
    }
    
    @Operation(summary  = "搜索")
    @PostMapping(value = "/search")
    public ApiResponse<PageVo<ProgramListVo>> search(@Valid @RequestBody ProgramSearchDto programSearchDto) {
        return ApiResponse.ok(programService.search(programSearchDto));
    }
    
    @Operation(summary  = "查询主页列表")
    @PostMapping(value = "/home/list")
    public ApiResponse<List<ProgramHomeVo>> selectHomeList(@Valid @RequestBody ProgramListDto programListDto) {
        return ApiResponse.ok(programService.selectHomeList(programListDto));
    }

    @PostMapping(value="/home/list/v2")
    public ApiResponse<List<ProgramHomeVoV2>> selectHomeListV2(@Valid @RequestBody ProgramListDtoV2 programListDtoV2) {
        return ApiResponse.ok(programService.selectHomeListV2(programListDtoV2));
    }

    @Operation(summary  = "查询分页列表")
    @PostMapping(value = "/page")
    public ApiResponse<PageVo<ProgramListVo>> selectPage(@Valid @RequestBody ProgramPageListDto programPageListDto) {
        return ApiResponse.ok(programService.selectPage(programPageListDto));
    }

    @PostMapping(value="/page/v2")
    public ApiResponse<PageVo<ProgramListVoV2>> selectPageV2(@Valid @RequestBody ProgramPageListDtoV2 programPageListDtoV2) {
        return ApiResponse.ok(programService.selectPageV2(programPageListDtoV2));
    }

    @Operation(summary  = "查询推荐列表")
    @PostMapping(value = "/recommend/list")
    public ApiResponse<List<ProgramListVo>> recommendList(@Valid @RequestBody ProgramRecommendListDto programRecommendListDto) {
        return ApiResponse.ok(programService.recommendList(programRecommendListDto));
    }
    
    @Operation(summary  = "查询详情(根据id)")
    @PostMapping(value = "/detail")
    public ApiResponse<ProgramVo> getDetail(@Valid @RequestBody ProgramGetDto programGetDto) {
        return ApiResponse.ok(programService.detail(programGetDto));
    }
    
    @Operation(summary  = "查询详情V1(根据id)")
    @PostMapping(value = "/detail/v1")
    public ApiResponse<ProgramVo> getDetailV1(@Valid @RequestBody ProgramGetDto programGetDto) {
        return ApiResponse.ok(programService.detailV1(programGetDto));
    }
    
    @Operation(summary  = "查询详情V2(根据id)")
    @PostMapping(value = "/detail/v2")
    public ApiResponse<ProgramVo> getDetailV2(@Valid @RequestBody ProgramGetDto programGetDto) {
        return ApiResponse.ok(programService.detailV2(programGetDto));
    }
    
    @Operation(summary  = "节目失效(根据id)")
    @PostMapping(value = "/invalid")
    public ApiResponse<Boolean> invalid(@Valid @RequestBody ProgramInvalidDto programInvalidDto) {
        return ApiResponse.ok(programService.invalid(programInvalidDto));
    }
    
    @Operation(summary  = "查看节目详情本地缓存(根据id)")
    @PostMapping(value = "/local/detail")
    public ApiResponse<ProgramVo> localDetail(@Valid @RequestBody ProgramGetDto programGetDto) {
        return ApiResponse.ok(programService.localDetail(programGetDto));
    }
}
