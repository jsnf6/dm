package com.damai.service;

import cn.hutool.http.HttpRequest;
import org.springframework.stereotype.Service;

@Service
public class ProgramService {

    private static final String BASE_URL = "http://localhost:6085/damai/program/program";

    public String getDetail(Long programId) {
        return HttpRequest.post(BASE_URL + "/detail")
                .header("no_verify", "true")
                .body("{\"id\":" + programId + "}")
                .timeout(20000)
                .execute()
                .body();
    }

    public String search(String keyword) {
        return HttpRequest.post(BASE_URL + "/search")
                .header("no_verify", "true")
                .body("{\"content\":\"" + keyword + "\",\"timeType\":0,\"type\":1,\"pageNumber\":1,\"pageSize\":5}")
                .timeout(20000)
                .execute()
                .body();
    }

    public String homeList() {
        return HttpRequest.post(BASE_URL + "/home/list")
                .header("no_verify", "true")
                .body("{\"parentProgramCategoryIds\":[]}")
                .timeout(20000)
                .execute()
                .body();
    }
}