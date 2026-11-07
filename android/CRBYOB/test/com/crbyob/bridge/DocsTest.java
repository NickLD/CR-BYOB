package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The in-app docs: parser, GitHub anchors, link rules, and a lint of every link in the bundled docs. */
public class DocsTest {
    private static final String REPO = "https://github.com/NickLD/CR-BYOB";
    private static final File ROOT = new File("../..");          // unit tests run from the module directory

    private static String read(String rel) throws IOException {
        return new String(Files.readAllBytes(new File(ROOT, rel).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void slugsMatchGitHub() {
        assertEquals("scanner-is-on-usb-2", Docs.slug("Scanner is on USB 2"));
        assertEquals("scanner-frame-rate-override-experimental", Docs.slug("Scanner frame-rate override (experimental)"));
        assertEquals("tested-your-phone-send-a-device-report", Docs.slug("Tested your phone? Send a device report"));
        assertEquals("google-pixel-10-pro-xl", Docs.slug("Google Pixel 10 Pro XL"));
        assertEquals("the-dashboard", Docs.slug("The **dashboard**"));
    }

    @Test
    public void imageSizes() {
        assertEquals(300, Docs.num("300"));
        assertEquals(-100, Docs.num("100%"));
        assertEquals(0, Docs.num(null));
    }

    @Test
    public void linksResolve() {
        Docs.Target t = Docs.target(REPO, "docs/phones.md", "../README.md#choosing-a-connection");
        assertEquals('d', t.kind);
        assertEquals("README.md", t.path);
        assertEquals("choosing-a-connection", t.anchor);
        assertEquals('a', Docs.target(REPO, "docs/phones.md", "#google-pixel-6").kind);
        Docs.Target dev = Docs.target(REPO, "docs/using-the-app.md", "../android/CRBYOB/DIRECT-SB-CRBYOB.xml");
        assertEquals('w', dev.kind);
        assertEquals(REPO + "/blob/main/android/CRBYOB/DIRECT-SB-CRBYOB.xml", dev.url);
        assertEquals('w', Docs.target(REPO, "docs/README.md", "building.md").kind);       // not bundled: GitHub
        Docs.Target gh = Docs.target(REPO, "README.md", REPO + "/blob/main/docs/phones.md#google-pixel-5");
        assertEquals('d', gh.kind);                                                      // own repo link: in the app
        assertEquals("docs/phones.md", gh.path);
        assertEquals('w', Docs.target(REPO, "README.md", "https://ko-fi.com/nickld").kind);
    }

    @Test
    public void parsesTheBlocksTheDocsUse() {
        String md = "# T\n\n<p>\n  <img src=\"screenshots/a.png\" alt=\"A\" width=\"300\">\n"
                + "  <img src=\"screenshots/b.png\" alt=\"B\" width=\"300\">\n  <br>\n  <sub>Left: a.\n  Right: b.</sub>\n</p>\n\n"
                + "1. One\n   - nested\n2. Two\n   continued\n\n| A | B |\n|:---:|---|\n| 1 | 2 |\n\n"
                + "> [!TIP]\n> **Hi** there\n\n```mermaid\nflowchart\n```\n";
        List<Docs.Block> b = Docs.parse("docs/x.md", md);
        Docs.Kind[] kinds = {Docs.Kind.HEADING, Docs.Kind.IMAGES, Docs.Kind.CAPTION, Docs.Kind.ITEM, Docs.Kind.ITEM,
                Docs.Kind.ITEM, Docs.Kind.TABLE, Docs.Kind.CALLOUT, Docs.Kind.DIAGRAM};
        assertEquals(kinds.length, b.size());
        for (int i = 0; i < kinds.length; i++) assertEquals("block " + i, kinds[i], b.get(i).kind);
        assertEquals(2, b.get(1).images.size());
        assertEquals("docs/screenshots/a.png", b.get(1).images.get(0).src);
        assertEquals("Left: a. Right: b.", b.get(2).text);
        assertEquals(1, b.get(4).level);
        assertEquals("2.", b.get(5).marker);
        assertEquals("Two continued", b.get(5).text);
        assertEquals(0, b.get(6).align[0]);
        assertEquals(2, b.get(6).rows.size());
        assertEquals("TIP", b.get(7).note);
        assertEquals("**Hi** there", b.get(7).text);
    }

    @Test
    public void indexListsOnlyBundledPages() throws IOException {
        List<String> app = new ArrayList<>();
        for (Docs.Block b : Docs.indexEntries(REPO, Docs.INDEX, Docs.parse(Docs.INDEX, read(Docs.INDEX))))
            app.add(Docs.links(b.text).get(0));
        assertEquals(Arrays.asList("../README.md", "using-the-app.md", "phones.md"), app);
    }

    @Test
    public void detailsAreCollapsibleQuestions() {
        String md = "## T\n\n<details>\n<summary><a id=\"usb-2\"></a>On USB 2?</summary>\n\nTry **this**:\n\n"
                + "- one\n- two\n\n</details>\n\n<details open><summary>Other?</summary>\n\nText.\n</details>\n\nAfter.\n";
        List<Docs.Block> b = Docs.parse("docs/x.md", md);
        assertEquals(4, b.size());
        Docs.Block d = b.get(1);
        assertEquals(Docs.Kind.DETAILS, d.kind);
        assertEquals("On USB 2?", d.text);
        assertEquals("usb-2", d.slug);
        assertTrue(!d.open);
        assertEquals(3, d.children.size());
        assertEquals(Docs.Kind.PARA, d.children.get(0).kind);
        assertEquals(Docs.Kind.ITEM, d.children.get(2).kind);
        assertEquals("other", b.get(2).slug);
        assertTrue(b.get(2).open);
        assertEquals("After.", b.get(3).text);
        assertTrue(Docs.anchors(b).containsAll(Arrays.asList("t", "usb-2", "other")));
    }

    /** Every Docs.HELP_* section the app's warnings link to exists in the guide. */
    @Test
    public void everyHelpLinkInTheAppExists() throws Exception {
        java.util.Set<String> a = Docs.anchors(Docs.parse(Docs.HOME, read(Docs.HOME)));
        int n = 0;
        for (java.lang.reflect.Field f : Docs.class.getDeclaredFields()) {
            if (!f.getName().startsWith("HELP_")) continue;
            String anchor = (String) f.get(null);
            assertTrue(f.getName() + " -> #" + anchor + " is not in " + Docs.HOME, a.contains(anchor));
            n++;
        }
        assertTrue(n >= 8);
    }

    @Test
    public void bundledListMatchesTheBuild() throws IOException {
        String gradle = new String(Files.readAllBytes(new File("build.gradle.kts").toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("bundledDocs\\.set\\(listOf\\(([^)]*)\\)\\)").matcher(gradle);
        assertTrue("bundledDocs.set(listOf(...)) in build.gradle.kts", m.find());
        List<String> built = new ArrayList<>();
        for (String s : m.group(1).split(",")) built.add(s.trim().replace("\"", ""));
        assertEquals(Arrays.asList(Docs.BUNDLED), built);
        assertTrue(built.contains(Docs.HOME));
    }

    /** Every link in a bundled doc that stays in the app must land on a heading or a bundled doc that exists. */
    @Test
    public void everyInAppLinkAndAnchorExists() throws IOException {
        Map<String, Set<String>> anchors = new HashMap<>();
        Map<String, List<Docs.Block>> docs = new HashMap<>();
        for (String p : Docs.BUNDLED) {
            List<Docs.Block> blocks = Docs.parse(p, read(p));
            docs.put(p, blocks);
            anchors.put(p, Docs.anchors(blocks));
        }
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, List<Docs.Block>> e : docs.entrySet()) {
            for (Docs.Block b : Docs.flatten(e.getValue())) {
                List<String> texts = new ArrayList<>();
                if (b.text != null) texts.add(b.text);
                if (b.rows != null) for (String[] r : b.rows) texts.addAll(Arrays.asList(r));
                for (String t : texts) {
                    for (String href : Docs.links(t)) {
                        Docs.Target tg = Docs.target(REPO, e.getKey(), href);
                        if (tg.kind == 'w') continue;
                        Set<String> a = anchors.get(tg.path);
                        if (a == null) broken.add(e.getKey() + ": " + href + " (not bundled)");
                        else if (tg.anchor != null && !a.contains(tg.anchor)) broken.add(e.getKey() + ": " + href + " (no such heading)");
                    }
                }
                if (b.images != null) for (Docs.Img im : b.images)
                    if (!Docs.isUrl(im.src) && !new File(ROOT, im.src).isFile()) broken.add(e.getKey() + ": image " + im.src);
            }
        }
        assertTrue("broken links:\n" + String.join("\n", broken), broken.isEmpty());
        assertNotNull(anchors.get(Docs.HOME));
        assertTrue("the USB 2 link from the dashboard", anchors.get(Docs.HOME).contains("scanner-is-on-usb-2"));
    }
}
