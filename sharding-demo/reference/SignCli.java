package com.example.gateway.tool;

import com.example.gateway.util.RsaSignTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端侧签名工具: 模拟"渠道方"用自己手里的私钥给请求签名。
 * 网关只拿得到公钥, 所以它只能验签、伪造不了签名 —— 这就是防篡改的根。
 *
 * 用法: java -cp target/classes com.example.gateway.tool.SignCli <私钥文件> <渠道编码> [body或@body文件]
 * 输出: 4 行请求头, 供 curl/PowerShell 直接使用
 *
 * 注意用 @文件 传 body: PowerShell 5.1 把参数交给 java 时会把 JSON 里的双引号吃掉,
 * 于是"签名的 body"和"实际发出去的 body"就不是同一个了, 验签必然失败。
 */
public final class SignCli {

    private SignCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: SignCli <私钥文件> <渠道编码> [body或@body文件]");
            System.exit(2);
        }
        String privateKey = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8).trim();
        String code = args[1];
        String body = args.length > 2 ? readBodyArg(args[2]) : "";

        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("businessBody", body);
        params.put("nonce", nonce);
        params.put("timestamp", timestamp);
        String sign = RsaSignTool.sign(params, privateKey);

        // 第一行打印 body 长度, 方便核对"签的"和"发的"是不是同一个
        System.out.println("#bodyLength=" + body.getBytes(StandardCharsets.UTF_8).length);
        System.out.println("X-Channel-Code=" + code);
        System.out.println("X-Timestamp=" + timestamp);
        System.out.println("X-Nonce=" + nonce);
        System.out.println("X-Sign=" + sign);
    }

    private static String readBodyArg(String arg) throws Exception {
        if (arg.startsWith("@")) {
            return Files.readString(Path.of(arg.substring(1)), StandardCharsets.UTF_8);
        }
        return arg;
    }
}