package com.example.samsungwallpaperprobe;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v0.17 Bounds Engine.
 *
 * Key idea: do NOT inspect every app at frame rate. We snapshot the configured One UI
 * layout when Home becomes visible, cache the relative grid, then refresh exactly ONE
 * AccessibilityNodeInfo at high rate. Its real getBoundsInScreen() X coordinate gives the
 * page translation. When that anchor leaves the screen, one rare tree scan acquires a
 * visible configured app on the incoming page and tracking continues from the same global
 * page coordinate.
 */
public class LauncherAccessibilityService extends AccessibilityService {
    private static final String LAUNCHER = "com.sec.android.app.launcher";
    private static final long TRACK_POLL_MS = 8L;      // ~125 Hz source for a 120 Hz renderer
    private static final long IDLE_POLL_MS = 120L;
    private static final long MIN_RESCAN_MS = 48L;
    private static final int MAX_TREE_NODES = 1400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean running = false;
    private long seenHomeEpoch = -1L;
    private long seenRescanGeneration = -1L;
    private long lastFullScanMs = 0L;
    private boolean slotsDirty = true;

    private final ArrayList<AssignedSlot> assignedSlots = new ArrayList<>();
    private final HashMap<String, Float> slotBiasX = new HashMap<>();
    private final HashMap<String, Float> slotBiasY = new HashMap<>();

    private AccessibilityNodeInfo anchorNode;
    private AssignedSlot anchorSlot;
    private float anchorRestX = 0f;
    private float anchorRestY = 0f;
    private int lastAnchorX = Integer.MIN_VALUE;
    private int lastAnchorY = Integer.MIN_VALUE;
    private long lastAnchorSampleMs = 0L;
    private long lastAnchorMoveMs = 0L;

