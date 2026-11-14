package com.lanmirror.core.api;

/**
 * Requested resolution/fps/bitrate. Device capabilities are checked, never silently downgraded.
 */
public final class StreamProfile {
    public static final int FPS = 60;
    public static final int DEFAULT = 0;
    public static final int TARGET_END_TO_END_MS = 70;
    public static final int COUNT = 7;
    private static final int[] BITRATES = {24000000, 32000000, 40000000, 60000000, 18000000, 12000000, 6000000};

    private StreamProfile() {
    }

    public static int[] sizeFor(int sourceWidth, int sourceHeight, int profile) {
        boolean portrait = sourceHeight > sourceWidth;
        int width = sanitize(profile) == 6 ? 1280 : 1920;
        int height = sanitize(profile) == 6 ? 720 : 1080;
        return portrait ? new int[]{height, width} : new int[]{width, height};
    }

    public static int bitrate(int profile) {
        return BITRATES[sanitize(profile)];
    }

    public static int sanitize(int profile) {
        return profile >= 0 && profile < COUNT ? profile : DEFAULT;
    }

    /**
     * AVC High has 1.25x the Baseline/Main bitrate limit at the same level.
     */
    public static boolean needsLevel5(boolean highProfile, int width, int height, int bitrate) {
        long levelLimit = (long) width * height >= 2000000L ? 50000000L : 20000000L;
        if (highProfile) levelLimit = levelLimit * 5 / 4;
        return bitrate > levelLimit;
    }
}
