package com.example.gateway.filter;

import com.example.gateway.config.GatewaySignProperties;
import com.example.gateway.constant.GatewayConstant;
import com.example.gateway.util.RsaSignTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 请求验签 + 防重放过滤器。
 *
 * 协议: 业务 JSON 直接放 body, 签名相关放请求头
 *   body        原始业务 JSON 字符串
 *   X-Channel-Code  渠道编码
 *   X-Timestamp     客户端毫秒时间戳
 *   X-Nonce         一次性随机串
 *   X-Sign          对 businessBody&nonce&timestamp(字典序) 做 SHA256withRSA 后 Base64
 *
 * 三道关: 时间窗 -> 验签 -> nonce 去重(顺序不能反, 见下面注释)。
 */
@Component
public class SignValidationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SignValidationFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GatewaySignProperties properties;
    private final ReactiveStringRedisTemplate redisTemplate;

    public SignValidationFilter(GatewaySignProperties properties, ReactiveStringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!needVerify(path)) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String code = request.getHeaders().getFirst(GatewayConstant.CHANNEL_CODE);
        String sign = request.getHeaders().getFirst(GatewayConstant.SIGN);
        String timestamp = request.getHeaders().getFirst(GatewayConstant.TIMESTAMP);
        String nonce = request.getHeaders().getFirst(GatewayConstant.NONCE);

        if (!StringUtils.hasText(code) || !StringUtils.hasText(sign)
                || !StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)) {
            return reject(exchange, "缺少签名请求头");
        }
        String publicKey = properties.getChannels().get(code);
        if (!StringUtils.hasText(publicKey)) {
            return reject(exchange, "渠道未配置公钥: " + code);
        }
        long timestampValue;
        try {
            timestampValue = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return reject(exchange, "时间戳格式非法");
        }
        long offsetMs = Math.abs(System.currentTimeMillis() - timestampValue);
        if (offsetMs > properties.getTimestampWindowSeconds() * 1000L) {
            return reject(exchange, "请求已过期, 时钟偏移超出窗口");
        }

        return readBody(request)
                .flatMap(body -> validate(exchange, chain, code, nonce, timestamp, sign, publicKey, body));
    }

    private Mono<Void> validate(ServerWebExchange exchange, GatewayFilterChain chain, String code,
                                String nonce, String timestamp, String sign, String publicKey, String body) {
        Map<String, String> params = new HashMap<>(4);
        params.put("businessBody", body);
        params.put("nonce", nonce);
        params.put("timestamp", timestamp);
        if (!RsaSignTool.verify(params, sign, publicKey)) {
            return reject(exchange, "签名校验失败");
        }
        // 验签通过之后才去写 nonce: 否则任何未认证的请求都能往 Redis 里灌垃圾 key, 变成免费的资源攻击面
        String nonceKey = GatewayConstant.NONCE_KEY_PREFIX + code + ":" + nonce;
        return redisTemplate.opsForValue()
                .setIfAbsent(nonceKey, "1", Duration.ofSeconds(properties.getNonceTtlSeconds()))
                .flatMap(firstTime -> {
                    if (!Boolean.TRUE.equals(firstTime)) {
                        return reject(exchange, "重复请求, 该 nonce 已被使用");
                    }
                    return forward(exchange, chain, code, body);
                });
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, String code, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        // 必须用响应侧(Netty)的 bufferFactory: 下游 NettyRoutingFilter 会把 DataBuffer 强转成 NettyDataBuffer,
        // 用 DefaultDataBufferFactory 拼出来的堆内 buffer 会在那里 ClassCastException
        DataBufferFactory factory = exchange.getResponse().bufferFactory();
        String userId = parseUserId(body);

        ServerHttpRequest decorated = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                // 客户端自己传的 X-User-Id 一律丢掉, 只认网关从验签过的 body 里解出来的那个
                headers.remove(GatewayConstant.USER_ID);
                if (userId != null) {
                    headers.add(GatewayConstant.USER_ID, userId);
                }
                return headers;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                // 每次 wrap 一个新的 buffer: 复用同一个 DataBuffer 会被下游读掉读写位置, 第二次读就是空 body
                return Flux.just(factory.wrap(bytes));
            }
        };

        ServerWebExchange mutated = exchange.mutate().request(decorated).build();
        mutated.getAttributes().put(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR, factory.wrap(bytes));
        log.info("验签通过 channel={} path={} userId={}", code, exchange.getRequest().getPath().value(), userId);
        return chain.filter(mutated);
    }

    /** WebFlux 的 body 只能读一次, 所以读完要自己重新 wrap 给下游 */
    private Mono<String> readBody(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody())
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return new String(bytes, StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty("");
    }

    private String parseUserId(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(body);
            JsonNode userId = node == null ? null : node.get("userId");
            return userId == null || userId.isNull() ? null : userId.asText();
        } catch (Exception e) {
            // 不是 JSON 就不注入, 交给后面的业务自己报错
            return null;
        }
    }

    private boolean needVerify(String path) {
        for (String pattern : properties.getVerifyPaths()) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        log.warn("验签拦截 path={} 原因={}", exchange.getRequest().getPath().value(), message);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String json = "{\"code\":" + HttpStatus.FORBIDDEN.value() + ",\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 排在限流和转发之前; 验签必须先跑, 否则限流拿到的是可伪造的 userId
        return -100;
    }
}