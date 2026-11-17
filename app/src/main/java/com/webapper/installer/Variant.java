package com.webapper.installer;

/** Per-branch settings (android / android-tv / android-xr). */
final class Variant {
    /** Platform name written into each generated app's webapp.json. */
    static final String KIND = "android";
    /** Generate an Android TV launcher banner. */
    static final boolean TV_BANNER = false;

    private Variant() {}
}
