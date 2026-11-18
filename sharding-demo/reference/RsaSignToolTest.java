package com.example.gateway.util;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RsaSignToolTest {

    private static final KeyPair PAIR = generate();
    private static final String PRIVATE_KEY = Base64.getEncoder().encodeToString(PAIR.getPrivate().getEncoded());
    private static final String PUBLIC_KEY = Base64.getEncoder().encodeToString(PAIR.getPublic().getEncoded());

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> params(String body, String nonce, String timestamp) {
        Map<String, String> params = new HashMap<>(4);
        params.put("businessBody", body);
        params.put("nonce", nonce);
        params.put("timestamp", timestamp);
        return params;
    }

    @Test
    void 正常签名与验签应该通过() {
        Map<String, String> params = params("{\"userId\":10086}", "n-1", "1730000000000");
        String sign = RsaSignTool.sign(params, PRIVATE_KEY);
        assertTrue(RsaSignTool.verify(params, sign, PUBLIC_KEY));
    }

    @Test
    void body被改一个字符应该验签失败() {
        Map<String, String> params = params("{\"userId\":10086}", "n-1", "1730000000000");
        String sign = RsaSignTool.sign(params, PRIVATE_KEY);
        Map<String, String> tampered = params("{\"userId\":10087}", "n-1", "1730000000000");
        assertFalse(RsaSignTool.verify(tampered, sign, PUBLIC_KEY));
    }

    @Test
    void 篡改nonce或timestamp也应该验签失败() {
        Map<String, String> params = params("{\"userId\":10086}", "n-1", "1730000000000");
        String sign = RsaSignTool.sign(params, PRIVATE_KEY);
        assertFalse(RsaSignTool.verify(params("{\"userId\":10086}", "n-2", "1730000000000"), sign, PUBLIC_KEY));
        assertFalse(RsaSignTool.verify(params("{\"userId\":10086}", "n-1", "1730000000001"), sign, PUBLIC_KEY));
    }

    @Test
    void 待签串应该与参数插入顺序无关() {
        Map<String, String> a = params("{\"userId\":1}", "n", "1");
        Map<String, String> b = new HashMap<>();
        b.put("timestamp", "1");
        b.put("businessBody", "{\"userId\":1}");
        b.put("nonce", "n");
        assertEquals(RsaSignTool.buildSignContent(a), RsaSignTool.buildSignContent(b));
        assertEquals("businessBody={\"userId\":1}&nonce=n&timestamp=1", RsaSignTool.buildSignContent(a));
        // 同一个待签串, 两次签名结果一致(RSA PKCS#1 v1.5 是确定性的)
        assertEquals(RsaSignTool.sign(a, PRIVATE_KEY), RsaSignTool.sign(b, PRIVATE_KEY));
    }

    @Test
    void 用别人的公钥验签应该失败() {
        Map<String, String> params = params("{\"userId\":10086}", "n-1", "1730000000000");
        String sign = RsaSignTool.sign(params, PRIVATE_KEY);
        String otherPublicKey = Base64.getEncoder().encodeToString(generate().getPublic().getEncoded());
        assertFalse(RsaSignTool.verify(params, sign, otherPublicKey));
    }

    @Test
    void 签名串是垃圾时应该返回false而不是抛异常() {
        Map<String, String> params = params("{}", "n", "1");
        assertFalse(RsaSignTool.verify(params, "not-a-base64-sign", PUBLIC_KEY));
    }
}