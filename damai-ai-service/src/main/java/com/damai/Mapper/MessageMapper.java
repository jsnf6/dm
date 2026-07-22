package com.damai.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.damai.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<AiMessage> {
}
