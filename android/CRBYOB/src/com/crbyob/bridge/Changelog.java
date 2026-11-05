package com.crbyob.bridge;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Release notes: CHANGELOG.md (bundled as an asset at build time) parsed into one entry per version. */
final class Changelog {
    private Changelog() {}

    static final class Entry {
        final String version;   // "0.1.0", or e.g. "Unreleased"
        final int code;         // AppVersion.codeOf(version), -1 if not a release
        final String date;      // "2026-11-10", or null
        final String body;      // Markdown below the heading

        Entry(String version, String date, String body) {
            this.version = version;
            this.code = AppVersion.codeOf(version);
            this.date = date;
            this.body = body;
        }
    }

    /** "## [0.1.0] - 2026-11-10", "## 0.1.0", "## [Unreleased]". "###" subsection headings do not match. */
    private static final Pattern HEADING = Pattern.compile("^##\\s+\\[?v?([^\\]\\s]+)\\]?(?:\\s+-\\s+(.+?))?\\s*$");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Entries in file order (newest first). Text before the first "## " heading is ignored. */
    static List<Entry> parse(String md) {
        List<Entry> out = new ArrayList<>();
        String version = null, date = null;
        StringBuilder body = new StringBuilder();
        for (String line : md.replace("\r", "").split("\n", -1)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                if (version != null) out.add(new Entry(version, date, body.toString().trim()));
                version = m.group(1);
                date = m.group(2) != null && DATE.matcher(m.group(2)).matches() ? m.group(2) : null;
                body.setLength(0);
            } else if (version != null) {
                body.append(line).append('\n');
            }
        }
        if (version != null) out.add(new Entry(version, date, body.toString().trim()));
        return out;
    }

    /** The bundled CHANGELOG.md; empty if the asset is missing or unreadable. */
    static List<Entry> load(Context c) {
        try (InputStream in = c.getAssets().open("CHANGELOG.md")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
            return parse(new String(bo.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            BLog.log("changelog_load_failed", "err", String.valueOf(e));
            return Collections.emptyList();
        }
    }

    /** Released entries with fromExclusive < code <= toInclusive and some text, newest first. */
    static List<Entry> between(List<Entry> all, int fromExclusive, int toInclusive) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all)
            if (e.code > fromExclusive && e.code <= toInclusive && !e.body.isEmpty()) out.add(e);
        Collections.sort(out, (a, b) -> Integer.compare(b.code, a.code));
        return out;
    }
}
