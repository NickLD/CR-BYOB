package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The anonymous-report rules: ID and tag, whose comment is whose, and what counts as unread. */
public class AnonReportsTest {
    private static final String TAG = "3eb1bd439947eb762998e566ccc2e099";
    private static final String BOT = "crbyob-bot[bot]";

    private static JSONObject comment(long id, String assoc, String login, String type, String body) throws Exception {
        return new JSONObject().put("id", id).put("author_association", assoc).put("body", body)
                .put("user", new JSONObject().put("login", login).put("type", type));
    }

    private static String ownBody(String tag) {
        return "**Reply from the reporter** (tag `" + tag + "`)\n\nhello";
    }

    private static JSONObject report(int seen, int dev) throws Exception {
        return new JSONObject().put("n", 1).put("seen", seen).put("dev", dev).put("newest", 0)
                .put("closed", false).put("unread", false);
    }

    @Test public void tagOf_vector() {
        assertEquals(TAG, AnonReports.tagOf("0123456789abcdef0123456789abcdef"));
    }

    @Test public void newId_isHexAndRandom() {
        String a = AnonReports.newId();
        assertTrue(a.matches("^[0-9a-f]{32}$"));
        assertNotEquals(a, AnonReports.newId());
    }

    @Test public void isDeveloper_rules() throws Exception {
        assertTrue(AnonReports.isDeveloper(comment(1, "OWNER", "NickLD", "User", "hi"), BOT, TAG));
        assertTrue(AnonReports.isDeveloper(comment(1, "NONE", "some-bot[bot]", "Bot", "hi"), BOT, TAG));
        assertFalse(AnonReports.isDeveloper(comment(1, "NONE", "stranger", "User", "hi"), BOT, TAG));
        assertFalse(AnonReports.isDeveloper(comment(1, "NONE", BOT, "Bot", ownBody(TAG)), BOT, TAG));
    }

    @Test public void isMine_needsBotLogin() throws Exception {
        assertTrue(AnonReports.isMine(comment(1, "NONE", BOT, "Bot", ownBody(TAG)), BOT, TAG));
        assertFalse(AnonReports.isMine(comment(1, "NONE", "someone", "User", ownBody(TAG)), BOT, TAG));
        assertFalse(AnonReports.isMine(comment(1, "NONE", BOT, "Bot", ownBody("0".repeat(32))), BOT, TAG));
    }

    @Test public void applyIssue_missingClosesIt() throws Exception {
        JSONObject r = report(1, 0);
        assertFalse(AnonReports.applyIssue(r, 404, null));
        assertTrue(r.getBoolean("closed"));
    }

    @Test public void applyIssue_410ClosesButRateLimit403DoesNot() throws Exception {
        JSONObject gone = report(1, 0);
        AnonReports.applyIssue(gone, 410, null);
        assertTrue(gone.getBoolean("closed"));
        JSONObject limited = report(1, 0);
        assertFalse(AnonReports.applyIssue(limited, 403, null));
        assertFalse(limited.getBoolean("closed"));
    }

    @Test public void applyIssue_commentCount() throws Exception {
        JSONObject open = new JSONObject().put("state", "open").put("comments", 3);
        assertTrue(AnonReports.applyIssue(report(1, 0), 200, open));
        assertFalse(AnonReports.applyIssue(report(3, 0), 200, open));
        JSONObject r = report(1, 0);
        AnonReports.applyIssue(r, 200, new JSONObject().put("state", "closed").put("comments", 1));
        assertTrue(r.getBoolean("closed"));
    }

    @Test public void applyComments_developerReplyIsUnread_ownIsNot() throws Exception {
        JSONObject r = report(0, 0);
        AnonReports.applyComments(r, new JSONArray().put(comment(5, "NONE", BOT, "Bot", ownBody(TAG))), BOT, TAG);
        assertFalse(r.getBoolean("unread"));
        assertEquals(1, r.getInt("seen"));
        AnonReports.applyComments(r, new JSONArray().put(comment(5, "NONE", BOT, "Bot", ownBody(TAG)))
                .put(comment(9, "OWNER", "NickLD", "User", "thanks")), BOT, TAG);
        assertTrue(r.getBoolean("unread"));
        assertEquals(9, r.getLong("newest"));
        AnonReports.markRead(r);
        assertFalse(r.getBoolean("unread"));
        assertEquals(9, r.getLong("dev"));
    }

    @Test public void errorMessage_texts() {
        assertEquals("Couldn't reach the report server. Check the connection and try again.", AnonReports.errorMessage(0));
        assertEquals("Too many reports from this network just now. Try again in a minute.", AnonReports.errorMessage(429));
        assertEquals("The report server answered 502. Try again later.", AnonReports.errorMessage(502));
    }
}
