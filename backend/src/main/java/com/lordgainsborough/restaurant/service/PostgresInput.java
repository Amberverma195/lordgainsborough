package com.lordgainsborough.restaurant.service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** PostgreSQL varchar counts Unicode code points; text rejects NUL and unpaired surrogates. */
final class PostgresInput {
    private PostgresInput() {}
    static void text(String value, int max, boolean required) {
        if (value == null) { if (required) invalid(); return; }
        if ((required && value.isBlank()) || value.codePointCount(0,value.length()) > max) invalid();
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (c==0 || Character.isLowSurrogate(c)) invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) invalid();
            }
        }
    }
    static void invalid() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid request values"); }
}
