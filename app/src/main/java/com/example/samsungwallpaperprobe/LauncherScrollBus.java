package com.example.samsungwallpaperprobe;

/**
 * Tiny in-process bridge between the opt-in accessibility service and the
 * wallpaper renderer. It stores only launcher scroll metadata; no UI content
 * from other apps is requested or retained.
 */
public final class LauncherScrollBus {
    private LauncherScrollBus() {}

    public static volatile boolean serviceConnected = false;
    public static volatile long sequence = 0L;
    public static volatile long eventUptimeMs = 0L;
    public static volatile int eventType = 0;
    public static volatile int scrollX = -1;
    public static volatile int scrollY = -1;
    public static volatile int maxScrollX = -1;
    public static volatile int maxScrollY = -1;
    public static volatile int deltaX = 0;
    public static volatile int deltaY = 0;
    public static volatile int fromIndex = -1;
    public static volatile int toIndex = -1;
    public static volatile int itemCount = -1;
    public static volatile String className = "";
    public static volatile String summary = "";

    public static void resetEventValues() {
        scrollX = -1;
        scrollY = -1;
        maxScrollX = -1;
        maxScrollY = -1;
        deltaX = 0;
        deltaY = 0;
        fromIndex = -1;
        toIndex = -1;
        itemCount = -1;
        className = "";
        summary = "";
    }
}
