package com.crbyob.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * "What's new": after an update, the release notes of every version since the last one this phone ran;
 * from the dashboard footer, the whole changelog (plus "Check for updates" in GitHub builds).
 */
final class WhatsNew {
    private WhatsNew() {}

    enum Result { FIRST_RUN, SHOWN, NONE }

    private static final String K_SEEN = "seen_version_code";

    /**
     * Call once per fresh launch. First run (or the first run of a build that has this feature) records the version
     * and shows nothing; a higher versionCode than last time shows the notes in between.
     */
    static Result maybeShowAfterUpdate(Activity a) {
        SharedPreferences p = a.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        int seen = p.getInt(K_SEEN, -1);
        int cur = AppVersion.CODE;
        if (seen != cur) p.edit().putInt(K_SEEN, cur).apply();
        if (seen == -1) return Result.FIRST_RUN;
        if (cur <= seen) return Result.NONE;
        List<Changelog.Entry> notes = Changelog.between(Changelog.load(a), seen, cur);
        BLog.log("whats_new", "from", seen, "to", cur, "entries", notes.size());
        if (notes.isEmpty()) return Result.NONE;
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("What's new")
                .setView(notesView(a, "Updated to CR-BYOB " + AppVersion.NAME + ".", notes))
                .setPositiveButton("Got it", null)
                .create();
        Widgets.showStyled(d);
        return Result.SHOWN;
    }

    /** Whole changelog, from the dashboard footer. */
    static void showAll(Activity a) {
        List<Changelog.Entry> notes = Changelog.between(Changelog.load(a), 0, Integer.MAX_VALUE);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("What's new")
                .setView(notesView(a, "You have CR-BYOB " + AppVersion.NAME + " (" + AppVersion.channelLabel() + ").", notes))
                .setPositiveButton("Close", null);
        if (Updates.SUPPORTED) b.setNeutralButton("Check for updates", (x, w) -> Updates.checkNow(a));
        AlertDialog d = b.create();
        Widgets.showStyled(d);
    }

    /** Scrollable list of release notes: an optional header line, then per version a title and its notes. */
    static View notesView(Context c, String header, List<Changelog.Entry> entries) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Widgets.dp(c, 22), Widgets.dp(c, 4), Widgets.dp(c, 22), Widgets.dp(c, 8));
        if (header != null) {
            TextView h = text(c, 14, Widgets.SUB);
            h.setText(header);
            col.addView(h);
        }
        if (entries.isEmpty()) {
            TextView t = text(c, 14, Widgets.SUB);
            t.setText("No release notes in this build.");
            t.setPadding(0, Widgets.dp(c, 12), 0, 0);
            col.addView(t);
        }
        for (Changelog.Entry e : entries) {
            TextView title = text(c, 17, Widgets.TEXT);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            SpannableStringBuilder sb = new SpannableStringBuilder(e.version);
            if (e.date != null) {
                int st = sb.length();
                sb.append("  ·  ").append(e.date);
                sb.setSpan(new ForegroundColorSpan(Widgets.SUB), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            title.setText(sb);
            title.setPadding(0, Widgets.dp(c, 16), 0, Widgets.dp(c, 4));
            col.addView(title);
            TextView body = text(c, 14, Widgets.TEXT);
            body.setLineSpacing(Widgets.dp(c, 2), 1f);
            body.setText(Markdown.render(c, e.body));
            col.addView(body);
        }
        ScrollView sv = new ScrollView(c);
        sv.addView(col);
        return sv;
    }

    private static TextView text(Context c, float sp, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }
}
