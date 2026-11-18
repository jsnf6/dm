package com.damai.pro.limit;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @program: 
 * @description: 线上限流工具属性
 * 
 **/
@Data
@Component
public class RateLimiterProperty {

    @Value("${rate.switch:false}")
    private Boolean rateSwitch;

    @Value("${rate.permits:200}")
    private Integer ratePermits;
}
