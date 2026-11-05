package com.crbyob.bridge;

import android.app.Activity;
import android.view.View;

/**
 * Play build: no donation link. Google Play's payments policy restricts links to outside donation pages.
 * (The GitHub-build version, with the Ko-fi button, is in flavors/github.)
 */
final class Donate {
    private Donate() {}

    static View footerButton(Activity a) {
        return null;
    }
}
