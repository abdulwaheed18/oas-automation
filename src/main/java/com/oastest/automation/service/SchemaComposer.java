package com.oastest.automation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.media.Schema;

/**
 * Collapses JSON-Schema composition keywords into a single effective schema so the rest of the
 * generator can treat everything as a plain object/leaf schema:
 *
 * <ul>
 *   <li>{@code allOf} — merged (properties and required lists combined).</li>
 *   <li>{@code oneOf} / {@code anyOf} — the first sub-schema is used as the representative.</li>
 * </ul>
 */
public final class SchemaComposer {

    private static final int MAX_DEPTH = 8;

    private SchemaComposer() {
    }

    public static Schema<?> resolve(Schema<?> schema) {
        return resolve(schema, 0);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema<?> resolve(Schema<?> schema, int depth) {
        if (schema == null || depth > MAX_DEPTH) {
            return schema;
        }
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            Schema merged = new Schema();
            merged.setType("object");
            Map<String, Schema> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();

            if (schema.getProperties() != null) {
                props.putAll(schema.getProperties());
            }
            if (schema.getRequired() != null) {
                required.addAll(schema.getRequired());
            }
            for (Object sub : schema.getAllOf()) {
                Schema<?> r = resolve((Schema<?>) sub, depth + 1);
                if (r.getProperties() != null) {
                    props.putAll(r.getProperties());
                }
                if (r.getRequired() != null) {
                    for (Object req : r.getRequired()) {
                        if (!required.contains(req)) {
                            required.add((String) req);
                        }
                    }
                }
            }
            merged.setProperties(props);
            if (!required.isEmpty()) {
                merged.setRequired(required);
            }
            return merged;
        }
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            return resolve((Schema<?>) schema.getOneOf().get(0), depth + 1);
        }
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            return resolve((Schema<?>) schema.getAnyOf().get(0), depth + 1);
        }
        return schema;
    }
}
