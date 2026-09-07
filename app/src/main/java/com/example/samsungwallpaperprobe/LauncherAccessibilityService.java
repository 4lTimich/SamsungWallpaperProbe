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
 * v0.19 real-bounds tracker.
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
    private static final long MIN_PREFETCH_SCAN_MS = 48L;
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

    // Layout prefetch is intentionally sparse: a few scans per real swipe, never per frame.
    private int prefetchPairKey = Integer.MIN_VALUE;
    private int prefetchStage = -1;

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
                fullScanAndAcquire(now);
            } else {
                boolean sampled = sampleAnchor(now);
                if (sampled) {
                    maybePrefetchLayout(now);
                } else if (now - lastFullScanMs >= MIN_RESCAN_MS) {
                    fullScanAndAcquire(now);
                }
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
            fullScanAndAcquire(now);
        }

        return true;
    }

    private void fullScanAndAcquire(long now) {
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

        publishIconSnapshot(icons, basisPos, w);

        Candidate chosen = chooseAnchor(icons, w, h);
        if (chosen == null) {
            clearAnchor("NO MOVING ICON");
            return;
        }
        acquire(chosen, basisPos, now, w);
    }

    /**
     * One UI usually exposes the incoming page in the accessibility tree before the swipe
     * finishes.  v0.18 waited until anchor reacquisition / page settle, which is why outlines
     * could pop in late.  We do at most a few tree scans during a gesture and cache what appears.
     */
    private void maybePrefetchLayout(long now) {
        if (!LauncherScrollBus.positionValid) return;

        int pages = clampInt(prefs.getInt(Prefs.KEY_PAGE_COUNT, 4), 2, 9);
        float pos = LauncherScrollBus.position;
        int base = LauncherScrollBus.authoritativePage >= 0
                ? clampInt(LauncherScrollBus.authoritativePage, 0, pages - 1)
                : clampInt(Math.round(pos), 0, pages - 1);

        float delta = pos - base;
        float travel = Math.abs(delta);
        if (travel < 0.045f) {
            prefetchPairKey = Integer.MIN_VALUE;
            prefetchStage = -1;
            return;
        }

        int direction = delta > 0f ? 1 : -1;
        int target = base + direction;
        if (target < 0 || target >= pages) return;

        int pairKey = base * 32 + target;
        if (pairKey != prefetchPairKey) {
            prefetchPairKey = pairKey;
            prefetchStage = -1;
        }

        int stage = travel >= 0.40f ? 2 : (travel >= 0.20f ? 1 : (travel >= 0.075f ? 0 : -1));
        if (stage <= prefetchStage) return;
        if (now - lastFullScanMs < MIN_PREFETCH_SCAN_MS) return;

        // Mark before scanning so a slow/expensive scan cannot be re-entered for the same stage.
        prefetchStage = stage;
        scanLayoutOnly(now);
    }

    private void scanLayoutOnly(long now) {
        lastFullScanMs = now;
        LauncherScrollBus.layoutScans++;
        LauncherScrollBus.lastLayoutScanMs = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !LAUNCHER.contentEquals(pkg)) return;

        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        ArrayList<Candidate> icons = collectIconCandidates(root, w, h);
        if (icons.isEmpty()) return;

        float basisPos = LauncherScrollBus.positionValid
                ? LauncherScrollBus.position
                : (LauncherScrollBus.authoritativePage >= 0
                    ? LauncherScrollBus.authoritativePage
                    : clampInt(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0), 0,
                        Math.max(0, prefs.getInt(Prefs.KEY_PAGE_COUNT, 4) - 1)));
        publishIconSnapshot(icons, basisPos, w);
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
                float overlap = overlapRatio(old.rect, c.rect);
                boolean sameLabel = normalize(old.label).equals(normalize(c.label));
                boolean sameGeometry = Math.abs(old.rect.centerX() - c.rect.centerX()) <= 6
                        && Math.abs(old.rect.centerY() - c.rect.centerY()) <= 6
                        && overlap > 0.84f;
                if ((sameLabel && overlap > 0.72f) || sameGeometry) {
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
        if (!n.isVisibleToUser()) return false;
        // Ignore stale hidden-page nodes that remain in One UI's tree with old bounds. Keep a
        // small off-screen margin so an icon can be cached just before it visibly enters.
        if (r.right < -w * 0.12f || r.left > w * 1.12f) return false;
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

    /**
     * Store icon bounds in stable world coordinates.
     *
     * The important v0.19 change is that snapshots are PAGE-SCOPED. v0.18 blindly merged every
     * later scan into one global list; after a page change that could keep stale nodes and then
     * draw the old page's outlines on the new page. A settled scan replaces only that page, while
     * a mid-swipe scan only adds/updates the visible portions of the incoming/outgoing pages.
     */
    private void publishIconSnapshot(ArrayList<Candidate> icons, float basisPos, int w) {
        int pages = clampInt(prefs.getInt(Prefs.KEY_PAGE_COUNT, 4), 2, 9);
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        ArrayList<LauncherScrollBus.IconBox> fresh = new ArrayList<>();

        for (Candidate c : icons) {
            boolean moves = c.rect.centerY() <= h * 0.84f;
            float worldX = moves ? c.rect.centerX() + basisPos * w : c.rect.centerX();
            int pageIndex = -1;
            if (moves) {
                pageIndex = (int) Math.floor(worldX / Math.max(1f, (float) w));
                if (pageIndex < 0 || pageIndex >= pages) continue;
            }
            addOrReplaceFresh(fresh, new LauncherScrollBus.IconBox(
                    worldX,
                    c.rect.centerY(),
                    c.rect.width(),
                    c.rect.height(),
                    c.label,
                    moves,
                    pageIndex));
        }

        LauncherScrollBus.IconBox[] oldArray = LauncherScrollBus.iconSnapshot;
        ArrayList<LauncherScrollBus.IconBox> merged = new ArrayList<>();
        for (LauncherScrollBus.IconBox old : oldArray) merged.add(old);

        boolean settled = Math.abs(basisPos - Math.round(basisPos)) < 0.065f;
        int settledPage = clampInt(Math.round(basisPos), 0, pages - 1);

        if (settled) {
            // Replace the current page atomically. This makes manual "rescan" idempotent and
            // prevents outlines from stacking on top of themselves.
            for (int i = merged.size() - 1; i >= 0; i--) {
                LauncherScrollBus.IconBox b = merged.get(i);
                if (b.movesWithPages && b.pageIndex == settledPage) merged.remove(i);
            }
        }

        // The dock is page-independent. Any scan that sees dock icons refreshes the dock rather
        // than accumulating another copy.
        boolean freshHasDock = false;
        for (LauncherScrollBus.IconBox b : fresh) if (!b.movesWithPages) { freshHasDock = true; break; }
        if (freshHasDock) {
            for (int i = merged.size() - 1; i >= 0; i--) {
                if (!merged.get(i).movesWithPages) merged.remove(i);
            }
        }

        for (LauncherScrollBus.IconBox n : fresh) {
            int hit = findSnapshotMatch(merged, n);
            if (hit >= 0) merged.set(hit, n); else merged.add(n);
        }

        // Final geometry-level de-duplication protects against Samsung exposing both a clickable
        // wrapper and its clickable child for the same icon on successive scans.
        ArrayList<LauncherScrollBus.IconBox> clean = new ArrayList<>();
        for (LauncherScrollBus.IconBox n : merged) addOrReplaceFresh(clean, n);

        LauncherScrollBus.iconSnapshot = clean.toArray(new LauncherScrollBus.IconBox[0]);
        LauncherScrollBus.iconSnapshotVersion++;
    }

    private void addOrReplaceFresh(ArrayList<LauncherScrollBus.IconBox> boxes, LauncherScrollBus.IconBox n) {
        int hit = findSnapshotMatch(boxes, n);
        if (hit < 0) {
            boxes.add(n);
            return;
        }
        LauncherScrollBus.IconBox old = boxes.get(hit);
        float oldArea = Math.max(1f, old.width * old.height);
        float newArea = Math.max(1f, n.width * n.height);
        // Prefer the tighter rectangle when Samsung publishes a wrapper + child pair.
        if (newArea <= oldArea * 1.06f) boxes.set(hit, n);
    }

    private int findSnapshotMatch(ArrayList<LauncherScrollBus.IconBox> boxes, LauncherScrollBus.IconBox n) {
        String nl = normalize(n.label);
        for (int i = 0; i < boxes.size(); i++) {
            LauncherScrollBus.IconBox o = boxes.get(i);
            if (o.movesWithPages != n.movesWithPages) continue;
            if (o.pageIndex != n.pageIndex) continue;

            float dx = Math.abs(o.worldCenterX - n.worldCenterX);
            float dy = Math.abs(o.centerY - n.centerY);
            boolean sameLabel = normalize(o.label).equals(nl);
            boolean sameSlot = dx < Math.max(34f, Math.min(o.width, n.width) * 0.42f)
                    && dy < Math.max(34f, Math.min(o.height, n.height) * 0.34f);
            if ((sameLabel && dx < Math.max(96f, n.width * 0.78f)
                    && dy < Math.max(54f, n.height * 0.50f)) || sameSlot) {
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
