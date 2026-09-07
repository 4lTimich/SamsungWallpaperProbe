package com.example.samsungwallpaperprobe;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * v0.18 real-bounds tracker.
 *
 * Full tree work is rare: once when Home becomes visible and when we need a new anchor.
 * At frame time we refresh ONE cached launcher node. A snapshot of all icon-like nodes is stored
 * in world coordinates, so the renderer can move every debug outline using one scalar page offset.
 */
public class LauncherAccessibilityService extends AccessibilityService {
    private static final String LAUNCHER = "com.sec.android.app.launcher";
    private static final long POLL_MS = 8L;          // request ~125 Hz; Samsung may publish less
    private static final long IDLE_MS = 120L;
    private static final long MIN_RESCAN_MS = 55L;
    private static final int MAX_TREE_NODES = 1800;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean running = false;
    private long seenHomeEpoch = -1L;
    private long seenRescanGeneration = -1L;
    private long lastFullScanMs = 0L;

    private AccessibilityNodeInfo anchorNode;
    private String anchorLabel = "";
    private float anchorWorldX = 0f;
    private int lastAnchorX = Integer.MIN_VALUE;
    private int lastAnchorY = Integer.MIN_VALUE;
    private long lastAnchorMoveMs = 0L;

    private long pollWindowStart = 0L;
    private int pollWindowCount = 0;
    private long changeWindowStart = 0L;
    private int changeWindowCount = 0;

    private final Runnable tracker = new Runnable() {
        @Override public void run() {
            if (!running) return;

            if (!LauncherScrollBus.wallpaperVisible) {
                handler.postDelayed(this, IDLE_MS);
                return;
            }

            long now = SystemClock.uptimeMillis();
            boolean homeEntry = seenHomeEpoch != LauncherScrollBus.homeEpoch;
            boolean rescan = seenRescanGeneration != LauncherScrollBus.rescanGeneration;

            if (homeEntry || rescan) {
                seenHomeEpoch = LauncherScrollBus.homeEpoch;
                seenRescanGeneration = LauncherScrollBus.rescanGeneration;
                fullScanAndAcquire(homeEntry, now);
            } else if (!sampleAnchor(now) && now - lastFullScanMs >= MIN_RESCAN_MS) {
                fullScanAndAcquire(false, now);
            }

            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences(Prefs.PREFS, MODE_PRIVATE);
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
        if (LauncherScrollBus.wallpaperVisible) {
            // Cheap immediate sample of the one cached node. No tree traversal here.
            sampleAnchor(SystemClock.uptimeMillis());
        }
    }

    private boolean sampleAnchor(long now) {
        if (anchorNode == null) return false;

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
        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        float pos = (anchorWorldX - cx) / w;

        boolean changed = cx != lastAnchorX || cy != lastAnchorY;
        if (changed) {
            LauncherScrollBus.boundsChanges++;
            lastAnchorMoveMs = now;

            LauncherScrollBus.previousChangedPosition = LauncherScrollBus.lastChangedPosition;
            LauncherScrollBus.previousChangedUptimeMs = LauncherScrollBus.lastChangedUptimeMs;
            LauncherScrollBus.lastChangedPosition = pos;
            LauncherScrollBus.lastChangedUptimeMs = now;
            LauncherScrollBus.touchXAtLastBoundsChange = LauncherScrollBus.touchX;
            LauncherScrollBus.touchWasActiveAtLastBoundsChange = LauncherScrollBus.touchActive;

            if (LauncherScrollBus.previousChangedUptimeMs > 0L
                    && now > LauncherScrollBus.previousChangedUptimeMs) {
                LauncherScrollBus.velocityPagesPerSec =
                        (LauncherScrollBus.lastChangedPosition - LauncherScrollBus.previousChangedPosition)
                                * 1000f / (now - LauncherScrollBus.previousChangedUptimeMs);
            }

            changeWindowCount++;
            if (changeWindowStart == 0L) changeWindowStart = now;
            long elapsed = now - changeWindowStart;
            if (elapsed >= 1000L) {
                LauncherScrollBus.boundsChangeHz = changeWindowCount * 1000f / elapsed;
                changeWindowCount = 0;
                changeWindowStart = now;
            }
        } else if (now - lastAnchorMoveMs > 90L) {
            LauncherScrollBus.velocityPagesPerSec = 0f;
        }

        lastAnchorX = cx;
        lastAnchorY = cy;

        LauncherScrollBus.positionValid = true;
        LauncherScrollBus.position = pos;
        LauncherScrollBus.positionUptimeMs = now;
        LauncherScrollBus.positionSequence++;
        LauncherScrollBus.anchorLabel = anchorLabel;
        LauncherScrollBus.anchorLeft = r.left;
        LauncherScrollBus.anchorTop = r.top;
        LauncherScrollBus.anchorRight = r.right;
        LauncherScrollBus.anchorBottom = r.bottom;
        LauncherScrollBus.anchorCenterX = cx;
        LauncherScrollBus.anchorCenterY = cy;
        LauncherScrollBus.anchorWorldX = anchorWorldX;
        LauncherScrollBus.boundsSamples++;
        LauncherScrollBus.trackerState = changed ? "BOUNDS CHANGED" : "BOUNDS POLL";

        pollWindowCount++;
        if (pollWindowStart == 0L) pollWindowStart = now;
        long pollElapsed = now - pollWindowStart;
        if (pollElapsed >= 1000L) {
            LauncherScrollBus.trackerPollHz = pollWindowCount * 1000f / pollElapsed;
            pollWindowCount = 0;
            pollWindowStart = now;
        }

        // A real settled anchor gives us the actual page. No fling prediction involved.
        int pages = clampInt(prefs.getInt(Prefs.KEY_PAGE_COUNT, 4), 2, 9);
        int rounded = clampInt(Math.round(pos), 0, pages - 1);
        if (now - lastAnchorMoveMs > 110L && Math.abs(pos - rounded) < 0.035f) {
            publishPage(rounded, now);
        }

        // Reacquire before the anchor disappears too far. The new anchor's world coordinate is
        // built from the current real position, so the swap is continuous.
        if ((cx < -w * 0.10f || cx > w * 1.10f) && now - lastFullScanMs > 70L) {
            fullScanAndAcquire(false, now);
        }

        return true;
    }

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
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !LAUNCHER.contentEquals(pkg)) {
            LauncherScrollBus.homeActive = false;
            clearAnchor("HOME NOT ACTIVE");
            return;
        }
        LauncherScrollBus.homeActive = true;

        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        ArrayList<Candidate> icons = collectIconCandidates(root, w, h);
        if (icons.isEmpty()) {
            clearAnchor("NO ICON NODES");
            return;
        }

