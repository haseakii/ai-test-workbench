package io.aitest.contract;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;

/** Strict wire codec; DTO constructors enforce aliases and discriminants. No default typing. */
public final class ProviderJson {
    private ProviderJson(){}
    private static final JsonMapper JSON=JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    public static String write(Object value){return JSON.writeValueAsString(value);}
    public static <T> T read(String json,Class<T> type){return JSON.readValue(json,type);}
    public static <T> T read(String json,TypeReference<T> type){return JSON.readValue(json,type);}
    public static <T> ProviderTypes.Result<T> readResult(String json,Class<T> valueType){
        return JSON.readValue(json,JSON.getTypeFactory().constructParametricType(ProviderTypes.Result.class,valueType));
    }
}
