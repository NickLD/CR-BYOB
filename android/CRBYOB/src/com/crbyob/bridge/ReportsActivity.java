package com.crbyob.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * This phone's anonymous reports, as two pages: the list (no EXTRA_ISSUE), and one report's thread with the
 * developer's comments and this phone's replies, read from GitHub, and a box to answer through the report
 * Worker. A thread always opens on top of the list, so Back leads to the list. Opening a thread marks it read.
 */
public final class ReportsActivity extends Activity {
    private static final String EXTRA_ISSUE = "issue", EXTRA_OPEN = "open";

    /** The list of reports. */
    static void openList(Context c) {
        c.startActivity(new Intent(c, ReportsActivity.class));
    }

    /** One report's thread, over the list. */
    static void open(Context c, int issue) {
        c.startActivity(new Intent(c, ReportsActivity.class).putExtra(EXTRA_OPEN, issue));
    }

    private int issue;                   // 0 on the list page
    private SharedPreferences prefs;
    private LinearLayout list;           // the list page's rows, or the thread's messages
    private TextView pill, note, status, error, web;   // pill: status badge; note: why there's no reply box
    private EditText box;
    private Button send;
    private View composer;               // the reply box and Send, pinned under the messages
    private LinearLayout sending;        // spinner + "Sending…" while a reply goes out
    private boolean sendingNow;
    private ScrollView scroll;

    private int dp(float v) {
        return Widgets.dp(this, v);
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyLockScreenMode(this);
        getWindow().setStatusBarColor(Widgets.BG);
        getWindow().setNavigationBarColor(Widgets.BG);
        Widgets.fitSystemBars(this);
        prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        issue = getIntent().getIntExtra(EXTRA_ISSUE, 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Widgets.BG);
        root.addView(bar(issue == 0 ? "Your reports" : "Report #" + issue));
        View rule = new View(this);
        rule.setBackgroundColor(Widgets.LINE);
        root.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(24));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (issue == 0) {
            TextView about = text(13, Widgets.SUB);
            about.setText("Only reports sent anonymously from this app are listed and checked for replies here. "
                    + "Check GitHub for replies on personally-submitted reports.");
            about.setPadding(dp(4), 0, dp(4), dp(12));
            body.addView(about);
            body.addView(list, wide());
        } else {
            buildThread(body);
            root.addView(composer, wide());
        }
        setContentView(root);

        int open = getIntent().getIntExtra(EXTRA_OPEN, 0);
        if (open > 0 && b == null)     // asked for one report: the list goes under it, for Back
            startActivity(new Intent(this, ReportsActivity.class).putExtra(EXTRA_ISSUE, open));
        if (issue != 0) {
            showClosed();
            load();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (issue == 0) fillList();     // a thread may have marked a report read, or dropped a deleted one
    }

