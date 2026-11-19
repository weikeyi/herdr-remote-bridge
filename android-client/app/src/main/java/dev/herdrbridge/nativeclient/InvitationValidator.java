package dev.herdrbridge.nativeclient;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure Java boundary. Error messages intentionally never contain supplied URLs. */
public final class InvitationValidator {
    private InvitationValidator() {}
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
        "setup", "label", "invite", "invite_version", "invite_expires",
        "gateways", "relay_id", "rendezvous", "path_policy"));
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid or expired scoped invitation");
    }
    private static URI parse(String value) {
        try {
            if (value == null || value.length() > 8192 || value.matches("(?s).*[\\x00-\\x20\\x7f\\\\].*")) throw invalid();
            URI uri = new URI(value);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() == 0
                    || uri.getPort() > 65535 || uri.getPort() < -1) throw invalid();
            return uri;
        } catch (Exception e) { throw invalid(); }
    }
    public static String origin(String value) {
        URI uri = parse(value);
        if (!"https".equals(uri.getScheme()) || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !rootPath(uri)) throw invalid();
        return authority(uri, "https");
    }
    private static boolean rootPath(URI uri) {
        return uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath());
    }
    private static String authority(URI uri, String scheme) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return scheme + "://" + host + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
    }
    public static boolean sameOrigin(String value, String configuredOrigin) {
        try {
            URI uri = parse(value);
            return "https".equals(uri.getScheme()) && authority(uri, "https").equals(origin(configuredOrigin));
        } catch (IllegalArgumentException e) { return false; }
    }
    public static String invitation(String value, String configuredOrigin, long nowMillis) {
        URI uri = parse(value);
        if (!sameOrigin(value, configuredOrigin) || !rootPath(uri) || uri.getRawQuery() != null
                || uri.getRawFragment() == null) throw invalid();
        Map<String, String> params = new HashMap<>();
        for (String part : uri.getRawFragment().split("&", -1)) {
            int separator = part.indexOf('=');
            if (separator < 1) throw invalid();
            String key = decode(part.substring(0, separator));
            String val = decode(part.substring(separator + 1));
            if (!KEYS.contains(key) || params.put(key, val) != null) throw invalid();
        }
        if (!params.keySet().equals(KEYS)
                || !"relay-only".equals(params.get("path_policy"))
                || !params.get("invite").matches("[A-Za-z0-9_-]{16,128}")
                || !canonicalKey(params.get("setup"), 32)
                || !canonicalKey(params.get("rendezvous"), 32)
                || !canonicalKey(params.get("relay_id"), 16)
                || params.get("label").length() > 256) throw invalid();
        positiveSafeInteger(params.get("invite_version"));
        if (positiveSafeInteger(params.get("invite_expires")) <= nowMillis) throw invalid();
        URI gateway = parse(params.get("gateways"));
        if (!"wss".equals(gateway.getScheme()) || !rootPath(gateway)
                || gateway.getRawQuery() != null || gateway.getRawFragment() != null
                || !authority(gateway, "https").equals(origin(configuredOrigin))) throw invalid();
        return value; // Only transient native protocol handoff; never persist this value.
    }
    private static boolean canonicalKey(String value, int length) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            return bytes.length == length && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value);
        } catch (IllegalArgumentException e) { return false; }
    }
    private static long positiveSafeInteger(String value) {
        try {
            if (!value.matches("[1-9][0-9]{0,15}")) throw invalid();
            long number = Long.parseLong(value);
            if (number > 9007199254740991L) throw invalid();
            return number;
        } catch (Exception e) { throw invalid(); }
    }
    private static String decode(String value) {
        try {
            String decoded = URLDecoder.decode(value, "UTF-8");
            if (decoded.matches("(?s).*[\\x00-\\x1f\\x7f\\ufffd].*")) throw invalid();
            return decoded;
        } catch (Exception e) { throw invalid(); }
    }
}
