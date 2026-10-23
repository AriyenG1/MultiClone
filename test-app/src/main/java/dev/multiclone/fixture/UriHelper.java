package dev.multiclone.fixture;

import android.net.Uri;

/** Deliberately hardcoded self-provider URI to exercise the conservative DEX rule. */
public final class UriHelper {
    private UriHelper() { }
    public static Uri selfUri() {
        return Uri.parse("content://dev.multiclone.fixture.state/status");
    }
}
