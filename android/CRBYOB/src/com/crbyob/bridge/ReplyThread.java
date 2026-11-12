package com.crbyob.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Replies to this phone's anonymous reports: the developer's comments and the phone's own replies, read from
 * GitHub, and a box to answer through the report Worker. Opening a thread marks it read.
 */
final class ReplyThread {
    private final Activity a;
    private final int issue;
    private final Runnable onChanged;
    private final SharedPreferences prefs;
    private final LinearLayout list;
    private final TextView status, error;
    private final EditText box;
    private final LinearLayout sending;   // spinner + "Sending…" while a reply goes out
    private AlertDialog dialog;
    private ScrollView scroll;

    /** One report goes straight to its thread; several open a list first. */
    static void openList(Activity a, Runnable onChanged) {
        JSONArray all = AnonReports.reports(a.getSharedPreferences("bridge", Activity.MODE_PRIVATE));
        if (all.length() <= 1) {
            if (all.length() == 1) open(a, all.optJSONObject(0).optInt("n"), onChanged);
            return;
        }
        String[] items = new String[all.length()];
        int[] nums = new int[all.length()];
        for (int i = 0; i < items.length; i++) {             // newest report first
            JSONObject r = all.optJSONObject(items.length - 1 - i);
            if (r == null) r = new JSONObject();
            nums[i] = r.optInt("n");
            items[i] = "#" + nums[i] + " · " + (r.optBoolean("closed") ? "closed" : "open")
                    + (r.optBoolean("unread") ? " · new replies" : "");
        }
        Widgets.showStyled(new AlertDialog.Builder(a)
                .setTitle("Your reports")
                .setItems(items, (d, w) -> open(a, nums[w], onChanged))
                .setNegativeButton("Close", null)
                .create());
    }

    static void open(Activity a, int issue, Runnable onChanged) {
        new ReplyThread(a, issue, onChanged).show();
    }

    private ReplyThread(Activity a, int issue, Runnable onChanged) {
        this.a = a;
        this.issue = issue;
        this.onChanged = onChanged;
        prefs = a.getSharedPreferences("bridge", Activity.MODE_PRIVATE);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        status = text(14, Widgets.SUB);
        error = text(14, Widgets.RED);
        box = new EditText(a);
        sending = new LinearLayout(a);
    }

