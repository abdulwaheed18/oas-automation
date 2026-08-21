package com.oastest.automation.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.media.Schema;

/**
 * Produces valid sample values from an OpenAPI {@link Schema}. These form the "baseline" request
 * from which the generator derives negative cases by mutating exactly one field at a time.
 */
public final class SchemaSampler {

    private SchemaSampler() {
    }

    /** Returns the effective JSON type of a schema, tolerating OpenAPI 3.0 and 3.1 shapes. */
    @SuppressWarnings("rawtypes")
    public static String type(Schema schema) {
        if (schema == null) {
            return null;
        }
        if (schema.getType() != null) {
            return schema.getType();
        }
        Set<String> types = schema.getTypes();
        if (types != null && !types.isEmpty()) {
            for (String t : types) {
                if (!"null".equals(t)) {
                    return t;
                }
            }
        }
        if (schema.getProperties() != null) {
            return "object";
        }
        return null;
    }

    /** Builds a valid sample value tree for the given schema. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object valid(Schema schema) {
        if (schema == null) {
            return "sample";
        }
        if (schema.getExample() != null) {
            return schema.getExample();
        }
        if (schema.getDefault() != null) {
            return schema.getDefault();
        }
        List<?> enums = schema.getEnum();
        if (enums != null && !enums.isEmpty()) {
            return enums.get(0);
        }

        String type = type(schema);
        if (type == null) {
            type = "string";
        }
        switch (type) {
            case "integer":
                return validInteger(schema);
            case "number":
                return validNumber(schema);
            case "boolean":
                return Boolean.TRUE;
            case "array": {
                java.util.List<Object> list = new java.util.ArrayList<>();
                Object item = valid(schema.getItems());
                list.add(item);
                Integer minItems = schema.getMinItems();
                if (minItems != null) {
                    while (list.size() < minItems) {
                        list.add(item);
                    }
                }
                return list;
            }
            case "object":
                return validObject(schema);
            case "string":
            default:
                return validString(schema);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, Object> validObject(Schema schema) {
        Map<String, Object> obj = new LinkedHashMap<>();
        Map<String, Schema> props = schema.getProperties();
        if (props != null) {
            for (Map.Entry<String, Schema> e : props.entrySet()) {
                obj.put(e.getKey(), valid(e.getValue()));
            }
        }
        return obj;
    }

    private static Object validString(Schema schema) {
        String format = schema.getFormat();
        if (format != null) {
            switch (format) {
                case "date":
                    return "2024-01-15";
                case "date-time":
                    return "2024-01-15T10:30:00Z";
                case "email":
                    return "user@example.com";
                case "uuid":
                    return "123e4567-e89b-12d3-a456-426614174000";
                case "uri":
                case "url":
                    return "https://example.com/resource";
                case "byte":
                    return "c2FtcGxl"; // base64("sample")
                case "hostname":
                    return "example.com";
                case "ipv4":
                    return "192.168.0.1";
                default:
                    break;
            }
        }
        String base = "sample";
        Integer min = schema.getMinLength();
        Integer max = schema.getMaxLength();
        if (min != null && base.length() < min) {
            StringBuilder sb = new StringBuilder(base);
            while (sb.length() < min) {
                sb.append('a');
            }
            base = sb.toString();
        }
        if (max != null && base.length() > max) {
            base = base.substring(0, Math.max(0, max));
        }
        return base;
    }

    private static Object validInteger(Schema schema) {
        BigDecimal min = schema.getMinimum();
        BigDecimal max = schema.getMaximum();
        long value = 1L;
        if (min != null && BigDecimal.valueOf(value).compareTo(min) < 0) {
            value = min.longValue();
        }
        if (max != null && BigDecimal.valueOf(value).compareTo(max) > 0) {
            value = max.longValue();
        }
        return value;
    }

    private static Object validNumber(Schema schema) {
        BigDecimal min = schema.getMinimum();
        BigDecimal max = schema.getMaximum();
        double value = 1.0;
        if (min != null && BigDecimal.valueOf(value).compareTo(min) < 0) {
            value = min.doubleValue();
        }
        if (max != null && BigDecimal.valueOf(value).compareTo(max) > 0) {
            value = max.doubleValue();
        }
        return value;
    }
}