    /** ← Back, the page title, and (on a thread) "View on GitHub". */
    private View bar(String title) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(8), dp(4));
        TextView back = text(22, Widgets.TEXT);
        back.setText("←");
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = text(17, Widgets.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setText(title);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (issue != 0) {
            web = text(14, Widgets.TEXT);
            web.setText("View on GitHub ↗");
            web.setBackground(Widgets.roundRect(Widgets.LINE, dp(10)));
            web.setPadding(dp(12), dp(8), dp(12), dp(8));
            web.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(BuildConfig.REPO_URL + "/issues/" + issue)));
                } catch (android.content.ActivityNotFoundException e) {
                    android.widget.Toast.makeText(this, "No app can open GitHub links", android.widget.Toast.LENGTH_LONG).show();
                }
            });
            bar.addView(web);
        }
        return bar;
    }

    // ------------------------------------------------------------------ list page

    /** One row per report, newest first: number, status badge, and a blue marker when a reply is unread. */
    private void fillList() {
        list.removeAllViews();
        JSONArray all = AnonReports.reports(prefs);
        if (all.length() == 0) {
            TextView none = text(15, Widgets.SUB);
            none.setText("No reports on this phone.");
            none.setPadding(dp(8), dp(8), dp(8), dp(8));
            list.addView(none);
            return;
        }
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (int i = all.length() - 1; i >= 0; i--) {
            JSONObject r = all.optJSONObject(i);
            if (r == null) continue;
            int n = r.optInt("n");
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setBackground(Widgets.roundRect(Widgets.CARD, dp(12)));
            row.setForeground(getDrawable(ripple.resourceId));
            // the title on top (or "Report #N" until it's known), then number, status badge and "New replies"
            String title = AnonReports.title(r);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView head = text(16, Widgets.TEXT);
            head.setText(title.isEmpty() ? "Report #" + n : title);
            head.setMaxLines(2);
            head.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(head);
            LinearLayout meta = new LinearLayout(this);
            meta.setGravity(Gravity.CENTER_VERTICAL);
            meta.setPadding(0, dp(6), 0, 0);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.rightMargin = dp(10);
            if (!title.isEmpty()) {
                TextView num = text(14, Widgets.SUB);
                num.setText("#" + n);
                meta.addView(num, blp);
            }
            TextView badge = new TextView(this);
            String s = AnonReports.status(r);
            Widgets.badge(badge, s, statusColor(s));
            meta.addView(badge, blp);
            if (r.optBoolean("unread")) {
                TextView u = text(13, Widgets.BLUE);
                u.setText("● New replies");
                meta.addView(u, blp);
            }
            col.addView(meta);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView chev = text(18, Widgets.SUB);
            chev.setText("›");
            row.addView(chev);
            row.setOnClickListener(v -> startActivity(new Intent(this, ReportsActivity.class).putExtra(EXTRA_ISSUE, n)));
            LinearLayout.LayoutParams lp = wide();
            lp.bottomMargin = dp(8);
            list.addView(row, lp);
        }
    }

    /** Badge colors, GitHub's where it has one: open green, resolved purple, locked amber, deleted red. */
    static int statusColor(String status) {
        switch (status) {
            case "Open": return Widgets.GREEN;
            case "Resolved": return Widgets.PURPLE;
            case "Locked": return Widgets.AMBER;
            case "Deleted": return Widgets.RED;
            default: return Widgets.GREY;   // Not planned, Duplicate, Closed
        }
    }

    // ------------------------------------------------------------------ thread page

    /** This report as stored, or null once it's dropped because GitHub deleted the issue. */
    private JSONObject record() {
        JSONArray all = AnonReports.reports(prefs);
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r != null && r.optInt("n") == issue) return r;
        }
        return null;
    }

    private void buildThread(LinearLayout body) {
        LinearLayout head = new LinearLayout(this);          // [badge]  No longer takes replies.
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, dp(8));
        pill = new TextView(this);
        head.addView(pill);
        note = text(13, Widgets.SUB);
        note.setPadding(dp(10), 0, 0, 0);
        head.addView(note);
        body.addView(head, wide());
        body.addView(list, wide());
        status = text(14, Widgets.SUB);
        status.setPadding(0, dp(8), 0, dp(4));
        body.addView(status);

        // the reply box and Send, pinned under the messages so they ride up with the keyboard
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(Widgets.CARD);
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
        box = new EditText(this);
        box.setTextColor(Widgets.TEXT);
        box.setHintTextColor(Widgets.GREY);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        android.graphics.drawable.GradientDrawable boxBg = Widgets.roundRect(0x00000000, dp(8));   // outlined, not
        boxBg.setStroke(Math.max(1, dp(1.5f)), Widgets.GREY);                                      // filled like a bubble
        box.setBackground(boxBg);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int n, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int bf, int n) { }
            @Override public void afterTextChanged(android.text.Editable s) { updateSend(); }
        });
        box.setHint("Reply (public on GitHub)");
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.setSingleLine(false);
        box.setMinLines(2);
        box.setMaxLines(6);
        box.setGravity(Gravity.TOP | Gravity.START);
        box.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10_000)});  // Worker's limit
        c.addView(box, wide());
        error = text(14, Widgets.RED);
        error.setPadding(0, dp(8), 0, 0);
        error.setVisibility(View.GONE);
        c.addView(error);

        LinearLayout row = new LinearLayout(this);               // spinner + "Sending…" left, Send right
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        sending = new LinearLayout(this);
        sending.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Widgets.BLUE));
        sending.addView(spin);
        TextView st = text(14, Widgets.SUB);
        st.setText("Sending…");
        st.setPadding(dp(8), 0, 0, 0);
        sending.addView(st);
        sending.setVisibility(View.INVISIBLE);   // INVISIBLE, not GONE: it holds Send at the right
        row.addView(sending, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        send = new Button(this);
        send.setText("Send");
        row.addView(send);
        Widgets.styleButton(send, true);
        send.setOnClickListener(v -> send());
        c.addView(row, wide());
        composer = c;
        updateSend();
    }

    /** Closed, locked and deleted issues take no replies: the badge and a line say why, and the box goes. */
    private void showClosed() {
        JSONObject r = record();
        String s = r == null ? "Deleted" : AnonReports.status(r);
        boolean shut = !s.equals("Open");
        Widgets.badge(pill, s, statusColor(s));
        note.setText(r == null ? "Removed from GitHub." : "No longer takes replies.");
        note.setVisibility(shut ? View.VISIBLE : View.GONE);
        if (shut) error.setVisibility(View.GONE);         // a refused reply's error only repeats the badge
        web.setVisibility(r == null ? View.GONE : View.VISIBLE);
        composer.setVisibility(shut ? View.GONE : View.VISIBLE);
        updateSend();
    }

    /**
     * Re-reads the issue (it may have been reopened, closed, locked or deleted since the last check) and its
     * comments, marks the report read, and shows the developer's comments and this phone's replies.
     */
    private void load() {
        status.setTextColor(Widgets.SUB);
        status.setText("Loading replies…");
        status.setVisibility(View.VISIBLE);
        new Thread(() -> {
            JSONObject iss = null;
            JSONArray cs = null;
            int code = 0;
            try {
                iss = AnonReports.issue(issue);
                cs = AnonReports.comments(issue);
            } catch (IOException e) {
                code = e instanceof AnonReports.HttpError ? ((AnonReports.HttpError) e).code : 0;
            }
            String bot = prefs.getString("anon_bot", null), tag = AnonReports.tagOf(AnonReports.deviceId(prefs));
            synchronized (AnonReports.LOCK) {
                JSONArray all = AnonReports.reports(prefs);
                for (int i = 0; i < all.length(); i++) {
                    JSONObject r = all.optJSONObject(i);
                    if (r == null || r.optInt("n") != issue) continue;
                    AnonReports.applyIssue(r, iss != null ? 200 : code, iss);   // reopened: background checks resume
                    if (cs != null) {
                        AnonReports.applyComments(r, cs, bot, tag);
                        AnonReports.markRead(r);
                    }
                }
                AnonReports.dropGone(all);
                AnonReports.saveReports(prefs, all);
            }
            JSONArray got = cs;
            JSONObject report = iss;
            int c = code;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showClosed();
                if (got == null) {
                    if (record() == null) {                    // deleted: the badge says so, an error would mislead
                        status.setVisibility(View.GONE);
                        return;
                    }
                    status.setTextColor(Widgets.RED);
                    status.setText(c == 0 ? "Couldn't reach GitHub. Check the connection and try again."
                            : "GitHub answered " + c + ". Try again later.");
                    return;
                }
                render(report, got, bot, tag);
            });
        }, "anon-thread").start();
    }

    /** The report itself (iss, when it loaded) first, then the replies, newest at the bottom and flashed. */
    private void render(JSONObject iss, JSONArray cs, String bot, String tag) {
        list.removeAllViews();
        String report = iss == null ? "" : iss.optString("body").trim();
        if (!report.isEmpty()) bubble(true, "Your report · " + localTime(iss.optString("created_at")), report, "report");
        View newest = null;
        for (int i = 0; i < cs.length(); i++) {             // GitHub lists them oldest first
            JSONObject c = cs.optJSONObject(i);
            if (c == null) continue;
            boolean mine = AnonReports.isMine(c, bot, tag);
            if (!mine && !AnonReports.isDeveloper(c, bot, tag)) continue;
            JSONObject u = c.optJSONObject("user");
            String who = mine ? "You" : u != null ? u.optString("login") : "";
            String text = c.optString("body");
            if (mine) {                                       // drop the "Reply from the reporter (tag …)" header
                int nl = text.indexOf('\n');
                text = nl < 0 ? "" : text.substring(nl + 1).trim();
            }
            if (text.trim().isEmpty()) continue;
            newest = bubble(mine, who + " · " + localTime(c.optString("created_at")), text, "reply");
        }
        status.setVisibility(newest == null ? View.VISIBLE : View.GONE);
        status.setTextColor(Widgets.SUB);
        status.setText("No replies yet.");
        View flash = newest;
        scroll.post(() -> {                                   // newest at the bottom, like a chat
            scroll.fullScroll(View.FOCUS_DOWN);
            if (flash != null) highlight(flash);
        });
    }

    /**
     * A chat-style bubble: the reporter's own messages tinted blue and set in from the left, the developer's
     * grey and set in from the right, so who said what reads at a glance. A long text shows its start and a
     * "Show full report" (or "reply") link.
     */
    private View bubble(boolean mine, String header, String text, String what) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(Widgets.roundRect(mine ? (Widgets.BLUE & 0x00FFFFFF) | 0x33000000 : Widgets.LINE, dp(10)));
        bubble.setPadding(dp(12), dp(8), dp(12), dp(10));
        TextView head = text(12, Widgets.SUB);
        head.setText(header);
        head.setPadding(0, 0, 0, dp(2));
        bubble.addView(head);
        TextView t = text(15, Widgets.TEXT);
        String start = preview(text);
        t.setText(Markdown.render(this, start != null ? start + " …" : text, true));   // GitHub text is Markdown
        t.setLinkTextColor(Widgets.BLUE);
        t.setTextIsSelectable(true);
        t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());   // after: links stay tappable
        bubble.addView(t);
        if (start != null) {
            TextView more = text(14, Widgets.BLUE);
            more.setText("Show full " + what + " ▾");
            more.setPadding(0, dp(6), 0, dp(2));
            more.setOnClickListener(v -> {
                t.setText(Markdown.render(this, text, true));
                more.setVisibility(View.GONE);
            });
            bubble.addView(more);
        }
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(list.getChildCount() == 0 ? 4 : 8);
        if (mine) lp.leftMargin = dp(32);
        else lp.rightMargin = dp(32);
        list.addView(bubble, lp);
        return bubble;
    }

    /** The first lines of a long text (whole lines, up to 12 and about 800 characters), or null when it's short. */
    static String preview(String s) {
        int end = 0;
        for (int lines = 0; lines < 12 && end < s.length(); lines++) {
            int nl = s.indexOf('\n', end);
            int next = nl < 0 ? s.length() : nl + 1;
            if (next > 800) break;
            end = next;
        }
        if (end == 0) end = Math.min(s.length(), 800);       // one very long first line
        return end >= s.length() ? null : s.substring(0, end).trim();
    }

    /** Fades a blue outline in and out over the newest reply, like the docs do for the section they open at. */
    private void highlight(View v) {
        android.graphics.drawable.GradientDrawable box = Widgets.roundRect((Widgets.BLUE & 0x00FFFFFF) | 0x24000000, dp(10));
        box.setStroke(Math.max(1, dp(1.5f)), (Widgets.BLUE & 0x00FFFFFF) | 0x99000000);
        v.getOverlay().add(box);
        box.setAlpha(0);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(0, 255, 255, 0);   // in, hold, out: 1 s each
        a.setDuration(3000);
        a.addUpdateListener(x -> {          // sized each frame: the bubble may not be laid out when this starts
            box.setBounds(0, 0, v.getWidth(), v.getHeight());
            box.setAlpha((int) x.getAnimatedValue());
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) { v.getOverlay().remove(box); }
        });
        a.start();
    }

    /** Posts the reply through the Worker; on failure the text stays in the box with the error under it. */
    private void send() {
        String text = box.getText().toString().trim();
        if (text.isEmpty()) return;
        sendingState(true);
        error.setVisibility(View.GONE);
        new Thread(() -> {
            int code = -1;
            String msg = null;
            try {
                AnonReports.reply(this, issue, text);
            } catch (IOException e) {
                code = e instanceof AnonReports.HttpError ? ((AnonReports.HttpError) e).code : 0;
                msg = AnonReports.errorMessage(e);
            }
            int c = code;
            String m = msg;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                sendingState(false);
                if (c >= 0) {
                    BLog.log("anon_reply_failed", "code", c);
                    error.setText(m);
                    error.setVisibility(View.VISIBLE);
                    if (c == 403) load();      // the Worker refuses closed and locked reports: show why, hide the box
                    return;
                }
                box.setText("");
                load();
            });
        }, "anon-reply").start();
    }

    /** GitHub's UTC timestamp ("2026-11-12T23:48:29Z") as the phone's local date and time, in its 12/24-hour style. */
    private String localTime(String iso) {
        java.text.SimpleDateFormat utc = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        try {
            java.util.Date d = utc.parse(iso);
            return android.text.format.DateFormat.getMediumDateFormat(this).format(d) + ", "
                    + android.text.format.DateFormat.getTimeFormat(this).format(d);
        } catch (java.text.ParseException | NullPointerException e) {
            return iso.length() >= 10 ? iso.substring(0, 10) : iso;
        }
    }

    /** While a reply sends: Send and the box are off and dimmed, and the spinner shows. */
    private void sendingState(boolean on) {
        sendingNow = on;
        box.setEnabled(!on);
        sending.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        updateSend();
    }

    /** Send works only with something in the box and no send running; otherwise it's off and dimmed. */
    private void updateSend() {
        if (send == null) return;
        boolean on = !sendingNow && box.getText().toString().trim().length() > 0;
        send.setEnabled(on);
        send.setAlpha(on ? 1f : 0.5f);
    }
}
