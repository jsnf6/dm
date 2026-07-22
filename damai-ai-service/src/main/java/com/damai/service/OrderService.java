package com.damai.service;

import cn.hutool.http.HttpRequest;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private static final String BASE_URL = "http://localhost:6085/damai/order/order";

    public String getDetail(Long orderNumber) {
        return HttpRequest.post(BASE_URL + "/get")
                .header("no_verify", "true")
                .body("{\"orderNumber\":" + orderNumber + "}")
                .timeout(20000)
                .execute()
                .body();
    }

    public String getList(Long userId) {
        return HttpRequest.post(BASE_URL + "/select/list")
                .header("no_verify", "true")
                .body("{\"userId\":" + userId + "}")
                .timeout(20000)
                .execute()
                .body();
    }

    public String cancel(Long orderNumber) {
        return HttpRequest.post(BASE_URL + "/cancel")
                .header("no_verify", "true")
                .body("{\"orderNumber\":" + orderNumber + "}")
                .timeout(20000)
                .execute()
                .body();
    }
}