        float basisPos;
        if (LauncherScrollBus.positionValid) {
            basisPos = LauncherScrollBus.position;
        } else if (LauncherScrollBus.authoritativePage >= 0) {
            basisPos = LauncherScrollBus.authoritativePage;
        } else {
            basisPos = clampInt(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0), 0,
                    Math.max(0, prefs.getInt(Prefs.KEY_PAGE_COUNT, 4) - 1));
        }

        publishIconSnapshot(icons, basisPos, w, homeEntry);

        Candidate chosen = chooseAnchor(icons, w, h);
        if (chosen == null) {
            clearAnchor("NO MOVING ICON");
            return;
        }
        acquire(chosen, basisPos, now, w);
    }

    private ArrayList<Candidate> collectIconCandidates(AccessibilityNodeInfo root, int w, int h) {
        ArrayList<Candidate> raw = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;

        while (!q.isEmpty() && seen < MAX_TREE_NODES) {
            AccessibilityNodeInfo n = q.removeFirst();
            seen++;

            Rect r = new Rect();
            n.getBoundsInScreen(r);
            String label = nodeLabel(n);
            if (looksLikeIconNode(n, r, label, w, h)) {
                raw.add(new Candidate(n, r, label));
            }

            int cc = n.getChildCount();
            for (int i = 0; i < cc && seen + q.size() < MAX_TREE_NODES; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.addLast(child);
            }
        }

        // Nested launcher nodes often repeat the same icon. Keep one candidate per strongly
        // overlapping rectangle/label, preferring the smaller, clickable node.
        ArrayList<Candidate> out = new ArrayList<>();
        for (Candidate c : raw) {
            int replace = -1;
            for (int i = 0; i < out.size(); i++) {
                Candidate old = out.get(i);
                if (normalize(old.label).equals(normalize(c.label)) && overlapRatio(old.rect, c.rect) > 0.72f) {
                    replace = i;
                    break;
                }
            }
            if (replace < 0) {
                out.add(c);
            } else {
                Candidate old = out.get(replace);
                int oldArea = Math.max(1, old.rect.width() * old.rect.height());
                int newArea = Math.max(1, c.rect.width() * c.rect.height());
                if ((c.node.isClickable() && !old.node.isClickable()) || newArea < oldArea) out.set(replace, c);
            }
        }
        return out;
    }

    private boolean looksLikeIconNode(AccessibilityNodeInfo n, Rect r, String label, int w, int h) {
        if (label.isEmpty()) return false;
        if (!n.isClickable()) return false;
        int rw = r.width(), rh = r.height();
        if (rw < w * 0.055f || rw > w * 0.42f) return false;
        if (rh < h * 0.035f || rh > h * 0.22f) return false;
        int cy = r.centerY();
        if (cy < h * 0.055f || cy > h * 0.985f) return false;
        return true;
    }

    private Candidate chooseAnchor(ArrayList<Candidate> icons, int w, int h) {
        Candidate best = null;
        double bestCost = Double.MAX_VALUE;
        for (Candidate c : icons) {
            int cx = c.rect.centerX();
            int cy = c.rect.centerY();
            // Dock icons do not move with pages, so never use them as anchor.
            if (cy > h * 0.84f) continue;
            if (cx < -w * 0.20f || cx > w * 1.20f) continue;
            double cost = Math.abs(cx - w * 0.5) + Math.abs(cy - h * 0.47) * 0.18;
            if (cost < bestCost) {
                bestCost = cost;
                best = c;
            }
        }
        return best;
    }

    private void acquire(Candidate c, float basisPos, long now, int w) {
        if (c == null) return;
        anchorNode = c.node;
        anchorLabel = c.label;
        anchorWorldX = c.rect.centerX() + basisPos * w;
        lastAnchorX = Integer.MIN_VALUE;
        lastAnchorY = Integer.MIN_VALUE;
        lastAnchorMoveMs = now;
        LauncherScrollBus.anchorReacquires++;
        LauncherScrollBus.trackerState = "ANCHOR ACQUIRED";
        sampleAnchor(now);
    }

    private void publishIconSnapshot(ArrayList<Candidate> icons, float basisPos, int w, boolean replace) {
        ArrayList<LauncherScrollBus.IconBox> fresh = new ArrayList<>();
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        for (Candidate c : icons) {
            boolean moves = c.rect.centerY() <= h * 0.84f;
            float worldX = moves ? c.rect.centerX() + basisPos * w : c.rect.centerX();
            fresh.add(new LauncherScrollBus.IconBox(
                    worldX,
                    c.rect.centerY(),
                    c.rect.width(),
                    c.rect.height(),
                    c.label,
                    moves));
        }

        if (!replace && LauncherScrollBus.iconSnapshot.length > 0) {
            // Mid-swipe scans should ADD knowledge, not make old outlines disappear.
            ArrayList<LauncherScrollBus.IconBox> merged = new ArrayList<>();
            for (LauncherScrollBus.IconBox old : LauncherScrollBus.iconSnapshot) merged.add(old);
            for (LauncherScrollBus.IconBox n : fresh) {
                int hit = findSnapshotMatch(merged, n);
                if (hit >= 0) merged.set(hit, n); else merged.add(n);
            }
            LauncherScrollBus.iconSnapshot = merged.toArray(new LauncherScrollBus.IconBox[0]);
        } else {
            LauncherScrollBus.iconSnapshot = fresh.toArray(new LauncherScrollBus.IconBox[0]);
        }
        LauncherScrollBus.iconSnapshotVersion++;
    }

    private int findSnapshotMatch(ArrayList<LauncherScrollBus.IconBox> boxes, LauncherScrollBus.IconBox n) {
        String nl = normalize(n.label);
        for (int i = 0; i < boxes.size(); i++) {
            LauncherScrollBus.IconBox o = boxes.get(i);
            if (!normalize(o.label).equals(nl)) continue;
            if (Math.abs(o.centerY - n.centerY) < Math.max(36f, n.height * 0.45f)
                    && Math.abs(o.worldCenterX - n.worldCenterX) < Math.max(90f, n.width * 0.70f)) {
                return i;
            }
        }
        return -1;
    }

    private float overlapRatio(Rect a, Rect b) {
        int l = Math.max(a.left, b.left), t = Math.max(a.top, b.top);
        int r = Math.min(a.right, b.right), bot = Math.min(a.bottom, b.bottom);
        int iw = Math.max(0, r - l), ih = Math.max(0, bot - t);
        int inter = iw * ih;
        if (inter <= 0) return 0f;
        int aa = Math.max(1, a.width() * a.height());
        int ba = Math.max(1, b.width() * b.height());
        return inter / (float) Math.min(aa, ba);
    }

    private void publishPage(int page, long now) {
        if (LauncherScrollBus.authoritativePage == page) return;
        LauncherScrollBus.authoritativePage = page;
        LauncherScrollBus.authoritativePageUptimeMs = now;
        prefs.edit().putInt(Prefs.KEY_SAVED_PAGE, page).apply();
        // At a newly settled page, refresh the cached layout once.
        LauncherScrollBus.requestRescan();
    }

    private void clearAnchor(String reason) {
        anchorNode = null;
        anchorLabel = "";
        lastAnchorX = Integer.MIN_VALUE;
        lastAnchorY = Integer.MIN_VALUE;
        LauncherScrollBus.clearTracking(reason);
    }

    private String nodeLabel(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription();
        CharSequence t = n.getText();
        if (d != null && d.length() > 0) return d.toString();
        if (t != null && t.length() > 0) return t.toString();
        return "";
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace('\u00a0', ' ').trim();
    }

    private int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class Candidate {
        final AccessibilityNodeInfo node;
        final Rect rect;
        final String label;
        Candidate(AccessibilityNodeInfo node, Rect rect, String label) {
            this.node = node;
            this.rect = new Rect(rect);
            this.label = label;
        }
    }

    @Override public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        running = false;
        handler.removeCallbacks(tracker);
        LauncherScrollBus.serviceConnected = false;
        LauncherScrollBus.clearTracking("SERVICE OFF");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(tracker);
        LauncherScrollBus.serviceConnected = false;
        LauncherScrollBus.clearTracking("SERVICE OFF");
        super.onDestroy();
    }
}
