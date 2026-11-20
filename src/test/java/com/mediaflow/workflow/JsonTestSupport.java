package com.mediaflow.workflow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JsonTestSupport {
    private JsonTestSupport() {}

    static String field(String json, String field) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"")
                        .matcher(json);
        if (!matcher.find()) throw new AssertionError("field not found: " + field + " in " + json);
        return matcher.group(1);
    }
}
