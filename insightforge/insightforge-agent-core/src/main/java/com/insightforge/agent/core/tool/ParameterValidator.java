package com.insightforge.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具参数校验器（L1 组件指标「参数 Schema 合规率 100%」的工程落地）。
 *
 * 用 networknt 的 JSON Schema 校验器：模型（或本地桩）产出的 args 必须先过这一关，
 * 不合规直接打回（记 RETRY span + 计 schema 违规），绝不拿脏参数去碰业务。
 * 这是「防幻觉/误调用」的第一道确定性闸门（设计文档 §5.6 第一层）。
 */
public final class ParameterValidator {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ParameterValidator() {
    }

    /**
     * @return 校验错误列表；空列表表示通过。
     */
    public static List<String> validate(String jsonSchema, Map<String, Object> args) {
        List<String> errors = new ArrayList<>();
        try {
            JsonNode schemaNode = OBJECT_MAPPER.readTree(jsonSchema);
            JsonNode dataNode = OBJECT_MAPPER.valueToTree(args);
            JsonSchema schema = FACTORY.getSchema(schemaNode);
            Set<ValidationMessage> messages = schema.validate(dataNode);
            for (ValidationMessage m : messages) {
                errors.add(m.getMessage());
            }
        } catch (Exception e) {
            errors.add("参数/Schema 解析失败: " + e.getMessage());
        }
        return errors;
    }
}
