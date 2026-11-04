package se.ataulnasar.pricingexception.api;

import java.util.regex.Pattern;

/**
 * Parses the {@code If-Match} header into the version the caller saw. Only the exact ETag form is
 * accepted: the version as a plain integer in double quotes, e.g. {@code "0"}. Weak tags, {@code *},
 * lists, unquoted or padded numbers are 400 (docs/design.md "API error codes").
 */
final class IfMatch {

    private static final Pattern STRONG_ETAG = Pattern.compile("\\\"(0|[1-9][0-9]*)\\\"");

    private IfMatch() {}

    static long requiredVersion(String header) {
        if (header == null || header.isBlank()) {
            throw new IfMatchRequiredException();
        }
        var m = STRONG_ETAG.matcher(header.trim());
        if (!m.matches()) {
            throw new MalformedRequestException(
                    "If-Match must be the request's version exactly as returned in the ETag, for example \"0\"");
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            throw new MalformedRequestException("If-Match version is out of range");
        }
    }
}
