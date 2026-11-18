package com.example.gateway.util;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * RSA 签名/验签工具。
 * 协议: 待签串 = 参与签名的参数按 key 字典序拼成 k=v, 用 & 连接(不含 sign 本身)。
 * 算法: SHA256withRSA, 结果 Base64。
 *
 * 为什么强调"按字典序拼接": JSON 反序列化再序列化后字段顺序是会变的,
 * 直接对序列化结果签名, 换一次序列化就验不过了。规范化拼接才是稳定做法。
 */
public final class RsaSignTool {

    private static final String KEY_ALGORITHM = "RSA";
    private static final String SIGN_ALGORITHM = "SHA256withRSA";

    private RsaSignTool() {
    }

    /** 规范化待签串: 剔除 sign, 其余按 key 字典序拼 k=v&k=v */
    public static String buildSignContent(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if ("sign".equals(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            sorted.put(entry.getKey(), entry.getValue());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    public static String sign(Map<String, String> params, String privateKeyBase64) {
        return sign(buildSignContent(params), privateKeyBase64);
    }

    public static String sign(String content, String privateKeyBase64) {
        try {
            PrivateKey privateKey = KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)));
            Signature signature = Signature.getInstance(SIGN_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("RSA 签名失败: " + e.getMessage(), e);
        }
    }

    public static boolean verify(Map<String, String> params, String signBase64, String publicKeyBase64) {
        return verify(buildSignContent(params), signBase64, publicKeyBase64);
    }

    public static boolean verify(String content, String signBase64, String publicKeyBase64) {
        try {
            PublicKey publicKey = KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            Signature signature = Signature.getInstance(SIGN_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signBase64));
        } catch (Exception e) {
            // 签名串本身格式错/Base64 解不开, 一律当作验签失败, 不要把异常抛给调用方
            return false;
        }
    }
}