    private long hzWindowStartMs = 0L;
    private int hzWindowSamples = 0;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sharedPreferences, key) -> {
                if (key == null) return;
                if (key.startsWith("cell_")
                        || Prefs.KEY_PAGE_COUNT.equals(key)
                        || Prefs.KEY_GRID_COLS.equals(key)
                        || Prefs.KEY_GRID_ROWS.equals(key)
                        || Prefs.KEY_GRID_X0.equals(key)
                        || Prefs.KEY_GRID_Y0.equals(key)
                        || Prefs.KEY_CELL_WIDTH.equals(key)
                        || Prefs.KEY_CELL_HEIGHT.equals(key)
                        || Prefs.KEY_GAP_X.equals(key)
                        || Prefs.KEY_GAP_Y.equals(key)) {
                    slotsDirty = true;
                    LauncherScrollBus.requestRescan();
                }
            };

    private final Runnable tracker = new Runnable() {
        @Override public void run() {
            if (!running) return;

            if (!LauncherScrollBus.wallpaperVisible) {
                handler.postDelayed(this, IDLE_POLL_MS);
                return;
            }

            long now = SystemClock.uptimeMillis();
            boolean homeEntry = seenHomeEpoch != LauncherScrollBus.homeEpoch;
            boolean rescanRequested = seenRescanGeneration != LauncherScrollBus.rescanGeneration;

            // Lockscreen / system surfaces can keep the wallpaper visible while One UI Home is
            // not the active accessibility root. Do not hammer getRootInActiveWindow there.
            if (!LauncherScrollBus.homeActive && anchorNode == null && !homeEntry && !rescanRequested) {
                handler.postDelayed(this, IDLE_POLL_MS);
                return;
            }

            if (homeEntry || rescanRequested) {
                seenHomeEpoch = LauncherScrollBus.homeEpoch;
                seenRescanGeneration = LauncherScrollBus.rescanGeneration;
                fullScanAndAcquire(homeEntry, now);
            } else if (!sampleCachedAnchor(now)) {
                if (now - lastFullScanMs >= MIN_RESCAN_MS) {
                    fullScanAndAcquire(false, now);
                }
            }

            handler.postDelayed(this, TRACK_POLL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences(Prefs.PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        running = true;
        LauncherScrollBus.serviceConnected = true;
        LauncherScrollBus.trackerState = "SERVICE READY";
        LauncherScrollBus.requestRescan();
        handler.removeCallbacks(tracker);
        handler.post(tracker);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !LAUNCHER.contentEquals(pkg)) return;
        LauncherScrollBus.homeActive = true;

        // Do not traverse the tree on every event. A launcher event is only a cheap hint to
        // sample our already-cached anchor immediately; the 8 ms tracker fills the gaps.
        if (LauncherScrollBus.wallpaperVisible) {
            sampleCachedAnchor(SystemClock.uptimeMillis());
        }
    }

    private boolean sampleCachedAnchor(long now) {
        if (anchorNode == null || anchorSlot == null) return false;

        try {
            if (!anchorNode.refresh()) {
                clearAnchor("ANCHOR STALE");
                return false;
            }
        } catch (Throwable t) {
            clearAnchor("ANCHOR REFRESH FAILED");
            return false;
        }

        Rect r = new Rect();
        anchorNode.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) {
            clearAnchor("ANCHOR EMPTY");
            return false;
        }

        int cx = r.centerX();
        int cy = r.centerY();
        int screenW = Math.max(1, getResources().getDisplayMetrics().widthPixels);

        // A page translation is simply the real icon displacement from its cached resting X.
        // Content moving left means the global page position increases.
        float pos = anchorSlot.page - (cx - anchorRestX) / screenW;

        float velocityPages = 0f;
        if (lastAnchorSampleMs > 0L && now > lastAnchorSampleMs && lastAnchorX != Integer.MIN_VALUE) {
            velocityPages = -((cx - lastAnchorX) * 1000f / (now - lastAnchorSampleMs)) / screenW;
        }

        if (cx != lastAnchorX || cy != lastAnchorY) {
            LauncherScrollBus.boundsChanges++;
            lastAnchorMoveMs = now;
        }
        lastAnchorX = cx;
        lastAnchorY = cy;
        lastAnchorSampleMs = now;

        LauncherScrollBus.homeActive = true;
        LauncherScrollBus.positionValid = true;
        LauncherScrollBus.position = pos;
        LauncherScrollBus.velocityPagesPerSec = velocityPages;
        LauncherScrollBus.positionUptimeMs = now;
        LauncherScrollBus.positionSequence++;
        LauncherScrollBus.anchorPackage = anchorSlot.pkg;
        LauncherScrollBus.anchorLabel = anchorSlot.label;
        LauncherScrollBus.anchorPage = anchorSlot.page;
        LauncherScrollBus.anchorRow = anchorSlot.row;
        LauncherScrollBus.anchorCol = anchorSlot.col;
        LauncherScrollBus.anchorLeft = r.left;
        LauncherScrollBus.anchorTop = r.top;
        LauncherScrollBus.anchorRight = r.right;
        LauncherScrollBus.anchorBottom = r.bottom;
        LauncherScrollBus.anchorCenterX = cx;
        LauncherScrollBus.anchorCenterY = cy;
        LauncherScrollBus.anchorRestX = anchorRestX;
        LauncherScrollBus.anchorRestY = anchorRestY;
        LauncherScrollBus.boundsSamples++;
        LauncherScrollBus.trackerState = "BOUNDS LIVE";

        hzWindowSamples++;
        if (hzWindowStartMs == 0L) hzWindowStartMs = now;
        long hzElapsed = now - hzWindowStartMs;
        if (hzElapsed >= 1000L) {
            LauncherScrollBus.trackerHz = hzWindowSamples * 1000f / hzElapsed;
            hzWindowSamples = 0;
            hzWindowStartMs = now;
        }

        // When the tracked icon is leaving the viewport, prepare a single reacquisition scan.
        // This is not frame-rate work; it happens roughly once per page transition.
        if ((cx < -screenW * 0.08f || cx > screenW * 1.08f)
                && now - lastFullScanMs > 70L) {
            fullScanAndAcquire(false, now);
        }

        // A stationary anchor on its own page is authoritative. Update the layout bias and
        // current page, but only after it has really stopped moving for a moment.
        if (now - lastAnchorMoveMs > 110L && Math.abs(pos - anchorSlot.page) < 0.035f) {
            float expectedX = configuredCenterX(anchorSlot, screenW);
            float expectedY = configuredCenterY(anchorSlot,
                    Math.max(1, getResources().getDisplayMetrics().heightPixels));
            float bx = cx - expectedX;
            float by = cy - expectedY;
            slotBiasX.put(anchorSlot.key(), bx);
            slotBiasY.put(anchorSlot.key(), by);
            LauncherScrollBus.layoutBiasX = bx;
            LauncherScrollBus.layoutBiasY = by;
            anchorRestX = expectedX + bx;
            anchorRestY = expectedY + by;
            publishAuthoritativePage(anchorSlot.page, now);
        }

        return true;
    }

    /**
     * Rare operation: scan the One UI tree once, match only apps that the user placed in
     * our glass grid, cache their mutual layout, and pick one visible app as the anchor.
     */
    private void fullScanAndAcquire(boolean homeEntry, long now) {
        lastFullScanMs = now;
        LauncherScrollBus.layoutScans++;
        LauncherScrollBus.lastLayoutScanMs = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            LauncherScrollBus.homeActive = false;
            clearAnchor("HOME ROOT MISSING");
            return;
        }
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg == null || !LAUNCHER.contentEquals(rootPkg)) {
            LauncherScrollBus.homeActive = false;
            clearAnchor("HOME NOT ACTIVE");
            return;
        }
        LauncherScrollBus.homeActive = true;

        if (slotsDirty || assignedSlots.isEmpty()) rebuildAssignedSlots();
        if (assignedSlots.isEmpty()) {
            clearAnchor("NO CONFIGURED APPS");
            return;
        }

        ArrayList<Candidate> candidates = collectCandidates(root);
        LauncherScrollBus.visibleSlotsInSnapshot = candidates.size();
        if (candidates.isEmpty()) {
            clearAnchor("NO VISIBLE CONFIGURED APP");
            return;
        }

        int screenW = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int screenH = Math.max(1, getResources().getDisplayMetrics().heightPixels);

        // On Home entry the launcher is normally settled. Pick the page with the most visible
        // configured icons, compute one global layout bias from those nodes, then use a center
        // icon as anchor. This is the user's suggested cached-relative-layout model.
        if (homeEntry || !LauncherScrollBus.positionValid) {
            int preferredPage = mostVisiblePage(candidates,
                    clampInt(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0), 0,
                            Math.max(0, prefs.getInt(Prefs.KEY_PAGE_COUNT, 4) - 1)),
                    screenW, screenH);
            updateSnapshotBias(candidates, preferredPage, screenW, screenH);
            Candidate chosen = chooseCenteredCandidate(candidates, preferredPage, screenW, screenH);
            if (chosen == null) chosen = chooseByContinuity(candidates, screenW);
            acquire(chosen, true, now, screenW, screenH);
            return;
        }

        // Mid-transition reacquisition: choose the candidate whose derived global page position
        // is closest to the last published position, so switching anchors is continuous.
        Candidate chosen = chooseByContinuity(candidates, screenW);
        if (chosen != null) acquire(chosen, false, now, screenW, screenH);
    }

    private void rebuildAssignedSlots() {
        assignedSlots.clear();
        PackageManager pm = getPackageManager();
        int pages = clampInt(prefs.getInt(Prefs.KEY_PAGE_COUNT, 4), 2, 9);
        int rows = clampInt(prefs.getInt(Prefs.KEY_GRID_ROWS, Prefs.DEFAULT_ROWS), 2, 9);
        int cols = clampInt(prefs.getInt(Prefs.KEY_GRID_COLS, Prefs.DEFAULT_COLS), 2, 7);

        for (int p = 0; p < pages; p++) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    String pkg = prefs.getString(Prefs.cellKey(p, r, c), null);
                    if (pkg == null || pkg.trim().isEmpty()) continue;
                    String label = pkg;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        CharSequence cs = pm.getApplicationLabel(ai);
                        if (cs != null && cs.length() > 0) label = cs.toString();
                    } catch (Throwable ignored) {}
                    assignedSlots.add(new AssignedSlot(p, r, c, pkg, label));
                }
            }
        }
        LauncherScrollBus.configuredSlots = assignedSlots.size();
        slotsDirty = false;
    }

    private ArrayList<Candidate> collectCandidates(AccessibilityNodeInfo root) {
        ArrayList<Candidate> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;

        while (!q.isEmpty() && seen < MAX_TREE_NODES) {
            AccessibilityNodeInfo n = q.removeFirst();
            seen++;
            String label = nodeLabel(n);
            if (!label.isEmpty()) {
                for (AssignedSlot slot : assignedSlots) {
                    int score = matchScore(label, slot.normalizedLabel, n);
                    if (score >= 100) {
                        Rect r = new Rect();
                        n.getBoundsInScreen(r);
                        if (r.width() > 0 && r.height() > 0) {
                            out.add(new Candidate(slot, n, r, score));
                        }
                    }
                }
            }
            int cc = n.getChildCount();
            for (int i = 0; i < cc && seen + q.size() < MAX_TREE_NODES; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.addLast(child);
            }
        }

        // De-duplicate repeated nested nodes for the same configured slot, keeping the best one.
        HashMap<String, Candidate> best = new HashMap<>();
        for (Candidate c : out) {
            Candidate old = best.get(c.slot.key());
            if (old == null || c.score > old.score) best.put(c.slot.key(), c);
        }
        return new ArrayList<>(best.values());
    }

    private int mostVisiblePage(List<Candidate> candidates, int fallback, int w, int h) {
        HashMap<Integer, Integer> counts = new HashMap<>();
        for (Candidate c : candidates) {
            int cx = c.rect.centerX(), cy = c.rect.centerY();
            if (cx < 0 || cx > w || cy < 0 || cy > h) continue;
            counts.put(c.slot.page, counts.getOrDefault(c.slot.page, 0) + 1);
        }
        int bestPage = fallback;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount
                    || (e.getValue() == bestCount && Math.abs(e.getKey() - fallback) < Math.abs(bestPage - fallback))) {
                bestPage = e.getKey();
                bestCount = e.getValue();
            }
        }
        return bestPage;
    }

    private void updateSnapshotBias(List<Candidate> candidates, int page, int w, int h) {
        ArrayList<Float> bx = new ArrayList<>();
        ArrayList<Float> by = new ArrayList<>();
        int visible = 0;
        for (Candidate c : candidates) {
            if (c.slot.page != page) continue;
            int cx = c.rect.centerX(), cy = c.rect.centerY();
            if (cx < 0 || cx > w || cy < 0 || cy > h) continue;
            float dx = cx - configuredCenterX(c.slot, w);
            float dy = cy - configuredCenterY(c.slot, h);
            bx.add(dx);
            by.add(dy);
            slotBiasX.put(c.slot.key(), dx);
            slotBiasY.put(c.slot.key(), dy);
            visible++;
        }
        if (!bx.isEmpty()) {
            Collections.sort(bx);
            Collections.sort(by);
            float medX = bx.get(bx.size() / 2);
            float medY = by.get(by.size() / 2);
            LauncherScrollBus.layoutBiasX = medX;
            LauncherScrollBus.layoutBiasY = medY;
        }
        LauncherScrollBus.visibleSlotsInSnapshot = visible;
    }

    private Candidate chooseCenteredCandidate(List<Candidate> candidates, int page, int w, int h) {
        Candidate best = null;
        double bestCost = Double.MAX_VALUE;
        for (Candidate c : candidates) {
            if (c.slot.page != page) continue;
            int cx = c.rect.centerX(), cy = c.rect.centerY();
            if (cx < 0 || cx > w || cy < 0 || cy > h) continue;
            double cost = Math.abs(cx - w * 0.5) + Math.abs(cy - h * 0.45) * 0.15 - c.score * 0.02;
            if (cost < bestCost) { bestCost = cost; best = c; }
        }
        return best;
    }

    private Candidate chooseByContinuity(List<Candidate> candidates, int w) {
        float lastPos = LauncherScrollBus.positionValid
                ? LauncherScrollBus.position
                : prefs.getInt(Prefs.KEY_SAVED_PAGE, 0);
        Candidate best = null;
        float bestCost = Float.MAX_VALUE;
        for (Candidate c : candidates) {
            float rest = configuredCenterX(c.slot, w) + biasXFor(c.slot);
            float pos = c.slot.page - (c.rect.centerX() - rest) / Math.max(1f, w);
            float offscreenPenalty = 0f;
            if (c.rect.centerX() < -w * 0.35f || c.rect.centerX() > w * 1.35f) offscreenPenalty = 0.6f;
            float cost = Math.abs(pos - lastPos) + offscreenPenalty - c.score * 0.0002f;
            if (cost < bestCost) { bestCost = cost; best = c; }
        }
        return best;
    }

    private void acquire(Candidate c, boolean stableHomeEntry, long now, int w, int h) {
        if (c == null) {
            clearAnchor("NO ANCHOR CANDIDATE");
            return;
        }
        anchorNode = c.node;
        anchorSlot = c.slot;
        anchorRestX = configuredCenterX(c.slot, w) + biasXFor(c.slot);
        anchorRestY = configuredCenterY(c.slot, h) + biasYFor(c.slot);
        lastAnchorX = Integer.MIN_VALUE;
        lastAnchorY = Integer.MIN_VALUE;
        lastAnchorSampleMs = 0L;
        lastAnchorMoveMs = now;
        LauncherScrollBus.anchorReacquires++;
        LauncherScrollBus.trackerState = stableHomeEntry ? "LAYOUT SNAPSHOT + ANCHOR" : "ANCHOR REACQUIRED";
        if (stableHomeEntry) publishAuthoritativePage(c.slot.page, now);
        sampleCachedAnchor(now);
    }

    private void publishAuthoritativePage(int page, long now) {
        int pages = clampInt(prefs.getInt(Prefs.KEY_PAGE_COUNT, 4), 2, 9);
        int p = clampInt(page, 0, pages - 1);
        LauncherScrollBus.authoritativePage = p;
        LauncherScrollBus.authoritativePageUptimeMs = now;
        prefs.edit().putInt(Prefs.KEY_SAVED_PAGE, p).apply();
    }

    private float biasXFor(AssignedSlot slot) {
        Float v = slotBiasX.get(slot.key());
        return v != null ? v : LauncherScrollBus.layoutBiasX;
    }

    private float biasYFor(AssignedSlot slot) {
        Float v = slotBiasY.get(slot.key());
        return v != null ? v : LauncherScrollBus.layoutBiasY;
    }

    private float configuredCenterX(AssignedSlot slot, int w) {
        float x0 = prefs.getFloat(Prefs.KEY_GRID_X0, Prefs.DEFAULT_X0);
        float step = prefs.getFloat(Prefs.KEY_CELL_WIDTH, Prefs.DEFAULT_CELL_WIDTH)
                + prefs.getFloat(Prefs.KEY_GAP_X, Prefs.DEFAULT_GAP_X);
        return (x0 + slot.col * step) * w;
    }

    private float configuredCenterY(AssignedSlot slot, int h) {
        float y0 = prefs.getFloat(Prefs.KEY_GRID_Y0, Prefs.DEFAULT_Y0);
        float step = prefs.getFloat(Prefs.KEY_CELL_HEIGHT, Prefs.DEFAULT_CELL_HEIGHT)
                + prefs.getFloat(Prefs.KEY_GAP_Y, Prefs.DEFAULT_GAP_Y);
        return (y0 + slot.row * step) * h;
    }

    private void clearAnchor(String reason) {
        anchorNode = null;
        anchorSlot = null;
        lastAnchorX = Integer.MIN_VALUE;
        lastAnchorY = Integer.MIN_VALUE;
        lastAnchorSampleMs = 0L;
        LauncherScrollBus.clearTracking(reason);
    }

    private int matchScore(String label, String needle, AccessibilityNodeInfo node) {
        if (needle == null || needle.isEmpty()) return Integer.MIN_VALUE;
        String hay = normalize(label);
        if (hay.isEmpty()) return Integer.MIN_VALUE;
        int score;
        if (hay.equals(needle)) score = 500;
        else if (hay.startsWith(needle + " ") || hay.startsWith(needle + ",") || hay.startsWith(needle + " |")) score = 430;
        else if (hay.contains(needle)) score = 260;
        else return Integer.MIN_VALUE;

        Rect r = new Rect();
        node.getBoundsInScreen(r);
        int min = Math.min(r.width(), r.height());
        int max = Math.max(r.width(), r.height());
        if (min >= 40 && max <= 520) score += 70;
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

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class AssignedSlot {
        final int page, row, col;
        final String pkg, label, normalizedLabel;
        AssignedSlot(int page, int row, int col, String pkg, String label) {
            this.page = page;
            this.row = row;
            this.col = col;
            this.pkg = pkg;
            this.label = label;
            this.normalizedLabel = label == null ? "" : label.toLowerCase(Locale.ROOT).replace('\u00a0', ' ').trim();
        }
        String key() { return page + ":" + row + ":" + col + ":" + pkg; }
    }

    private static final class Candidate {
        final AssignedSlot slot;
        final AccessibilityNodeInfo node;
        final Rect rect;
        final int score;
        Candidate(AssignedSlot slot, AccessibilityNodeInfo node, Rect rect, int score) {
            this.slot = slot;
            this.node = node;
            this.rect = new Rect(rect);
            this.score = score;
        }
    }

    @Override public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        running = false;
        handler.removeCallbacks(tracker);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        LauncherScrollBus.serviceConnected = false;
        LauncherScrollBus.clearTracking("SERVICE OFF");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(tracker);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        LauncherScrollBus.serviceConnected = false;
        LauncherScrollBus.clearTracking("SERVICE OFF");
        super.onDestroy();
    }
}
