package com.crbyob.bridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * GitHub build: the Ko-fi button under the dashboard footer. Bundled as a vector drawable, so showing it makes
 * no network request. (The Play build has none: Play's payments policy restricts links to outside donation
 * pages. See flavors/play.)
 */
final class Donate {
    private Donate() {}

    /** The button view, or null when this build has no donation link. */
    static View footerButton(Activity a) {
        ImageView b = new ImageView(a);
        b.setImageResource(R.drawable.kofi_button);
        b.setAdjustViewBounds(true);
        b.setContentDescription("Support me on Ko-fi");
        b.setOnClickListener(x -> {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.DONATE_URL)));
            } catch (Exception e) {
                Toast.makeText(a, "No browser to open " + BuildConfig.DONATE_URL, Toast.LENGTH_LONG).show();
            }
        });
        return b;
    }
}
