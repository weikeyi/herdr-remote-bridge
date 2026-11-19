package dev.herdrbridge.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class InvitationValidatorTest {
    private final String origin = "https://192.0.2.10:9443";
    private String invitation() {
        // Go url.Values.Encode order and escaping, including label spaces as +.
        return origin + "/#gateways=wss%3A%2F%2F192.0.2.10%3A9443&invite=" + repeat('B', 22)
            + "&invite_expires=2000000000000&invite_version=1&label=My+PC&path_policy=relay-only"
            + "&relay_id=" + repeat('A', 22) + "&rendezvous=" + repeat('A', 43) + "&setup=" + repeat('A', 43);
    }
    private String repeat(char c, int count) { return new String(new char[count]).replace('\0', c); }
    private void rejects(String value) {
        try { InvitationValidator.invitation(value, origin, 1900000000000L); fail("accepted invalid invitation"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("setup=")); }
    }
    @Test public void acceptsScopedInvitationAndIpPort() {
        assertEquals(invitation(), InvitationValidator.invitation(invitation(), origin, 1900000000000L));
        assertEquals("https://example.com", InvitationValidator.origin("https://EXAMPLE.com:443/"));
        assertEquals(origin, InvitationValidator.origin(origin));
    }
    @Test public void rejectsBootstrapDuplicateUnknownAndMissing() {
        rejects(origin + "/#setup=" + repeat('A', 43));
        rejects(invitation().replace(repeat('B', 22), "bootstrap"));
        rejects(invitation() + "&setup=" + repeat('A', 43));
        rejects(invitation() + "&%73etup=" + repeat('A', 43));
        rejects(invitation() + "&relay=wss%3A%2F%2Fevil.example");
        rejects(invitation().replace("&path_policy=relay-only", ""));
    }
    @Test public void rejectsUnsafeOriginsGatewaysAndExpiry() {
        rejects(invitation().replace("invite_expires=2000000000000", "invite_expires=1800000000000"));
        rejects(invitation().replace("invite_version=1", "invite_version=0"));
        rejects(invitation().replace("invite_version=1", "invite_version=9007199254740992"));
        rejects(invitation().replace("wss%3A", "ws%3A"));
        rejects(invitation().replace("gateways=wss%3A%2F%2F192.0.2.10%3A9443", "gateways=wss%3A%2F%2Fevil.example"));
        rejects(invitation().replace("%3A9443&invite=", "%3A9443%2Cwss%3A%2F%2Fevil.example&invite="));
        rejects(invitation().replace("/#", "/?query=secret#"));
        rejects(invitation().replace("https://", "http://"));
        rejects(invitation().replace("https://", "https://user@"));
        rejects(invitation() + "&");
        rejects(invitation().replace("My+PC", "%FF"));
    }
    @Test public void canonicalKeysAndIpv6() {
        assertEquals("https://[2001:db8::1]:9443", InvitationValidator.origin("https://[2001:db8::1]:9443/"));
        assertTrue(InvitationValidator.sameOrigin("https://[2001:db8::1]:9443/a", "https://[2001:db8::1]:9443"));
        rejects(invitation().replace("rendezvous=" + repeat('A', 43), "rendezvous=" + repeat('B', 43)));
        rejects(invitation().replace("relay_id=" + repeat('A', 22), "relay_id=" + repeat('B', 22)));
    }
    @Test public void navigationRejectsOtherOriginsAndSchemes() {
        assertTrue(InvitationValidator.sameOrigin(origin + "/assets/app.js", origin));
        for (String url : new String[]{"https://evil.example", "http://192.0.2.10:9443", "file:///etc/passwd",
                "content://provider/a", "intent://host", "javascript:alert(1)", "https://192.0.2.10:443",
                "https://192.0.2.10:9443@evil.example", "https://192.0.2.10:9443\\@evil.example"}) {
            assertFalse(InvitationValidator.sameOrigin(url, origin));
        }
    }
    private void rejectsOrigin(String value) {
        try { InvitationValidator.origin(value); fail("accepted invalid origin"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains(value)); }
    }
    @Test public void rejectsAuthorityEncodingWhitespaceAndPortBounds() {
        for (String url : new String[]{
                "https://192.0.2.10%2f.evil.example:9443",
                "https://192.0.2.10%40evil.example:9443",
                "https://user:password@192.0.2.10:9443",
                "https://192.0.2.10:9443.evil.example",
                "https://192.0.2.10:0", "https://192.0.2.10:65536", "https://192.0.2.10:-1",
                "https://192.0.2.10:999999999999999999999",
                " https://192.0.2.10:9443", "https://192.0.2.10:9443 ",
                "https://192.0.2.10:9443\t", "https://192.0.2.10:9443\n",
                "https://192.0.2.10:9443\\evil.example"}) {
            rejectsOrigin(url);
            assertFalse(InvitationValidator.sameOrigin(url, origin));
        }
        assertEquals("https://example.com:1", InvitationValidator.origin("https://example.com:1"));
        assertEquals("https://example.com:65535", InvitationValidator.origin("https://example.com:65535"));
    }
    @Test public void rejectsInvitationPathsQueriesAndEncodedDelimiters() {
        for (String path : new String[]{"/%2f", "/%2F%2Fevil.example", "/./", "/../", "/%2e/", "/app/", "/?", "/?next=https://evil.example"}) {
            rejects(invitation().replace(origin + "/#", origin + path + "#"));
            rejectsOrigin(origin + path);
        }
        for (String gateway : new String[]{
                "wss%3A%2F%2Fuser%40192.0.2.10%3A9443",
                "wss%3A%2F%2F192.0.2.10%3A9443%2F%252f",
                "wss%3A%2F%2F192.0.2.10%3A9443%2F..%2F",
                "wss%3A%2F%2F192.0.2.10%3A9443%3Fnext%3Devil",
                "wss%3A%2F%2F192.0.2.10%3A9443%23secret",
                "wss%3A%2F%2F192.0.2.10%3A65536"}) {
            rejects(invitation().replace("wss%3A%2F%2F192.0.2.10%3A9443", gateway));
        }
        rejects(invitation().replace("label=My+PC", "label=My%00PC"));
        rejects(invitation().replace("label=My+PC", "label=%GG"));
        rejects(invitation().replace("label=My+PC", "label=%"));
    }
    @Test public void rejectsEveryMissingRequiredFieldAndExpiredBoundary() {
        for (String key : new String[]{"setup", "label", "invite", "invite_version", "invite_expires",
                "gateways", "relay_id", "rendezvous", "path_policy"}) {
            String[] parts = invitation().split("#", 2);
            java.util.List<String> kept = new java.util.ArrayList<>();
            for (String field : parts[1].split("&")) if (!field.startsWith(key + "=")) kept.add(field);
            rejects(parts[0] + "#" + String.join("&", kept));
        }
        rejects(invitation().replace("invite_expires=2000000000000", "invite_expires=1900000000000"));
        rejects(invitation().replace("invite_version=1", "invite_version=01"));
        rejects(invitation().replace("invite_version=1", "invite_version=1e0"));
        rejects(invitation().replace("path_policy=relay-only", "path_policy=direct"));
    }
}
