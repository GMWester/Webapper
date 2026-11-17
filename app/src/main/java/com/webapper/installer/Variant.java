package com.webapper.installer;

/** Per-branch settings (android / android-tv / android-xr). */
final class Variant {
    /** Platform name written into each generated app's webapp.json. */
    static final String KIND = "android-tv";
    /** Generate an Android TV launcher banner. */
    static final boolean TV_BANNER = true;

    private Variant() {}
}
