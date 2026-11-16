package io.legohunter.bricklink.webgateway.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.bricklink.webgateway.browser.ImportedCookie;

/**
 * Reads a Playwright storage-state file (the session exported on the PC) into cookies.
 *
 * <p>The file is a live credential. It is parsed from memory, never written to disk by the gateway,
 * and no error from here quotes its content.
 */
public final class StorageStateParser {

    /** A session without this cookie cannot be a signed-in BrickLink session. */
    static final String REQUIRED_COOKIE_PREFIX = "bricklink.lego-account.";

    private static final List<String> ALLOWED_DOMAINS = List.of("bricklink.com", "lego.com");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StorageStateParser() {
    }

    /**
     * @return the BrickLink and LEGO Account cookies of the file; cookies of other sites (analytics,
     *         advertising) are left out
     * @throws InvalidSessionStateException if the file is not a storage state or holds no BrickLink session
     */
    public static List<ImportedCookie> parse(byte[] storageState) {
        JsonNode root;
        try {
            root = MAPPER.readTree(storageState);
        } catch (IOException e) {
            // Jackson's message can quote the input, so it is not passed on.
            throw new InvalidSessionStateException("The file is not valid JSON");
        }
        JsonNode cookies = root == null ? null : root.get("cookies");
        if (cookies == null || !cookies.isArray()) {
            throw new InvalidSessionStateException("The file has no \"cookies\" array; expected a Playwright storage state");
        }
        List<ImportedCookie> result = new ArrayList<>();
        boolean hasBricklinkToken = false;
        for (JsonNode cookie : cookies) {
            String name = text(cookie, "name");
            String value = text(cookie, "value");
            String domain = text(cookie, "domain");
            if (name == null || name.isBlank() || value == null || domain == null || !allowed(domain)) {
                continue;
            }
            String path = text(cookie, "path");
            result.add(new ImportedCookie(name, value, domain, path == null || path.isBlank() ? "/" : path,
                    cookie.path("expires").asDouble(-1), cookie.path("httpOnly").asBoolean(false),
                    cookie.path("secure").asBoolean(false), text(cookie, "sameSite")));
            hasBricklinkToken |= name.startsWith(REQUIRED_COOKIE_PREFIX);
        }
        if (!hasBricklinkToken) {
            throw new InvalidSessionStateException("The file holds no BrickLink sign-in cookies; export it from a signed-in session");
        }
        return result;
    }

    private static boolean allowed(String domain) {
        String host = domain.toLowerCase(Locale.ROOT);
        if (host.startsWith(".")) {
            host = host.substring(1);
        }
        for (String allowed : ALLOWED_DOMAINS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** The uploaded file cannot be used. The message is safe to return to the caller. */
    public static class InvalidSessionStateException extends RuntimeException {
        public InvalidSessionStateException(String message) {
            super(message);
        }
    }
}
