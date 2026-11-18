package com.damai.jwt;

import com.alibaba.fastjson.JSONObject;
import com.damai.enums.BaseCode;
import com.damai.exception.DaMaiFrameException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;

/**
 * @program: 
 * @description: token工具
 * 
 **/
@Slf4j
public class TokenUtil {
    //使用hs256加密
     private static final SignatureAlgorithm SIGNATURE_ALGORITHM = SignatureAlgorithm.HS256;
    /**
     * 创建 JWT 令牌
     * @param id          - JWT ID（唯一标识）
     * @param info        - 用户信息（JSON字符串）
     * @param ttlMillis   - 过期时间（毫秒）
     * @param tokenSecret - 签名密钥
     */
    public static String createToken(String id, String info, long ttlMillis, String tokenSecret) {
        
        long nowMillis = System.currentTimeMillis();
        //jwt由三部分组成：header、payload、signature
        //header：包含签名算法（HS256）和类型（JWT）
        //payload：包含用户信息（JSON字符串）
        //signature：根据header和payload和tokenSecret生成的签名
        //对前两部分用密钥进行加密生成的签名，用于验证令牌的真实性和完整性
        JwtBuilder builder = Jwts.builder()
//                .setClaims(claims)
                .setId(id)
                .setIssuedAt(new Date(nowMillis))
                .setSubject(info)
                .signWith(SIGNATURE_ALGORITHM, tokenSecret);
        if (ttlMillis >= 0) {
            //设置过期时间
            builder.setExpiration(new Date(nowMillis + ttlMillis));
        }
        return builder.compact();
    }

    public static String parseToken(String token, String tokenSecret) {
        try {
            return Jwts.parser()
                    .setSigningKey(tokenSecret)
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
        }catch (ExpiredJwtException jwtException) {
            log.error("parseToken error",jwtException);
            throw new DaMaiFrameException(BaseCode.TOKEN_EXPIRE);
        }
        
    }
    
    public static void main(String[] args) {
        
         String tokenSecret = "CSYZWECHAT";
        
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("001key", "001value");
        jsonObject.put("002key", "001value");

        String token1 = TokenUtil.createToken("1", jsonObject.toJSONString(), 10000, tokenSecret);
        System.out.println("token:" + token1);
        
        
        String token2 = "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiIxIiwiaWF0IjoxNjg4NTQyODM3LCJzdWIiOiJ7XCIwMDJrZXlcIjpcIjAwMXZhbHVlXCIsXCIwMDFrZXlcIjpcIjAwMXZhbHVlXCJ9IiwiZXhwIjoxNjg4NTQyODQ3fQ.vIKcAilTn_CR3VYssNE7rBpfuCSCH_RrkmsadLWf664";
        String subject = TokenUtil.parseToken(token2, tokenSecret);
        System.out.println("解析token后的值:" + subject);
    }
}