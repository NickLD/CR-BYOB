package com.crbyob.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The user docs bundled into the app (README.md and docs/*.md, see bundleSharedAssets in build.gradle.kts),
 * parsed into blocks for {@link DocsActivity}. Pure Java (no Android types) so the parser, the anchors and the
 * link rules are unit-tested against the real files.
 *
 * Covers what those docs use on GitHub: headings (with GitHub's anchor slugs), paragraphs, "-" and "1." lists
 * (nested by indent), fenced code, pipe tables, &lt;img&gt; rows with a &lt;sub&gt; caption, "&gt; [!NOTE]"
 * callouts and Mermaid diagrams (shown as a link to GitHub). Inline markup stays in the block text for the view.
 */
final class Docs {
    private Docs() {}

    /** Bundled docs, as repo paths. Assets live under ASSET_ROOT + path. Everything else opens on GitHub. */
    static final String[] BUNDLED = {"README.md", "docs/README.md", "docs/using-the-app.md", "docs/phones.md"};
    static final String ASSET_ROOT = "doc/";
    static final String HOME = "docs/using-the-app.md";
    static final String INDEX = "docs/README.md";

    // Sections of HOME that the app's warnings link to ("How to fix ›"). DocsTest fails if one goes missing.
    static final String HELP_TROUBLESHOOTING = "troubleshooting";
    static final String HELP_USB2 = "scanner-is-on-usb-2";
    static final String HELP_POWER = "scanner-keeps-disconnecting";
    static final String HELP_SLOW = "low-frame-rate";
    static final String HELP_DUAL = "wi-fi-direct-and-wi-fi-at-the-same-time";
    static final String HELP_ETHERNET = "ethernet-has-no-ip-address";
    static final String HELP_MODEL = "scanner-model-warning";
    static final String HELP_NETWORK = "choosing-a-network";

    enum Kind { HEADING, PARA, ITEM, CODE, DIAGRAM, TABLE, IMAGES, CAPTION, CALLOUT, DETAILS }

    static final class Img {
        final String src, alt;      // src: repo path (resolved), or a URL
        final int width, height;    // CSS px from the tag, 0 = not given, negative = percent of the page width
        Img(String src, String alt, int width, int height) {
            this.src = src;
            this.alt = alt;
            this.width = width;
            this.height = height;
        }
    }

    static final class Block {
        final Kind kind;
        int level;                  // heading 1-6; list nesting 0..
        String text = "";           // inline markdown (heading, paragraph, item, caption, callout); code text
        String marker;              // list item: "•" or "3."
        String slug;                // heading anchor
        String note;                // callout kind (NOTE, TIP, ...) or code language
        List<String[]> rows;        // table: header first
        int[] align;                // table: -1 left, 0 center, 1 right
        List<Img> images;
        List<Block> children;       // details: the content shown when opened (text = the summary)
        boolean open;               // details: starts opened (<details open>)
        Block(Kind kind) { this.kind = kind; }
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern ITEM = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$");
    private static final Pattern IMG_TAG = Pattern.compile("<img\\s[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MD_IMG = Pattern.compile("^!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)$");
    private static final Pattern BADGES = Pattern.compile("^(\\[!\\[[^\\]]*\\]\\([^)]*\\)\\]\\([^)]*\\)\\s*)+$");
    private static final Pattern CALLOUT = Pattern.compile("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\\]\\s*$");
    private static final Pattern STRUCTURAL = Pattern.compile("^</?(div|p|center)\\b[^>]*>$|^<br\\s*/?>$", Pattern.CASE_INSENSITIVE);

    /** Parses one doc. `path` is its repo path ("docs/phones.md"), for resolving image paths. */
    static List<Block> parse(String path, String md) {
        List<Block> out = new ArrayList<>();
        String[] lines = stripComments(md.replace("\r", "")).split("\n", -1);
        Block open = null;                  // paragraph or item that the next plain line continues
        Block images = null;                // image row that the next <img> line joins
        Map<String, Integer> slugs = new HashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            if (line.isEmpty()) { open = null; images = null; continue; }

            if (line.toLowerCase(Locale.US).startsWith("<details")) {   // collapsible: <summary> + content
                Block b = new Block(Kind.DETAILS);
                b.open = line.matches("(?i)<details[^>]*\\bopen\\b[^>]*>.*");
                String id = attr(line, "id");
                StringBuilder inner = new StringBuilder();
                String rest = line.replaceFirst("(?i)^<details[^>]*>", "");
                for (; ; ) {
                    int end = rest.toLowerCase(Locale.US).indexOf("</details>");
                    if (end >= 0) { inner.append(rest, 0, end); break; }
                    inner.append(rest).append('\n');
                    if (++i >= lines.length) break;
                    rest = lines[i];
                }
                String in = inner.toString();
                Matcher sm = Pattern.compile("(?is)<summary>(.*?)</summary>").matcher(in);
                String summary = sm.find() ? sm.group(1) : "";
                if (id == null) id = attr(summary, "id");
                if (id == null) id = attr(summary, "name");
                b.text = summary.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
                b.slug = id != null ? id : slug(b.text);
                b.children = parse(path, sm.find(0) ? in.substring(sm.end()) : in);
                out.add(b);
                open = images = null;
                continue;
            }
            if (line.startsWith("```")) {                       // fenced code (or a Mermaid diagram)
                String lang = line.substring(3).trim().toLowerCase(Locale.US);
                StringBuilder code = new StringBuilder();
                for (i++; i < lines.length && !lines[i].trim().startsWith("```"); i++)
                    code.append(code.length() > 0 ? "\n" : "").append(lines[i]);
                Block b = new Block("mermaid".equals(lang) ? Kind.DIAGRAM : Kind.CODE);
                b.text = code.toString();
                b.note = lang;
                out.add(b);
                open = images = null;
                continue;
            }
            if (line.toLowerCase(Locale.US).startsWith("<sub>")) {  // caption under an image row (may span lines)
                StringBuilder sub = new StringBuilder(line);
                while (!sub.toString().toLowerCase(Locale.US).contains("</sub>") && i + 1 < lines.length)
                    sub.append(' ').append(lines[++i].trim());
                Block b = new Block(Kind.CAPTION);
                b.text = sub.toString().replaceAll("(?i)</?sub>", "").trim();
                out.add(b);
                open = images = null;
                continue;
            }
            Matcher m;
            if (IMG_TAG.matcher(line).find() || (m = MD_IMG.matcher(line)).matches()) {
                List<Img> found = new ArrayList<>();
                Matcher t = IMG_TAG.matcher(line);
                while (t.find()) found.add(img(path, attr(t.group(), "src"), attr(t.group(), "alt"),
                        num(attr(t.group(), "width")), num(attr(t.group(), "height"))));
                Matcher md2 = MD_IMG.matcher(line);
                if (found.isEmpty() && md2.matches()) found.add(img(path, md2.group(2), md2.group(1), 0, 0));
                if (images == null) {
                    images = new Block(Kind.IMAGES);
                    images.images = new ArrayList<>();
                    out.add(images);
                }
                images.images.addAll(found);
                open = null;
                continue;
            }
            if (STRUCTURAL.matcher(line).matches()) {           // <div align=…>, <p>, </p>, <br>: layout only
                if (line.toLowerCase(Locale.US).startsWith("<br")) images = null;
                open = null;
                continue;
            }
            if (BADGES.matcher(line).matches()) continue;       // shields.io badges: remote images, skipped
            images = null;

            if ((m = HEADING.matcher(line)).matches()) {
                Block b = new Block(Kind.HEADING);
                b.level = m.group(1).length();
                b.text = m.group(2);
                String s = slug(b.text);
                Integer n = slugs.get(s);                       // GitHub: repeats get -1, -2, ...
                slugs.put(s, n == null ? 1 : n + 1);
                b.slug = n == null ? s : s + "-" + n;
                out.add(b);
                open = null;
            } else if (line.startsWith("|")) {                  // pipe table: header, separator, rows
                List<String[]> rows = new ArrayList<>();
                int[] align = null;
                for (; i < lines.length && lines[i].trim().startsWith("|"); i++) {
                    String[] cells = cells(lines[i].trim());
                    if (rows.size() == 1 && align == null && cells.length > 0 && cells[0].matches(":?-{2,}:?")) {
                        align = new int[cells.length];
                        for (int c = 0; c < cells.length; c++)
                            align[c] = cells[c].startsWith(":") && cells[c].endsWith(":") ? 0 : cells[c].endsWith(":") ? 1 : -1;
                    } else {
                        rows.add(cells);
                    }
                }
                i--;
                Block b = new Block(Kind.TABLE);
                b.rows = rows;
                b.align = align != null ? align : new int[rows.isEmpty() ? 0 : rows.get(0).length];
                out.add(b);
                open = null;
            } else if (line.startsWith(">")) {                  // quote / GitHub callout
                Block b = new Block(Kind.CALLOUT);
                StringBuilder q = new StringBuilder();
                for (; i < lines.length && lines[i].trim().startsWith(">"); i++) {
                    String l = lines[i].trim().substring(1).trim();
                    Matcher c = CALLOUT.matcher(l);
                    if (b.note == null && q.length() == 0 && c.matches()) b.note = c.group(1);
                    else q.append(q.length() > 0 ? " " : "").append(l);
                }
                i--;
                b.text = q.toString();
                out.add(b);
                open = null;
            } else if ((m = ITEM.matcher(raw)).matches()) {
                Block b = new Block(Kind.ITEM);
                int indent = m.group(1).replace("\t", "    ").length();
                b.level = indent == 0 ? 0 : indent < 6 ? 1 : 2;
                String mk = m.group(2);
                b.marker = Character.isDigit(mk.charAt(0)) ? mk.substring(0, mk.length() - 1) + "." : "•";
                b.text = m.group(3);
                out.add(b);
                open = b;
            } else if (open != null) {
                open.text += " " + line;
            } else {
                Block b = new Block(Kind.PARA);
                b.text = line;
                out.add(b);
                open = b;
            }
        }
        for (Block b : out) if (b.text != null) b.text = b.text.replace("&nbsp;", "\u00A0");
        return out;
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    /** "300" -> 300, "100%" -> -100 (percent of the page width), missing or odd -> 0. */
    static int num(String s) {
        if (s == null) return 0;
        try {
            int n = Integer.parseInt(s.replaceAll("[^0-9]", ""));
            return s.trim().endsWith("%") ? -n : n;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Img img(String docPath, String src, String alt, int w, int h) {
        if (src == null) src = "";
        return new Img(isUrl(src) ? src : resolve(docPath, src), alt == null ? "" : alt, w, h);
    }

    private static String[] cells(String row) {
        String r = row;
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        String[] c = r.split("(?<!\\\\)\\|", -1);
        for (int i = 0; i < c.length; i++) c[i] = c[i].trim().replace("\\|", "|");
        return c;
    }

    /** GitHub's heading anchor: plain text, lower case, punctuation dropped, spaces to "-". */
    static String slug(String heading) {
        String t = plain(heading).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            if (Character.isLetterOrDigit(cp) || cp == '-' || cp == '_') sb.appendCodePoint(cp);
            else if (cp == ' ') sb.append('-');
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** Inline markdown reduced to its text: links keep their text, emphasis and code marks go. */
    static String plain(String inline) {
        return inline.replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("<[^>]+>", "")
                .replace("`", "").replace("**", "").replace("__", "")
                .replaceAll("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])", "$1")
                .replaceAll("(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)", "$1");
    }

    static boolean isUrl(String href) {
        return href.matches("(?i)^[a-z][a-z0-9+.-]*:.*");
    }

    /** "docs/phones.md" + "../README.md#x" -> "README.md#x". Keeps a #fragment; drops a leading "./". */
    static String resolve(String docPath, String href) {
        String frag = "";
        int h = href.indexOf('#');
        if (h >= 0) { frag = href.substring(h); href = href.substring(0, h); }
        if (href.isEmpty()) return docPath + frag;
        List<String> parts = new ArrayList<>();
        if (!href.startsWith("/")) {
            int slash = docPath.lastIndexOf('/');
            if (slash >= 0) for (String p : docPath.substring(0, slash).split("/")) parts.add(p);
        }
        for (String p : href.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
            else parts.add(p);
        }
        return String.join("/", parts) + frag;
    }

    static boolean bundled(String path) {
        for (String b : BUNDLED) if (b.equals(path)) return true;
        return false;
    }

    /** Where a link goes: kind 'a' = anchor in this doc, 'd' = bundled doc (+ anchor), 'w' = web URL. */
    static final class Target {
        final char kind;
        final String path, anchor, url;
        Target(char kind, String path, String anchor, String url) {
            this.kind = kind;
            this.path = path;
            this.anchor = anchor;
            this.url = url;
        }
    }

    /** github.com/<repo>/blob/<branch>/ (and tree/) links to a bundled doc stay in the app too. */
    static Target target(String repoUrl, String docPath, String href) {
        String rel = null;
        if (isUrl(href)) {
            Matcher m = Pattern.compile("^" + Pattern.quote(repoUrl) + "/(?:blob|tree)/main/(.*)$").matcher(href);
            if (!m.matches()) return new Target('w', null, null, href);
            rel = "/" + m.group(1);
        }
        String r = resolve(docPath, rel != null ? rel : href);
        int h = r.indexOf('#');
        String path = h >= 0 ? r.substring(0, h) : r;
        String anchor = h >= 0 ? r.substring(h + 1) : null;
        if (path.equals(docPath) && anchor != null && !href.startsWith("http")) return new Target('a', path, anchor, null);
        if (bundled(path)) return new Target('d', path, anchor, null);
        return new Target('w', null, null, webUrl(repoUrl, path, anchor));
    }

    static String webUrl(String repoUrl, String path, String anchor) {
        return repoUrl + "/blob/main/" + path + (anchor != null && !anchor.isEmpty() ? "#" + anchor : "");
    }

    /** The index's entries that open in the app (their first link is a bundled doc); web-only ones are left out. */
    static List<Block> indexEntries(String repoUrl, String docPath, List<Block> blocks) {
        List<Block> out = new ArrayList<>();
        for (Block b : blocks) {
            if (b.kind != Kind.ITEM || b.level != 0) continue;
            List<String> l = links(b.text);
            if (!l.isEmpty() && target(repoUrl, docPath, l.get(0)).kind != 'w') out.add(b);
        }
        return out;
    }

    /** The blocks with the content of every details block after it, for walking a whole doc. */
    static List<Block> flatten(List<Block> blocks) {
        List<Block> out = new ArrayList<>();
        for (Block b : blocks) {
            out.add(b);
            if (b.children != null) out.addAll(flatten(b.children));
        }
        return out;
    }

    /** Every anchor a link can land on: heading slugs and details ids. */
    static java.util.Set<String> anchors(List<Block> blocks) {
        java.util.Set<String> a = new java.util.HashSet<>();
        for (Block b : flatten(blocks)) if (b.slug != null) a.add(b.slug);
        return a;
    }

    /** Every link in the inline text: [text](href). */
    static List<String> links(String inline) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<!!)\\[(?:[^\\]]*)\\]\\(([^)\\s]+)\\)").matcher(inline);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
