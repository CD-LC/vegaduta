package com.agenticai.core.security.http;

import java.nio.charset.StandardCharsets;

/**
 * Builds a {@code Content-Disposition: attachment} header value the way
 * RFC 6266 / RFC 5987 say to: a plain quoted ASCII {@code filename=} every
 * client understands, plus {@code filename*=UTF-8''percent-encoded} only when
 * the real name has characters the ASCII form cannot carry.
 *
 * <p>Why not Spring's {@code ContentDisposition.attachment().filename(name,
 * UTF_8)}: for ANY name - even pure ASCII - it writes the {@code filename=}
 * parameter as an RFC 2047 encoded-word ({@code filename="=?UTF-8?Q?name?="})
 * next to the {@code filename*} one. RFC 2047 encoded-words are a mail-header
 * convention; RFC 6266 (appendix C.1) says not to use them here. A client that
 * reads the first {@code filename} parameter - the web console's download
 * helper did - saves the file under the literal encoded-word. Prod
 * 2026-10-05: a workflow export downloaded as
 * {@code =?UTF-8?Q?e2e-workflow.vegaduta-workflow.json?=}.
 *
 * <p>The name is treated as untrusted: path separators, quotes, backslashes
 * and control characters never reach the header.
 */
public final class AttachmentDisposition {

    private static final String FALLBACK_NAME = "download";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private AttachmentDisposition() {
    }

    /** {@code attachment; filename="..."} (plus {@code filename*} when the name is not plain ASCII). */
    public static String attachment(String filename) {
        return build("attachment", filename);
    }

    /** {@code inline; filename="..."} (plus {@code filename*} when the name is not plain ASCII). */
    public static String inline(String filename) {
        return build("inline", filename);
    }

    private static String build(String type, String filename) {
        String name = clean(filename);
        String ascii = asciiFallback(name);
        StringBuilder header = new StringBuilder(type).append("; filename=\"").append(ascii).append('"');
        if (!ascii.equals(name)) {
            header.append("; filename*=UTF-8''").append(percentEncode(name));
        }
        return header.toString();
    }

    /** The last path segment, without control characters; never empty. */
    static String clean(String filename) {
        if (filename == null) {
            return FALLBACK_NAME;
        }
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder kept = new StringBuilder(name.length());
        name.codePoints().filter(cp -> cp >= 0x20 && cp != 0x7F).forEach(kept::appendCodePoint);
        String trimmed = kept.toString().trim();
        return trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..") ? FALLBACK_NAME : trimmed;
    }

    /** Printable ASCII only, with the two characters that would break a quoted-string replaced. */
    private static String asciiFallback(String name) {
        StringBuilder ascii = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> {
            if (cp < 0x20 || cp > 0x7E || cp == '"' || cp == '\\') {
                ascii.append('_');
            } else {
                ascii.append((char) cp);
            }
        });
        return ascii.toString();
    }

    /** RFC 5987 {@code value-chars}: attr-char stays, every other UTF-8 byte becomes {@code %XX}. */
    private static String percentEncode(String name) {
        StringBuilder encoded = new StringBuilder(name.length() * 3);
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isAttrChar(c)) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return encoded.toString();
    }

    private static boolean isAttrChar(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
            || c == '!' || c == '#' || c == '$' || c == '&' || c == '+' || c == '-' || c == '.'
            || c == '^' || c == '_' || c == '`' || c == '|' || c == '~';
    }
}
