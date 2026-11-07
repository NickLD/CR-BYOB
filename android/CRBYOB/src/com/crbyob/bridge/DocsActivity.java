package com.crbyob.bridge;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.crbyob.bridge.Widgets.*;

/**
 * Reads the bundled user docs (see {@link Docs}) in the app: the same text as on GitHub, for the installed
 * version, offline. Links to other bundled docs and to #sections stay here; everything else opens in the
 * browser. "GitHub" opens the page on GitHub.
 */
public final class DocsActivity extends Activity {
    static final String EXTRA_PATH = "path", EXTRA_ANCHOR = "anchor";

    /** Opens a bundled doc, scrolled to `anchor` (a GitHub heading slug) if given. */
    static void open(Context c, String path, String anchor) {
        c.startActivity(new Intent(c, DocsActivity.class).putExtra(EXTRA_PATH, path).putExtra(EXTRA_ANCHOR, anchor));
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Map<String, View> anchors = new HashMap<>();
    private final Map<View, Integer> headingLevel = new HashMap<>();
    private final Map<View, Runnable> expanders = new HashMap<>();   // details card -> opens it
    private android.animation.ValueAnimator flash;
    private String path;
    private ScrollView scroll;
    private LinearLayout body;
    private int contentWidthPx;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainActivity.applyLockScreenMode(this);           // over the lock screen only with the dev option on
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        Widgets.fitSystemBars(this);
        path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !Docs.bundled(path)) path = Docs.HOME;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(8), dp(4));
        TextView back = barButton("←");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = new TextView(this);
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!Docs.INDEX.equals(path)) {                  // the docs index: every page, bundled or on GitHub
            TextView toc = barButton("☰");
            toc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            toc.setContentDescription("All docs");
            toc.setOnClickListener(v -> open(this, Docs.INDEX, null));
            bar.addView(toc, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        TextView gh = barButton("View on GitHub");
        gh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        gh.setBackground(rounded(LINE, dp(10)));
        gh.setPadding(dp(12), dp(8), dp(12), dp(8));
        gh.setContentDescription("Open this page on GitHub");
        gh.setOnClickListener(v -> openInBrowser(this, Docs.webUrl(BuildConfig.REPO_URL, path, currentAnchor())));
        bar.addView(gh);
        root.addView(bar);
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        root.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(32));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        contentWidthPx = getResources().getDisplayMetrics().widthPixels - dp(32);
        String md = readAsset(Docs.ASSET_ROOT + path);
        if (md == null) {
            title.setText("Docs");
            body.addView(para("This page isn't in this build. Tap GitHub to read it online."));
            return;
        }
        List<Docs.Block> blocks = Docs.parse(path, md);
        String t = path;
        for (Docs.Block k : blocks) if (k.kind == Docs.Kind.HEADING && k.level == 1) { t = Docs.plain(k.text); break; }
        title.setText(t);
        if (Docs.INDEX.equals(path)) renderIndex(blocks);
        else render(blocks);
        String anchor = getIntent().getStringExtra(EXTRA_ANCHOR);
        if (anchor != null && b == null) scrollTo(anchor);
    }

    @Override
    protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }

    private TextView barButton(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private static GradientDrawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** The heading at the top of the screen, so "GitHub" opens the same section. */
    private String currentAnchor() {
        String best = null;
        int y = scroll.getScrollY() + dp(8);
        int top = Integer.MIN_VALUE;
        for (Map.Entry<String, View> e : anchors.entrySet()) {
            int t = e.getValue().getTop();
            if (t <= y && t > top) { top = t; best = e.getKey(); }
        }
        return best;
    }

    /** Scrolls the section's heading to the top (as far as the page allows) and flashes the section. */
    private void scrollTo(String anchor) {
        View v = anchors.get(anchor.toLowerCase(Locale.ROOT));
        if (v == null) return;
        Runnable expand = expanders.get(v);
        if (expand != null) expand.run();                  // a collapsed question opens first
        // after the next layout (image boxes are sized before their bitmaps decode, so nothing moves later)
        v.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                v.getViewTreeObserver().removeOnPreDrawListener(this);
                scroll.smoothScrollTo(0, Math.max(0, v.getTop() - dp(8)));
                highlight(v);
                return true;
            }
        });
        v.invalidate();
    }

    /**
     * Fades a blue box in and out over the section: the heading down to the next heading of the same or a
     * higher level. Drawn in the page's overlay, so tables, callouts and images keep their own backgrounds.
     */
    private void highlight(View heading) {
        Integer level = headingLevel.get(heading);
        int i = body.indexOfChild(heading);
        if (i < 0) return;
        int bottom = heading.getBottom() - dp(6);               // a question card: just the card
        if (level != null) {
            bottom = body.getChildAt(body.getChildCount() - 1).getBottom();
            for (int j = i + 1; j < body.getChildCount(); j++) {
                Integer l = headingLevel.get(body.getChildAt(j));
                if (l != null && l <= level) { bottom = body.getChildAt(j).getTop() - dp(10); break; }
            }
        }
        GradientDrawable box = rounded((BLUE & 0x00FFFFFF) | 0x24000000, dp(10));
        box.setStroke(Math.max(1, dp(1.5f)), (BLUE & 0x00FFFFFF) | 0x99000000);
        box.setBounds(body.getPaddingLeft() - dp(8), heading.getTop() - dp(6),
                body.getWidth() - body.getPaddingRight() + dp(8), bottom + dp(6));
        if (flash != null) flash.end();
        body.getOverlay().clear();
        body.getOverlay().add(box);
        box.setAlpha(0);
        flash = android.animation.ValueAnimator.ofInt(0, 255, 255, 0);   // in, hold, out: 1 s each
        flash.setDuration(3000);
        flash.addUpdateListener(a -> box.setAlpha((int) a.getAnimatedValue()));
        flash.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { body.getOverlay().remove(box); }
        });
        flash.start();
    }

    /**
     * The docs index (☰): only the pages bundled in the app, under the version they belong to. The rest
     * (developer docs) is one tap away through "View on GitHub", which opens the full index there. Entries
     * come from the same link rules as everywhere else, so a page shows up here once it's bundled.
     */
    private void renderIndex(List<Docs.Block> blocks) {
        for (Docs.Block k : blocks) {
            if (k.kind != Docs.Kind.HEADING || k.level != 1) continue;
            body.addView(heading(k));
            break;
        }
        TextView ver = text(14, SUB);                          // the bundled docs are this build's
        ver.setText("For v" + AppVersion.NAME);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vl.topMargin = dp(6);
        body.addView(ver, vl);
        boolean first = true;
        for (Docs.Block k : Docs.indexEntries(BuildConfig.REPO_URL, path, blocks)) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(first ? 16 : 10);
            body.addView(item(k), lp);
            first = false;
        }
    }

    // ------------------------------------------------------------------ blocks
    private void render(List<Docs.Block> blocks) {
        render(blocks, body);
    }

    private void render(List<Docs.Block> blocks, LinearLayout into) {
        Docs.Kind prev = null;
        for (Docs.Block k : blocks) {
            View v;
            int top = prev == null ? 0 : dp(10);
            switch (k.kind) {
                case HEADING: v = heading(k); top = prev == null ? dp(4) : dp(k.level <= 2 ? 22 : 16); break;
                case PARA: v = para(k.text); break;
                case ITEM: v = item(k); top = prev == Docs.Kind.ITEM ? dp(4) : dp(10); break;
                case CODE: v = code(k.text); break;
                case DIAGRAM: v = diagram(); break;
                case TABLE: v = table(k); break;
                case IMAGES: v = images(k.images); top = dp(12); break;
                case CAPTION: v = caption(k.text); top = dp(6); break;
                case CALLOUT: v = callout(k); top = dp(12); break;
                case DETAILS: v = details(k); top = prev == Docs.Kind.DETAILS ? dp(8) : dp(12); break;
                default: continue;
            }
            if (prev == null && k.kind != Docs.Kind.HEADING) top = 0;      // nothing above it (e.g. inside a card)
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = top;
            into.addView(v, lp);
            if (k.kind == Docs.Kind.DETAILS && k.slug != null) anchors.put(k.slug, v);
            if (k.kind == Docs.Kind.HEADING) {
                anchors.put(k.slug, v);
                headingLevel.put(v, k.level);
            }
            prev = k.kind;
        }
    }

    /** &lt;details&gt;: a card with the question; tap to open or close the answer under it. */
    private View details(Docs.Block k) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, dp(10)));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        head.setClickable(true);
        head.setFocusable(true);
        TextView chev = text(15, SUB);
        head.addView(chev, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView q = text(15, TEXT);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setText(inline(k.text));
        head.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(head);
        LinearLayout answer = new LinearLayout(this);
        answer.setOrientation(LinearLayout.VERTICAL);
        answer.setPadding(dp(12), 0, dp(12), dp(14));
        render(k.children, answer);
        card.addView(answer);
        boolean[] open = {k.open};
        Runnable apply = () -> {
            answer.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            chev.setText(open[0] ? "▾" : "▸");
            head.setContentDescription(Docs.plain(k.text) + (open[0] ? ", expanded" : ", collapsed"));
        };
        apply.run();
        head.setOnClickListener(v -> { open[0] = !open[0]; apply.run(); });
        expanders.put(card, () -> { open[0] = true; apply.run(); });
        return card;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(2), 1f);
        t.setLinkTextColor(BLUE);
        return t;
    }

    private View heading(Docs.Block k) {
        float[] size = {24, 20, 17, 15, 15, 15};
        TextView t = text(size[Math.min(k.level, 6) - 1], TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setText(inline(k.text));
        if (k.level > 2) return t;
        LinearLayout c = new LinearLayout(this);          // h1/h2 get GitHub's rule underneath
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(t);
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        rl.topMargin = dp(6);
        c.addView(rule, rl);
        return c;
    }

    private TextView para(String md) {
        TextView t = text(15, TEXT);
        setInline(t, md);
        return t;
    }

    private void setInline(TextView t, String md) {
        CharSequence s = inline(md);
        t.setText(s);
        if (s instanceof Spanned && ((Spanned) s).getSpans(0, s.length(), ClickableSpan.class).length > 0)
            t.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private View item(Docs.Block k) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(4 + 18 * k.level), 0, 0, 0);
        TextView mk = text(15, SUB);
        mk.setText(k.marker);
        row.addView(mk, new LinearLayout.LayoutParams(dp("•".equals(k.marker) ? 16 : 24), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(para(k.text), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private View code(String src) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setBackground(rounded(CARD, dp(8)));
        TextView t = text(13, TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setText(src);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setTextIsSelectable(true);
        hs.addView(t);
        return hs;
    }

    private View diagram() {
        TextView t = text(14, BLUE);
        t.setText("Diagram: view it on GitHub ↗");
        t.setBackground(rounded(CARD, dp(8)));
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setOnClickListener(v -> openInBrowser(this, Docs.webUrl(BuildConfig.REPO_URL, path, null)));
        return t;
    }

    /**
     * Columns sized from their text: each wants its longest cell on one line (capped at 220 dp) and needs at
     * least its longest word. Up to 3 columns are fitted to the screen, giving up width down to that minimum;
     * wider tables (the phone matrix) keep their size and scroll sideways.
     */
    private View table(Docs.Block k) {
        int cols = 0;
        for (String[] r : k.rows) cols = Math.max(cols, r.length);
        TextPaint p = new TextPaint();
        p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics()));
        p.setTypeface(Typeface.DEFAULT_BOLD);                        // bold measures wider: safe for both
        int pad = dp(20) + 2;
        float[] want = new float[cols], min = new float[cols];
        for (String[] r : k.rows) {
            for (int c = 0; c < r.length; c++) {
                String plain = Docs.plain(r[c]);
                want[c] = Math.max(want[c], Math.min(p.measureText(plain) + pad, dp(220)));
                for (String w : plain.split("\\s+")) min[c] = Math.max(min[c], Math.min(p.measureText(w) + pad, dp(220)));
            }
        }
        float total = 0, totalMin = 0;
        for (int c = 0; c < cols; c++) { total += want[c]; totalMin += min[c]; }
        boolean fit = cols <= 3;
        int[] width = new int[cols];
        float avail = contentWidthPx - (cols + 1);
        for (int c = 0; c < cols; c++) {
            float w = want[c];
            if (fit && total > avail) {
                float spare = Math.max(0, avail - totalMin);       // shared in proportion to what each gives up
                w = min[c] + (total - totalMin > 0 ? spare * (want[c] - min[c]) / (total - totalMin) : 0);
            } else if (fit) {
                w = want[c] * avail / total;                       // room to spare: widen in proportion
            }
            width[c] = Math.round(w);
        }

        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        tl.setBackground(rounded(LINE, dp(8)));
        tl.setPadding(1, 1, 1, 1);
        for (int r = 0; r < k.rows.size(); r++) {
            LinearLayout row = new LinearLayout(this);
            String[] cells = k.rows.get(r);
            for (int c = 0; c < cols; c++) {
                TextView t = text(14, TEXT);
                if (c < cells.length) setInline(t, cells[c]);
                t.setPadding(dp(10), dp(8), dp(10), dp(8));
                t.setBackgroundColor(r == 0 ? CARD : BG);
                if (r == 0) t.setTypeface(Typeface.DEFAULT_BOLD);
                int a = c < k.align.length ? k.align[c] : -1;
                t.setGravity(a == 0 ? Gravity.CENTER_HORIZONTAL : a > 0 ? Gravity.END : Gravity.START);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width[c], ViewGroup.LayoutParams.MATCH_PARENT);
                lp.setMargins(c == 0 ? 0 : 1, r == 0 ? 0 : 1, 0, 0);
                row.addView(t, lp);
            }
            tl.addView(row);
        }
        if (fit) return tl;
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(tl);
        return hs;
    }

    private View caption(String md) {
        TextView t = text(13, SUB);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        setInline(t, md);
        return t;
    }

    private View callout(Docs.Block k) {
        int color = k.note == null ? SUB : "TIP".equals(k.note) ? GREEN : "WARNING".equals(k.note) ? AMBER
                : "CAUTION".equals(k.note) ? RED : "IMPORTANT".equals(k.note) ? 0xFFA371F7 : BLUE;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = rounded((color & 0x00FFFFFF) | 0x1F000000, dp(8));
        bg.setStroke(dp(1), (color & 0x00FFFFFF) | 0x66000000);
        box.setBackground(bg);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (k.note != null) {
            TextView label = text(14, color);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setText(k.note.charAt(0) + k.note.substring(1).toLowerCase(Locale.US));
            box.addView(label);
        }
        box.addView(para(k.text));
        return box;
    }

    // ------------------------------------------------------------------ images
    /**
     * One row of images, as GitHub lays out &lt;img&gt; tags side by side: each at its width/height attribute
     * (CSS px ≈ dp), the row scaled down to fit the screen. Bitmaps decode off the main thread.
     */
    private View images(List<Docs.Img> imgs) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        int n = imgs.size();
        float[] w = new float[n], hgt = new float[n];
        float total = 0;
        int gap = dp(8);
        for (int i = 0; i < n; i++) {
            Docs.Img im = imgs.get(i);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            if (!Docs.isUrl(im.src)) decodeAsset(im.src, o);
            if (o.outWidth <= 0) continue;                     // remote or missing: skipped
            float aspect = (float) o.outWidth / o.outHeight;
            if (im.width < 0) { w[i] = contentWidthPx * Math.min(100, -im.width) / 100f; hgt[i] = w[i] / aspect; }
            else if (im.width > 0) { w[i] = dp(im.width); hgt[i] = w[i] / aspect; }
            else if (im.height > 0) { hgt[i] = dp(im.height); w[i] = hgt[i] * aspect; }
            else { w[i] = Math.min(o.outWidth, contentWidthPx); hgt[i] = w[i] / aspect; }
            total += w[i];
        }
        int shown = 0;
        for (float x : w) if (x > 0) shown++;
        float avail = contentWidthPx - gap * Math.max(0, shown - 1);
        float scale = total > avail ? avail / total : 1f;
        for (int i = 0; i < n; i++) {
            if (w[i] <= 0) continue;
            Docs.Img im = imgs.get(i);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setContentDescription(im.alt);
            iv.setBackground(rounded(CARD, dp(6)));
            int pw = Math.round(w[i] * scale), ph = Math.round(hgt[i] * scale);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pw, ph);
            if (row.getChildCount() > 0) lp.leftMargin = gap;
            row.addView(iv, lp);
            iv.setOnClickListener(v -> showFull(im));
            decoder.execute(() -> {
                Bitmap bm = decodeSampled(im.src, pw);
                if (bm != null) h.post(() -> {
                    if (isDestroyed()) return;
                    iv.setImageBitmap(bm);
                    iv.setBackground(null);              // the placeholder would show through transparent PNGs
                });
            });
        }
        return row;
    }

    private Bitmap decodeAsset(String src, BitmapFactory.Options o) {
        try (InputStream in = getAssets().open(Docs.ASSET_ROOT + src)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
    }

    private Bitmap decodeSampled(String src, int targetW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        decodeAsset(src, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return decodeAsset(src, d);
    }

    /** Full screen, tap anywhere to close. */
    private void showFull(Docs.Img im) {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(this);
        iv.setBackgroundColor(0xFF000000);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription(im.alt);
        iv.setOnClickListener(v -> d.dismiss());
        d.setContentView(iv);
        decoder.execute(() -> {
            Bitmap bm = decodeAsset(im.src, new BitmapFactory.Options());
            if (bm != null) h.post(() -> iv.setImageBitmap(bm));
        });
        d.show();
    }

    // ------------------------------------------------------------------ inline markdown + links
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[((?:[^\\[\\]]|\\[[^\\]]*\\])+)\\]\\(([^)\\s]+)\\)"
                    + "|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)"
                    + "|<br\\s*/?>|<[^>]+>");

    private CharSequence inline(String md) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendInline(sb, md);
        return sb;
    }

    private void appendInline(SpannableStringBuilder sb, String s) {
        Matcher m = INLINE.matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            int st = sb.length();
            if (m.group(1) != null || m.group(2) != null) {
                appendInline(sb, m.group(1) != null ? m.group(1) : m.group(2));
                sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(3) != null) {
                sb.append(m.group(3));
                sb.setSpan(new TypefaceSpan("monospace"), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new BackgroundColorSpan(LINE), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(4) != null) {
                appendInline(sb, m.group(4));
                final String href = m.group(5);
                sb.setSpan(new ClickableSpan() {
                    @Override public void onClick(View v) { follow(href); }
                    @Override public void updateDrawState(TextPaint p) { p.setColor(BLUE); p.setUnderlineText(false); }
                }, st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(6) != null || m.group(7) != null) {
                appendInline(sb, m.group(6) != null ? m.group(6) : m.group(7));
                sb.setSpan(new StyleSpan(Typeface.ITALIC), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group().toLowerCase(Locale.US).startsWith("<br")) {
                sb.append('\n');
            }                                                   // other HTML tags: dropped
            last = m.end();
        }
        sb.append(s, last, s.length());
    }

    private void follow(String href) {
        Docs.Target t = Docs.target(BuildConfig.REPO_URL, path, href);
        if (t.kind == 'a' || (t.kind == 'd' && t.path.equals(path))) {
            if (t.anchor != null) scrollTo(t.anchor);
        } else if (t.kind == 'd') {
            open(this, t.path, t.anchor);
        } else {
            if (DeviceReport.isFormLink(t.url)) DeviceReport.open(this);   // the form, filled in from this phone
            else openInBrowser(this, t.url);
        }
    }

    /**
     * Opens a link in the browser, not the GitHub app: the GitHub app claims github.com links but ignores the
     * #section anchor and shows the top of the page. The browser is whoever opens an ordinary web page (the
     * GitHub app only claims github.com; needs the https &lt;queries&gt; entry in the manifest). With no default
     * browser set (Android's chooser would answer), or none at all, the link goes to any app that takes it.
     */
    static void openInBrowser(Activity a, String url) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
        android.content.pm.ResolveInfo ri = a.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        String pkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
        if (pkg != null && !"android".equals(pkg)) {               // "android": the chooser, no default browser
            try {
                a.startActivity(new Intent(view).setPackage(pkg));
                return;
            } catch (android.content.ActivityNotFoundException ignored) {
            }
        }
        try {
            a.startActivity(view);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(a, url, Toast.LENGTH_LONG).show();
        }
    }
}
