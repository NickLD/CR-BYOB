package com.crbyob.bridge;

import android.app.Activity;
import android.content.Context;

/**
 * Play build: no self-update. Google Play policy allows updates only through Play, so this build has no
 * GitHub check and no download links; Play's own auto-update delivers new versions and "What's new" shows
 * their notes afterwards. (The GitHub-build version of this class is in flavors/github.)
 */
final class Updates {
    private Updates() {}

    static final boolean SUPPORTED = false;

    static void maybeAutoCheck(Activity a) {}

    static void checkNow(Activity a) {}

    static void setRepoOverride(Context c, String repo) {}
}