    private int dp(float v) {
        return Widgets.dp(a, v);
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(a);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private boolean closed() {
        JSONArray all = AnonReports.reports(prefs);
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r != null && r.optInt("n") == issue) return r.optBoolean("closed");
        }
        return false;
    }

    private void show() {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(4), dp(20), dp(8));
        body.addView(list, wide());
        status.setPadding(0, dp(8), 0, dp(4));
        body.addView(status);

        box.setTextColor(Widgets.TEXT);
        box.setHintTextColor(Widgets.GREY);
        box.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        box.setBackground(Widgets.roundRect(Widgets.LINE, dp(8)));
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setHint("Reply (public on GitHub)");
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.setSingleLine(false);
        box.setMinLines(3);
        box.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        box.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10_000)});  // Worker's limit
        LinearLayout.LayoutParams blp = wide();
        blp.topMargin = dp(8);
        body.addView(box, blp);
        // spinner + "Sending…" under the box while a reply goes out (a second or two)
        sending.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sending.setPadding(0, dp(8), 0, 0);
        android.widget.ProgressBar spin = new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleSmall);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Widgets.BLUE));
        sending.addView(spin);
        TextView st = text(14, Widgets.SUB);
        st.setText("Sending…");
        st.setPadding(dp(8), 0, 0, 0);
        sending.addView(st);
        sending.setVisibility(View.GONE);
        body.addView(sending);
        error.setPadding(0, dp(8), 0, 0);
        error.setVisibility(View.GONE);
        body.addView(error);

        android.widget.Switch check = new android.widget.Switch(a);
        check.setText("Check for replies");
        check.setTextColor(Widgets.SUB);
        check.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        check.setChecked(prefs.getBoolean("anon_check_replies", true));
        check.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("anon_check_replies", on).apply());
        LinearLayout.LayoutParams clp = wide();
        clp.topMargin = dp(12);
        body.addView(check, clp);

        TextView web = text(13, Widgets.BLUE);
        web.setText("Open on GitHub ›");
        web.setPadding(0, dp(10), 0, dp(2));
        web.setOnClickListener(v -> {
            try {
                a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(BuildConfig.REPO_URL + "/issues/" + issue)));
            } catch (android.content.ActivityNotFoundException e) {
                android.widget.Toast.makeText(a, "No app can open GitHub links", android.widget.Toast.LENGTH_LONG).show();
            }
        });
        body.addView(web);

        scroll = new ScrollView(a);
        scroll.addView(body);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Report #" + issue)
                .setView(scroll)
                .setPositiveButton("Send", null)
                .setNegativeButton("Close", null)
                .create();
        if (d.getWindow() != null)
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> send()));
        dialog = d;
        Widgets.showStyled(d);
        showClosed();
        load();
    }

    /** Closed issues take no replies: no box, no Send. */
    private void showClosed() {
        int vis = closed() ? View.GONE : View.VISIBLE;
        box.setVisibility(vis);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(vis);
    }

    /** Fetches the comments, marks the report read, and shows the developer's comments and this phone's replies. */
    private void load() {
        status.setTextColor(Widgets.SUB);
        status.setText("Loading replies…");
        status.setVisibility(View.VISIBLE);
        new Thread(() -> {
            JSONArray cs = null;
            int code = 0;
            try {
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
                    if (cs != null) {
                        AnonReports.applyComments(r, cs, bot, tag);
                        AnonReports.markRead(r);
                    } else {
                        AnonReports.applyIssue(r, code, null);   // 404/410: the issue is gone, stop checking it
                    }
                }
                AnonReports.saveReports(prefs, all);
            }
            JSONArray got = cs;
            int c = code;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                onChanged.run();
                if (!dialog.isShowing()) return;
                showClosed();
                if (got == null) {
                    status.setTextColor(Widgets.RED);
                    status.setText(c == 0 ? "Couldn't reach GitHub. Check the connection and try again."
                            : "GitHub answered " + c + ". Try again later.");
                    return;
                }
                render(got, bot, tag);
            });
        }, "anon-thread").start();
    }

    private void render(JSONArray cs, String bot, String tag) {
        list.removeAllViews();
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
            String date = c.optString("created_at");
            TextView head = text(13, Widgets.SUB);
            head.setText(who + " · " + (date.length() >= 10 ? date.substring(0, 10) : date));
            head.setPadding(0, dp(list.getChildCount() == 0 ? 4 : 14), 0, dp(2));
            list.addView(head);
            TextView t = text(15, Widgets.TEXT);
            t.setText(text);
            t.setTextIsSelectable(true);
            list.addView(t);
        }
        status.setVisibility(list.getChildCount() == 0 ? View.VISIBLE : View.GONE);
        status.setTextColor(Widgets.SUB);
        status.setText("No replies yet.");
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
                AnonReports.reply(a, issue, text);
            } catch (IOException e) {
                code = e instanceof AnonReports.HttpError ? ((AnonReports.HttpError) e).code : 0;
                msg = AnonReports.errorMessage(e);
            }
            int c = code;
            String m = msg;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || !dialog.isShowing()) return;
                sendingState(false);
                if (c >= 0) {
                    BLog.log("anon_reply_failed", "code", c);
                    error.setText(m);
                    error.setVisibility(View.VISIBLE);
                    scroll.post(() -> scroll.smoothScrollTo(0, error.getBottom()));
                    return;
                }
                box.setText("");
                load();
            });
        }, "anon-reply").start();
    }

    /** While a reply sends: Send and the box are off and dimmed, and the spinner shows. */
    private void sendingState(boolean on) {
        android.widget.Button send = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        send.setEnabled(!on);
        send.setAlpha(on ? 0.5f : 1f);
        box.setEnabled(!on);
        sending.setVisibility(on ? View.VISIBLE : View.GONE);
    }
}
