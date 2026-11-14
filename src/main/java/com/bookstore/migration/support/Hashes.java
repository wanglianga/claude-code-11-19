package com.bookstore.migration.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 统一哈希：按 key 排序的规范 JSON 做 SHA-256，保证两端对同一组行算出相同摘要 */
public final class Hashes {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Hashes() {
    }

    public static String sha256(Object value) {
        try {
            String json = CANONICAL.writeValueAsString(value);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("哈希计算失败", e);
        }
    }

    /** 行级摘要：按列名字典序排列，避免列顺序差异 */
    public static String rowHash(Map<String, Object> row) {
        Map<String, Object> sorted = new LinkedHashMap<>(row);
        return sha256(sorted);
    }
}
