package com.lordgainsborough.restaurant.util;

import java.util.regex.Pattern;

/**
 * Strict, RFC-compliant email validator shared across Lord Gainsborough auth flows.
 * Requires exactly one '@', valid local part, valid domain labels with at least one dot,
 * and a valid TLD (at least 2 letters). Rejects malformed addresses such as 'user@gma'.
 */
public final class EmailValidator {

    private static final int MAX_LENGTH = 254;
    private static final int MAX_LOCAL_LENGTH = 64;

    private static final Pattern LOCAL_PART_PATTERN = Pattern.compile(
            "^(?![.])[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+(?<![.])$"
    );

    private static final Pattern DOMAIN_LABEL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$"
    );

    private static final Pattern TLD_PATTERN = Pattern.compile(
            "^[a-zA-Z]{2,63}$"
    );

    private EmailValidator() {}

    public static boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }

        String trimmed = email.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return false;
        }

        if (trimmed.indexOf(' ') != -1 || trimmed.indexOf('\t') != -1
                || trimmed.indexOf('\n') != -1 || trimmed.indexOf('\r') != -1) {
            return false;
        }

        int atIndex = trimmed.indexOf('@');
        if (atIndex <= 0 || atIndex != trimmed.lastIndexOf('@')) {
            return false;
        }

        String localPart = trimmed.substring(0, atIndex);
        String domainPart = trimmed.substring(atIndex + 1);

        if (localPart.isEmpty() || localPart.length() > MAX_LOCAL_LENGTH) {
            return false;
        }

        if (localPart.startsWith(".") || localPart.endsWith(".") || localPart.contains("..")) {
            return false;
        }

        if (!LOCAL_PART_PATTERN.matcher(localPart).matches()) {
            return false;
        }

        if (domainPart.isEmpty() || domainPart.startsWith(".") || domainPart.endsWith(".") || domainPart.contains("..")) {
            return false;
        }

        // Domain must contain at least one dot (e.g. amberverma@gma is rejected)
        if (!domainPart.contains(".")) {
            return false;
        }

        String[] labels = domainPart.split("\\.");
        if (labels.length < 2) {
            return false;
        }

        for (int i = 0; i < labels.length - 1; i++) {
            String label = labels[i];
            if (label.isEmpty() || label.length() > 63 || !DOMAIN_LABEL_PATTERN.matcher(label).matches()) {
                return false;
            }
        }

        String tld = labels[labels.length - 1];
        if (!TLD_PATTERN.matcher(tld).matches()) {
            return false;
        }

        return true;
    }
}
