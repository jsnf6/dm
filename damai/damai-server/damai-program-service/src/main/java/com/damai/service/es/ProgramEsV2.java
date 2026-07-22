package com.damai.service.es;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.damai.core.SpringUtil;
import com.damai.dto.ProgramListDtoV2;
import com.damai.dto.ProgramPageListDto;
import com.damai.dto.ProgramPageListDtoV2;
import com.damai.page.PageVo;
import com.damai.service.init.ProgramDocumentParamName;
import com.damai.service.tool.ProgramPageOrder;
import com.damai.util.BusinessEsHandle;
import com.damai.util.DateUtils;
import com.damai.vo.ProgramHomeVoV2;
import com.damai.vo.ProgramListVo;
import com.damai.vo.ProgramListVoV2;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class ProgramEsV2 {

    @Autowired
    private BusinessEsHandle businessEsHandle;

    @Autowired
    private RestClient restClient;

    public List<ProgramHomeVoV2> selectHomeListV2(ProgramListDtoV2 programListDtoV2) {
        List<ProgramHomeVoV2> programHomeVoV2List = new ArrayList<>();
        try {
            for(Long parentCategoryId : programListDtoV2.getParentProgramCategoryIds()) {
                SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
                BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
                ProgramHomeVoV2 programHomeVoV2 = new ProgramHomeVoV2();
                programHomeVoV2.setCategoryId(parentCategoryId);
                Long areaId = programListDtoV2.getAreaId();
                if (areaId != null) {
                    boolQueryBuilder.must(QueryBuilders.termQuery("areaId", areaId));
                } else {
                    boolQueryBuilder.must(QueryBuilders.termQuery("prime", 1));
                }
                boolQueryBuilder.must(QueryBuilders.termQuery("parentProgramCategoryId", parentCategoryId));
                sourceBuilder.query(boolQueryBuilder);
                sourceBuilder.from(0);
                sourceBuilder.size(7);
                sourceBuilder.trackTotalHits(true);
                String json = sourceBuilder.toString();
                HttpEntity entity = new NStringEntity(json, ContentType.APPLICATION_JSON);
                String indexName = SpringUtil.getPrefixDistinctionName() + "-" + ProgramDocumentParamName.INDEX_NAME;
                Request request = new Request("POST", "/" + indexName + "/_search");
                request.setEntity(entity);
                Response response = restClient.performRequest(request);
                String result = EntityUtils.toString(response.getEntity());
                JSONObject resultJson = JSON.parseObject(result);
                JSONObject hits = resultJson.getJSONObject("hits");
                Long total = hits.getJSONObject("total").getLong("value");
                JSONArray docs = hits.getJSONArray("hits");
                programHomeVoV2.setProgramListVoList(new ArrayList<>());
                if (docs != null && !docs.isEmpty()) {
                    JSONObject firstDoc = docs.getJSONObject(0);
                    JSONObject firstSource = firstDoc.getJSONObject("_source");
                    programHomeVoV2.setCategoryName(firstSource.getString("parentProgramCategoryName"));
                }
                for (int i = 0; i < docs.size(); i++) {
                    JSONObject doc = docs.getJSONObject(i);
                    String esId = doc.getString("_id");
                    JSONObject source = doc.getJSONObject("_source");
                    ProgramListVo vo = new ProgramListVo();
                    vo.setId(source.getLong("id"));
                    vo.setTitle(source.getString("title"));
                    vo.setPlace(source.getString("place"));
                    vo.setItemPicture(source.getString("itemPicture"));
                    vo.setAreaId(source.getLong("areaId"));
                    vo.setProgramCategoryId(source.getLong("programCategoryId"));
                    vo.setParentProgramCategoryId(source.getLong("parentProgramCategoryId"));
                    vo.setParentProgramCategoryName(source.getString("parentProgramCategoryName"));
                    vo.setActor(source.getString("actor"));
                    vo.setEsId(doc.getString("_id"));
                    programHomeVoV2.getProgramListVoList().add(vo);
                }
                programHomeVoV2List.add(programHomeVoV2);
            }
        } catch (IOException e) {
            log.error("查询主页异常", e);
        }
        return programHomeVoV2List;
    }

    public PageVo<ProgramListVoV2> selectPageV2(ProgramPageListDtoV2 programPageListDtoV2) {
        PageVo<ProgramListVoV2> pageVo = new PageVo<>();
        pageVo.setPageNum(programPageListDtoV2.getPageNumber());
        pageVo.setPageSize(programPageListDtoV2.getPageSize());
        List<ProgramListVoV2> list = new ArrayList<>();
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        if(Objects.nonNull(programPageListDtoV2.getAreaId())) {
            boolQueryBuilder.must(QueryBuilders.termQuery("areaId", programPageListDtoV2.getAreaId()));
        } else {
            boolQueryBuilder.must(QueryBuilders.termQuery("prime", 1));
        }
        if(Objects.nonNull(programPageListDtoV2.getParentProgramCategoryId())) {
            boolQueryBuilder.must(QueryBuilders.termQuery("parentProgramCategoryId", programPageListDtoV2.getParentProgramCategoryId()));
        }
        if(Objects.nonNull(programPageListDtoV2.getProgramCategoryId())) {
            boolQueryBuilder.must(QueryBuilders.termQuery("programCategoryId", programPageListDtoV2.getProgramCategoryId()));
        }
        if(Objects.nonNull(programPageListDtoV2.getTimeType())){
            if(programPageListDtoV2.getTimeType() == 5){
                boolQueryBuilder.must(QueryBuilders.rangeQuery("showDayTime").from(programPageListDtoV2.getStartDateTime()));
                boolQueryBuilder.must(QueryBuilders.rangeQuery("showDayTime").to(programPageListDtoV2.getEndDateTime()));
            }
            else{
                Date st= DateUtils.now();
                Date et=switchTime(st,programPageListDtoV2.getTimeType());
                boolQueryBuilder.must(QueryBuilders.rangeQuery("showDayTime").from(st));
                boolQueryBuilder.must(QueryBuilders.rangeQuery("showDayTime").to(et));
            }
        }
        sourceBuilder.query(boolQueryBuilder);
        sourceBuilder.from((programPageListDtoV2.getPageNumber() - 1) * programPageListDtoV2.getPageSize());
        sourceBuilder.size(programPageListDtoV2.getPageSize());
        ProgramPageOrder programPageOrder = getProgramPageOrder(programPageListDtoV2);
        sourceBuilder.sort(programPageOrder.sortParam, programPageOrder.sortOrder);
        String json = sourceBuilder.toString();
        try {
            HttpEntity entity = new NStringEntity(json, ContentType.APPLICATION_JSON);
            String indexName = SpringUtil.getPrefixDistinctionName() + "-" + ProgramDocumentParamName.INDEX_NAME;
            Request request = new Request("POST", "/" + indexName + "/_search");
            request.setEntity(entity);
            Response response = restClient.performRequest(request);
            String result = EntityUtils.toString(response.getEntity());
            JSONObject resultJson = JSON.parseObject(result);
            JSONObject hits = resultJson.getJSONObject("hits");
            Long total = hits.getJSONObject("total").getLong("value");
            JSONArray docs = hits.getJSONArray("hits");
            pageVo.setTotalSize(total);
            for(int i=0;i<docs.size();i++){
                JSONObject doc = docs.getJSONObject(i);
                JSONObject source = doc.getJSONObject("_source");
                ProgramListVoV2 vo = JSONObject.parseObject(source.toJSONString(), ProgramListVoV2.class);
                vo.setEsId(doc.getString("_id"));
                list.add(vo);
            }
            pageVo.setList(list);
            return pageVo;
        } catch (IOException e) {
            log.info("查询ES异常", e);
            return pageVo;
        }
    }

    private Date switchTime(Date st,Integer timeType) {
        switch (timeType) {
            case 0:
                return DateUtils.addYear(st,10);
            case 1:
                return st;
            case 2:
                return DateUtils.addDay(st, 1);
            case 3:
                return DateUtils.addDay(st, 7);
            case 4:
                return DateUtils.addDay(st, 30);
            default:
                return null;
        }
    }
    public ProgramPageOrder getProgramPageOrder(ProgramPageListDtoV2 programPageListDtoV2){
        ProgramPageOrder programPageOrder = new ProgramPageOrder();
        switch (programPageListDtoV2.getType()) {
            //推荐排序
            case 2:
                programPageOrder.sortParam = ProgramDocumentParamName.HIGH_HEAT;
                programPageOrder.sortOrder = SortOrder.DESC;
                break;
            //最近开场
            case 3:
                programPageOrder.sortParam = ProgramDocumentParamName.SHOW_TIME;
                programPageOrder.sortOrder = SortOrder.ASC;
                break;
            //最新上架
            case 4:
                programPageOrder.sortParam = ProgramDocumentParamName.ISSUE_TIME;
                programPageOrder.sortOrder = SortOrder.ASC;
                break;
            //相关度排序
            default:
                programPageOrder.sortParam = null;
                programPageOrder.sortOrder = null;
        }
        return programPageOrder;
    }
}
