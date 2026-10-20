package io.github.felixlp.libkat.core;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.Charset;
import java.security.MessageDigest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OfflineTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Test
    public void base64MatchesRfc4648() {
        assertEquals("", Text.base64(new byte[0], false));
        assertEquals("Zg==", Text.base64("f".getBytes(UTF8), false));
        assertEquals("Zm8=", Text.base64("fo".getBytes(UTF8), false));
        assertEquals("Zm9v", Text.base64("foo".getBytes(UTF8), false));
        assertEquals("Zm9vYmFy", Text.base64("foobar".getBytes(UTF8), false));
        // url-safe без паддинга, как требует PKCE
        assertEquals("-_8", Text.base64(new byte[]{(byte) 0xfb, (byte) 0xff}, true));
    }

    @Test
    public void pkceChallengeMatchesRfc7636Example() throws Exception {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Text.base64(d, true));
    }

    @Test
    public void altchaFindsNumber() throws Exception {
        String salt = "abc123:1792455573";
        int secret = 4242;
        byte[] target = MessageDigest.getInstance("SHA-256").digest((salt + secret).getBytes(UTF8));
        JSONObject ch = new JSONObject();
        ch.put("algorithm", "SHA-256");
        ch.put("challenge", Text.hex(target));
        ch.put("salt", salt);
        ch.put("signature", "sig");
        ch.put("maxnumber", 100000);
        String payload = Altcha.solve(ch);
        JSONObject p = new JSONObject(new String(java.util.Base64.getDecoder().decode(payload), UTF8));
        assertEquals(secret, p.getInt("number"));
        assertEquals("sig", p.getString("signature"));
        assertEquals(salt, p.getString("salt"));
    }

    @Test
    public void summaryFromProseMirrorDoc() throws Exception {
        JSONObject doc = new JSONObject("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"Раз\"},{\"type\":\"hardBreak\"},{\"type\":\"text\",\"text\":\"два\"}]},"
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"три\"}]}]}");
        assertEquals("Раз\nдва\nтри\n", Text.fromDoc(doc));
        assertEquals("просто строка", Text.fromDoc("просто строка"));
    }

    @Test
    public void playerPicksQuality() throws Exception {
        Player p = Player.parse(new JSONObject("{\"player\":\"Animelib\",\"video\":{\"quality\":["
                + "{\"href\":\"/a_2160.mp4\",\"quality\":2160},{\"href\":\"/a_1080.mp4\",\"quality\":1080},"
                + "{\"href\":\"/a_720.mp4\",\"quality\":720},{\"href\":\"/a_360.mp4\",\"quality\":360}]}}"));
        assertTrue(p.isDirect());
        assertEquals(720, p.pick(720).quality);
        assertEquals(720, p.pick(1000).quality);
        assertEquals(360, p.pick(100).quality);
    }

    @Test
    public void tokenRoundTrip() {
        Token t = new Token();
        t.accessToken = "a";
        t.refreshToken = "r";
        t.expiresAt = 123;
        t.userId = 7;
        t.username = "Педаль";
        Token back = Token.fromJson(t.toJson());
        assertEquals("a", back.accessToken);
        assertEquals("r", back.refreshToken);
        assertEquals(7, back.userId);
        assertEquals("Педаль", back.username);
        assertNull(Token.fromJson("{}"));
    }

    @Test
    public void errorDescriptions() {
        assertTrue(LibApi.describe(451, "{}").contains("прокси"));
        assertTrue(LibApi.describe(404, "{\"data\":{\"toast\":{\"type\":\"silent\",\"message\":\"Not Found\"}}}")
                .startsWith("Не найдено"));
        assertTrue(LibApi.describe(403, "<html>ddos-guard</html>").contains("прокси"));
    }
}
