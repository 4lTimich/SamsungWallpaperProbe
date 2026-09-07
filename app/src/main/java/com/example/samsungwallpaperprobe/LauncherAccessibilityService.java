package com.example.samsungwallpaperprobe;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * v0.16 experimental probe.
 *
 * The service is restricted to One UI Home events. Window-content access is enabled only
 * so we can ask the launcher for the on-screen bounds of ONE user-selected app icon.
 * The code immediately rejects roots whose package is not com.sec.android.app.launcher,
 * does not retain the accessibility tree, and the application has no INTERNET permission.
 */
public class LauncherAccessibilityService extends AccessibilityService {
    private static final String LAUNCHER = "com.sec.android.app.launcher";
    private static final long POLL_MS = 24L; // ~42 Hz diagnostic sampling

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private int lastLeft = Integer.MIN_VALUE;
    private int lastTop = Integer.MIN_VALUE;
    private int lastRight = Integer.MIN_VALUE;
    private int lastBottom = Integer.MIN_VALUE;

    private final Runnable boundsPoll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            sampleAnchorBounds();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        LauncherScrollBus.serviceConnected = true;
        LauncherScrollBus.sequence++;
        running = true;
        handler.removeCallbacks(boundsPoll);
        handler.post(boundsPoll);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !LAUNCHER.contentEquals(pkg)) return;

        LauncherScrollBus.eventUptimeMs = SystemClock.uptimeMillis();
        LauncherScrollBus.eventType = event.getEventType();
        LauncherScrollBus.scrollX = event.getScrollX();
        LauncherScrollBus.scrollY = event.getScrollY();
        LauncherScrollBus.maxScrollX = event.getMaxScrollX();
        LauncherScrollBus.maxScrollY = event.getMaxScrollY();
        if (Build.VERSION.SDK_INT >= 28) {
            LauncherScrollBus.deltaX = event.getScrollDeltaX();
            LauncherScrollBus.deltaY = event.getScrollDeltaY();
        } else {
            LauncherScrollBus.deltaX = 0;
            LauncherScrollBus.deltaY = 0;
        }
        LauncherScrollBus.fromIndex = event.getFromIndex();
        LauncherScrollBus.toIndex = event.getToIndex();
        LauncherScrollBus.itemCount = event.getItemCount();
        LauncherScrollBus.className = event.getClassName() == null ? "" : event.getClassName().toString();

        StringBuilder text = new StringBuilder();
        CharSequence description = event.getContentDescription();
        if (description != null && description.length() > 0) text.append(description);
        List<CharSequence> items = event.getText();
        if (items != null) {
            for (CharSequence item : items) {
                if (item == null || item.length() == 0) continue;
                if (text.length() > 0) text.append(" | ");
                text.append(item);
                if (text.length() > 120) break;
            }
        }
        LauncherScrollBus.summary = text.length() > 120 ? text.substring(0, 120) : text.toString();
        LauncherScrollBus.sequence++;

        // Also sample immediately on a launcher event; the timer fills the gaps between events.
        sampleAnchorBounds();
    }

    private void sampleAnchorBounds() {
        String anchor = getSharedPreferences(Prefs.PREFS, MODE_PRIVATE)
                .getString(Prefs.KEY_BOUNDS_ANCHOR_LABEL, Prefs.DEFAULT_BOUNDS_ANCHOR_LABEL);
        if (anchor == null) anchor = "";
        anchor = anchor.trim();
        if (anchor.isEmpty()) {
            publishMissing(anchor);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            publishMissing(anchor);
            return;
        }
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg == null || !LAUNCHER.contentEquals(rootPkg)) {
            // Important privacy guard: never traverse another app's accessibility tree.
            publishMissing(anchor);
            return;
        }

        AccessibilityNodeInfo best = findAnchor(root, anchor);
        if (best == null) {
            publishMissing(anchor);
            return;
        }

        Rect r = new Rect();
        best.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) {
            publishMissing(anchor);
            return;
        }

        String nodeText = nodeLabel(best);
        boolean changed = r.left != lastLeft || r.top != lastTop || r.right != lastRight || r.bottom != lastBottom;
        if (changed) {
            lastLeft = r.left; lastTop = r.top; lastRight = r.right; lastBottom = r.bottom;
            LauncherScrollBus.boundsChanges++;
        }

        LauncherScrollBus.boundsAnchor = anchor;
        LauncherScrollBus.boundsFound = true;
        LauncherScrollBus.boundsLeft = r.left;
        LauncherScrollBus.boundsTop = r.top;
        LauncherScrollBus.boundsRight = r.right;
        LauncherScrollBus.boundsBottom = r.bottom;
        LauncherScrollBus.boundsCenterX = r.centerX();
        LauncherScrollBus.boundsCenterY = r.centerY();
        LauncherScrollBus.boundsNodeText = nodeText;
        LauncherScrollBus.boundsUptimeMs = SystemClock.uptimeMillis();
        LauncherScrollBus.boundsSamples++;
        LauncherScrollBus.boundsSequence++;
    }

    private void publishMissing(String anchor) {
        LauncherScrollBus.boundsAnchor = anchor;
        LauncherScrollBus.boundsFound = false;
        LauncherScrollBus.boundsUptimeMs = SystemClock.uptimeMillis();
        LauncherScrollBus.boundsSamples++;
        LauncherScrollBus.boundsSequence++;
    }

    private AccessibilityNodeInfo findAnchor(AccessibilityNodeInfo root, String anchor) {
        // Fast path implemented by Accessibility framework / launcher.
        try {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(anchor);
            AccessibilityNodeInfo best = chooseBest(matches, anchor);
            if (best != null) return best;
        } catch (Throwable ignored) {}

        // Fallback for launchers that expose app labels only as contentDescription.
        String needle = normalize(anchor);
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        int seen = 0;
        while (!q.isEmpty() && seen < 900) {
            AccessibilityNodeInfo n = q.removeFirst();
            seen++;
            String label = nodeLabel(n);
            int score = matchScore(label, needle, n);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
            int cc = n.getChildCount();
            for (int i = 0; i < cc && seen + q.size() < 900; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.addLast(child);
            }
        }
        return bestScore >= 100 ? best : null;
    }

    private AccessibilityNodeInfo chooseBest(List<AccessibilityNodeInfo> nodes, String anchor) {
        if (nodes == null || nodes.isEmpty()) return null;
        String needle = normalize(anchor);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null) continue;
            int score = matchScore(nodeLabel(n), needle, n);
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return bestScore >= 100 ? best : null;
    }

    private int matchScore(String label, String needle, AccessibilityNodeInfo node) {
        if (needle.isEmpty()) return Integer.MIN_VALUE;
        String hay = normalize(label);
        if (hay.isEmpty()) return Integer.MIN_VALUE;
        int score;
        if (hay.equals(needle)) score = 500;
        else if (hay.startsWith(needle + " ") || hay.startsWith(needle + ",")) score = 430;
        else if (hay.contains(needle)) score = 300;
        else return Integer.MIN_VALUE;

        Rect r = new Rect();
        node.getBoundsInScreen(r);
        int min = Math.min(r.width(), r.height());
        int max = Math.max(r.width(), r.height());
        // Prefer home-icon sized clickable nodes over tiny nested glyphs or a whole page.
        if (min >= 50 && max <= 450) score += 80;
        if (node.isClickable()) score += 30;
        if (node.isVisibleToUser()) score += 20;
        return score;
    }

    private String nodeLabel(AccessibilityNodeInfo node) {
        StringBuilder s = new StringBuilder();
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null && t.length() > 0) s.append(t);
        if (d != null && d.length() > 0) {
            if (s.length() > 0) s.append(" | ");
            s.append(d);
        }
        return s.toString();
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace('\u00a0', ' ').trim();
    }

    @Override public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        running = false;
        handler.removeCallbacks(boundsPoll);
        LauncherScrollBus.serviceConnected = false;
        LauncherScrollBus.boundsFound = false;
        LauncherScrollBus.sequence++;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(boundsPoll);
        LauncherScrollBus.serviceConnected = false;
        LauncherScrollBus.boundsFound = false;
        LauncherScrollBus.sequence++;
        super.onDestroy();
    }
}
