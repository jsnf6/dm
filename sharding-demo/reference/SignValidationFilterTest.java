package com.example.gateway.filter;

import com.example.gateway.config.GatewaySignProperties;
import com.example.gateway.constant.GatewayConstant;
import com.example.gateway.util.RsaSignTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 直接调用过滤器, 不启 HTTP: 构造 MockServerWebExchange + 假 chain 即可。
 * Redis 用 Mockito 打桩, 方便把"nonce 已存在"这种分支单独测出来。
 */
class SignValidationFilterTest {

    private static final KeyPair PAIR = generate();
    private static final String PRIVATE_KEY = Base64.getEncoder().encodeToString(PAIR.getPrivate().getEncoded());
    private static final String PUBLIC_KEY = Base64.getEncoder().encodeToString(PAIR.getPublic().getEncoded());
    private static final String CODE = "client-app";
    private static final String BODY = "{\"userId\":10086,\"programId\":1,\"buyCount\":1}";

    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveValueOperations<String, String> valueOps;
    private GatewaySignProperties properties;

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = Mockito.mock(ReactiveStringRedisTemplate.class);
        valueOps = Mockito.mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 默认: nonce 是第一次出现
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        properties = new GatewaySignProperties();
        properties.setChannels(Map.of(CODE, PUBLIC_KEY));
        properties.setVerifyPaths(List.of("/order/**"));
        properties.setTimestampWindowSeconds(300);
        properties.setNonceTtlSeconds(300);
    }

    private static String sign(String body, String nonce, String timestamp) {
        return RsaSignTool.sign(Map.of("businessBody", body, "nonce", nonce, "timestamp", timestamp), PRIVATE_KEY);
    }

    private static Map<String, String> signedHeaders(String body, String nonce) {
        return signedHeaders(body, nonce, String.valueOf(System.currentTimeMillis()));
    }

    private static Map<String, String> signedHeaders(String body, String nonce, String timestamp) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(GatewayConstant.CHANNEL_CODE, CODE);
        headers.put(GatewayConstant.TIMESTAMP, timestamp);
        headers.put(GatewayConstant.NONCE, nonce);
        headers.put(GatewayConstant.SIGN, sign(body, nonce, timestamp));
        return headers;
    }

    private static final class Result {
        final MockServerWebExchange original;
        final ServerWebExchange forwarded;

        Result(MockServerWebExchange original, ServerWebExchange forwarded) {
            this.original = original;
            this.forwarded = forwarded;
        }

        /** 没被拦下就是 200(过滤器没动响应状态) */
        int status() {
            HttpStatusCode status = original.getResponse().getStatusCode();
            return status == null ? 200 : status.value();
        }
    }

    private Result call(String path, String body, Map<String, String> headers) {
        MockServerHttpRequest.BodyBuilder builder = MockServerHttpRequest.post(path)
                .contentType(MediaType.APPLICATION_JSON);
        headers.forEach(builder::header);
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.body(body));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };
        new SignValidationFilter(properties, redisTemplate).filter(exchange, chain).block(Duration.ofSeconds(5));
        return new Result(exchange, forwarded.get());
    }

    private static String readBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return new String(bytes, StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .block(Duration.ofSeconds(5));
    }

    @Test
    void 签名正确应该放行_注入XUserId_且下游仍能读到完整body() {
        Result result = call("/order/order", BODY, signedHeaders(BODY, "nonce-ok"));
        assertNotNull(result.forwarded, "签名正确必须放行");
        assertEquals(200, result.status());
        assertEquals("10086", result.forwarded.getRequest().getHeaders().getFirst(GatewayConstant.USER_ID));
        assertEquals(BODY, readBody(result.forwarded), "下游必须能重新读到 body(验证 body 缓存没被读空)");
    }

    @Test
    void body被篡改应该返回403() {
        Map<String, String> headers = signedHeaders(BODY, "nonce-tamper");
        Result result = call("/order/order", "{\"userId\":10086,\"programId\":1,\"buyCount\":999}", headers);
        assertNull(result.forwarded, "body 被改过不能放行");
        assertEquals(403, result.status());
    }

    @Test
    void 缺少签名头应该返回403() {
        Map<String, String> headers = signedHeaders(BODY, "nonce-miss");
        headers.remove(GatewayConstant.SIGN);
        Result result = call("/order/order", BODY, headers);
        assertNull(result.forwarded);
        assertEquals(403, result.status());
    }

    @Test
    void 未知渠道应该返回403() {
        Map<String, String> headers = signedHeaders(BODY, "nonce-channel");
        headers.put(GatewayConstant.CHANNEL_CODE, "unknown-app");
        Result result = call("/order/order", BODY, headers);
        assertNull(result.forwarded);
        assertEquals(403, result.status());
    }

    @Test
    void 时间戳超出窗口应该返回403() {
        String expired = String.valueOf(System.currentTimeMillis() - 400_000L);
        Result result = call("/order/order", BODY, signedHeaders(BODY, "nonce-expired", expired));
        assertNull(result.forwarded, "过期请求(重放)不能放行");
        assertEquals(403, result.status());
    }

    @Test
    void nonce已经用过应该返回403() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(false));
        Result result = call("/order/order", BODY, signedHeaders(BODY, "nonce-replay"));
        assertNull(result.forwarded, "同一个 nonce 第二次来必须拦住");
        assertEquals(403, result.status());
    }

    @Test
    void 客户端自己传的XUserId应该被覆盖掉() {
        Map<String, String> headers = signedHeaders(BODY, "nonce-spoof");
        headers.put(GatewayConstant.USER_ID, "1");
        Result result = call("/order/order", BODY, headers);
        assertNotNull(result.forwarded);
        assertEquals("10086", result.forwarded.getRequest().getHeaders().getFirst(GatewayConstant.USER_ID),
                "必须用网关解析出来的 userId, 不能信客户端传的");
    }

    @Test
    void 不在验签范围内的路径直接放行() {
        Result result = call("/program/getByProgramId", "", new LinkedHashMap<>());
        assertNotNull(result.forwarded, "没配验签的路径不该被拦");
        assertEquals(200, result.status());
        assertNull(result.forwarded.getRequest().getHeaders().getFirst(GatewayConstant.USER_ID));
    }
}