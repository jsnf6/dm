package com.damai.tool;

import com.damai.context.UserContext;
import com.damai.service.OrderService;
import com.damai.service.ProgramService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ToolFunctions {

    @Autowired
    private ProgramService programService;

    @Autowired
    private OrderService orderService;

    @Tool(description = "Search performances/programs by keyword (e.g. artist name, show name). Call this when user asks about shows without knowing the exact program ID.")
    public String searchPrograms(
            @ToolParam(description = "Search keyword, e.g. artist name or show name") String keyword
    ) {
        if (keyword == null || keyword.isBlank()) {
            return "Error: search keyword is required. Please ask the user what they want to search for.";
        }
        return programService.search(keyword);
    }

    @Tool(description = "Get the homepage/recommended performance list. Call this when user asks what shows are available, hot shows, or recent performances.")
    public String getHomePrograms() {
        return programService.homeList();
    }

    @Tool(description = "Get detailed information of a specific performance/program by its ID. Call this when user has a specific program ID or when you extracted it from search results.")
    public String getProgramDetail(
            @ToolParam(description = "The program ID number") Long programId
    ) {
        if (programId == null) {
            return "Error: program ID is required.";
        }
        return programService.getDetail(programId);
    }

    @Tool(description = "Get the current user's order list. Call this when user asks about their orders, recent purchases, or order status.")
    public String getMyOrders() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            return "Error: unable to identify the current user. Please ask the user to log in first.";
        }
        return orderService.getList(Long.valueOf(userId));
    }

    @Tool(description = "Get detailed information of a specific order by its order number. Call this when user provides an order number or when you extracted it from order list results.")
    public String getOrderDetail(
            @ToolParam(description = "The order number") Long orderNumber
    ) {
        if (orderNumber == null) {
            return "Error: order number is required.";
        }
        return orderService.getDetail(orderNumber);
    }
}