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
import java.util.Locale;

/**
 * v0.20 dual real-bounds tracker.
 *
 * We keep two real One UI icon nodes alive at once: the left/bottom icon and the right/top icon.
 * For a swipe toward the next page, the right/top icon stays on screen longer; for a swipe toward
 * the previous page, the left/bottom icon stays on screen longer.  If one node publishes a newer
 * bound than the other, the tracker takes the leading real position in the current direction
 * instead of averaging in a stale sample.  This reduces phase lag without inventing launcher
 * physics.
 */
public class LauncherAccessibilityService extends AccessibilityService {
    private static final String LAUNCHER = "com.sec.android.app.launcher";
    private static final long POLL_MS = 8L;
    private static final long IDLE_MS = 120L;
    private static final long MIN_RESCAN_MS = 55L;
    private static final long MIN_PREFETCH_SCAN_MS = 48L;
    private static final long HANDOFF_RESCAN_MS = 64L;
    private static final int MAX_TREE_NODES = 1800;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean running = false;
    private long seenHomeEpoch = -1L;
    private long seenRescanGeneration = -1L;
    private long lastFullScanMs = 0L;

    private final AnchorTrack leftBottom = new AnchorTrack("LEFT-BOTTOM");
    private final AnchorTrack rightTop = new AnchorTrack("RIGHT-TOP");
    private float lastUnifiedPosition = Float.NaN;
    private long lastUnifiedMoveMs = 0L;

    private long pollWindowStart = 0L;
    private int pollWindowCount = 0;
    private long changeWindowStart = 0L;
    private int changeWindowCount = 0;

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
                boolean sampled = sampleAnchors(now);
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
        LauncherScrollBus.trackerState = "DUAL BOUNDS READY";
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
            // Accessibility events are free wake-ups: sample both cached nodes immediately.
            sampleAnchors(SystemClock.uptimeMillis());
        }
    }

    private boolean sampleAnchors(long now) {
        int direction = directionHint();

        // Sample the anchor that should survive this direction first, shaving a little IPC latency
        // from the coordinate that is most likely to become authoritative for this frame.
        AnchorSample lb, rt;
        if (direction > 0) {
            rt = sampleOne(rightTop, now);
            lb = sampleOne(leftBottom, now);
        } else {
            lb = sampleOne(leftBottom, now);
            rt = sampleOne(rightTop, now);
        }

        if (!lb.valid && !rt.valid) {
            LauncherScrollBus.anchorLeftBottomValid = false;
            LauncherScrollBus.anchorRightTopValid = false;
            return false;
        }

        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        float pos;
        AnchorTrack active;

        if (lb.valid && rt.valid) {
            LauncherScrollBus.anchorDisagreementPx = Math.abs(lb.position - rt.position) * w;

            if (direction > 0) {
                // Icons move left / page position grows. The larger real position is the fresher
                // sample when one node is one Samsung accessibility tick behind the other.
                if (rt.position >= lb.position) { pos = rt.position; active = rightTop; }
                else { pos = lb.position; active = leftBottom; }
            } else if (direction < 0) {
                // Icons move right / page position shrinks. Take the smaller real position.
                if (lb.position <= rt.position) { pos = lb.position; active = leftBottom; }
                else { pos = rt.position; active = rightTop; }
            } else if (lb.changed != rt.changed) {
                if (lb.changed) { pos = lb.position; active = leftBottom; }
                else { pos = rt.position; active = rightTop; }
            } else if (Math.abs(lb.position - rt.position) <= 0.012f) {
                // At rest both should agree. Averaging only very close samples suppresses integer
                // pixel quantisation without adding visible phase lag.
                pos = (lb.position + rt.position) * 0.5f;
                active = lb.changedMs >= rt.changedMs ? leftBottom : rightTop;
            } else if (lb.changedMs >= rt.changedMs) {
                pos = lb.position; active = leftBottom;
            } else {
                pos = rt.position; active = rightTop;
            }
        } else if (lb.valid) {
            pos = lb.position;
            active = leftBottom;
            LauncherScrollBus.anchorDisagreementPx = 0f;
        } else {
            pos = rt.position;
            active = rightTop;
            LauncherScrollBus.anchorDisagreementPx = 0f;
        }

        boolean unifiedChanged = Float.isNaN(lastUnifiedPosition)
                || Math.abs(pos - lastUnifiedPosition) > 0.00012f;
        if (unifiedChanged) {
            LauncherScrollBus.boundsChanges++;
            lastUnifiedMoveMs = now;

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
        } else if (now - lastUnifiedMoveMs > 90L) {
            LauncherScrollBus.velocityPagesPerSec = 0f;
        }
        lastUnifiedPosition = pos;

        LauncherScrollBus.positionValid = true;
        LauncherScrollBus.position = pos;
        LauncherScrollBus.positionUptimeMs = now;
        LauncherScrollBus.positionSequence++;
        LauncherScrollBus.boundsSamples++;
        LauncherScrollBus.activeAnchorRole = active.role;
        publishUnifiedAnchorMetadata(active);
        LauncherScrollBus.trackerState = unifiedChanged ? "DUAL BOUNDS CHANGED" : "DUAL BOUNDS POLL";

        pollWindowCount++;
        if (pollWindowStart == 0L) pollWindowStart = now;
        long pollElapsed = now - pollWindowStart;
        if (pollElapsed >= 1000L) {
            LauncherScrollBus.trackerPollHz = pollWindowCount * 1000f / pollElapsed;
            pollWindowCount = 0;
            pollWindowStart = now;
        }

        int pages = clampInt(prefs.getInt(Prefs.KEY_PAGE_COUNT, 4), 2, 9);
        int rounded = clampInt(Math.round(pos), 0, pages - 1);
        if (now - lastUnifiedMoveMs > 110L && Math.abs(pos - rounded) < 0.035f) {
            publishPage(rounded, now);
        }

        // Directional handoff: reacquire while the source-page anchor is still visible, not after
        // it disappears. Right/top survives longest going to next page; left/bottom survives
        // longest going to previous page.
        if (now - lastFullScanMs >= HANDOFF_RESCAN_MS) {
            if (direction > 0 && rt.valid && rt.rect.centerX() < w * 0.28f) {
                fullScanAndAcquire(now);
            } else if (direction < 0 && lb.valid && lb.rect.centerX() > w * 0.72f) {
                fullScanAndAcquire(now);
            } else if (!lb.valid || !rt.valid) {
                fullScanAndAcquire(now);
            }
        }

        return true;
    }

    private AnchorSample sampleOne(AnchorTrack track, long now) {
        if (track.node == null) {
            track.valid = false;
            publishTrackDiagnostics(track);
            return AnchorSample.invalid(track);
        }

        try {
            if (!track.node.refresh()) {
                track.invalidate();
                publishTrackDiagnostics(track);
                return AnchorSample.invalid(track);
            }
        } catch (Throwable t) {
            track.invalidate();
            publishTrackDiagnostics(track);
            return AnchorSample.invalid(track);
        }

        Rect r = new Rect();
        track.node.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) {
            track.invalidate();
            publishTrackDiagnostics(track);
            return AnchorSample.invalid(track);
        }

        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int cx = r.centerX();
        int cy = r.centerY();
        float pos = (track.worldX - cx) / w;
        boolean changed = cx != track.lastX || cy != track.lastY;

        if (changed) {
            track.changedMs = now;
            track.changes++;
        }
        track.lastX = cx;
        track.lastY = cy;
        track.position = pos;
        track.rect.set(r);
        track.valid = true;
        publishTrackDiagnostics(track);
        return new AnchorSample(track, true, changed, pos, track.changedMs, new Rect(r));
    }

    private int directionHint() {
        if (LauncherScrollBus.touchActive && Math.abs(LauncherScrollBus.touchVelocityX) > 24f) {
            // Finger left => icons left => page position increases.
            return LauncherScrollBus.touchVelocityX < 0f ? 1 : -1;
        }
        if (Math.abs(LauncherScrollBus.velocityPagesPerSec) > 0.012f) {
            return LauncherScrollBus.velocityPagesPerSec > 0f ? 1 : -1;
        }
        return 0;
    }

    private void publishTrackDiagnostics(AnchorTrack t) {
        if ("LEFT-BOTTOM".equals(t.role)) {
            LauncherScrollBus.anchorLeftBottomValid = t.valid;
            LauncherScrollBus.anchorLeftBottomLabel = t.label;
            LauncherScrollBus.anchorLeftBottomX = t.lastX == Integer.MIN_VALUE ? 0 : t.lastX;
            LauncherScrollBus.anchorLeftBottomY = t.lastY == Integer.MIN_VALUE ? 0 : t.lastY;
            LauncherScrollBus.anchorLeftBottomPosition = t.position;
            LauncherScrollBus.anchorLeftBottomChangedMs = t.changedMs;
        } else {
            LauncherScrollBus.anchorRightTopValid = t.valid;
            LauncherScrollBus.anchorRightTopLabel = t.label;
            LauncherScrollBus.anchorRightTopX = t.lastX == Integer.MIN_VALUE ? 0 : t.lastX;
            LauncherScrollBus.anchorRightTopY = t.lastY == Integer.MIN_VALUE ? 0 : t.lastY;
            LauncherScrollBus.anchorRightTopPosition = t.position;
            LauncherScrollBus.anchorRightTopChangedMs = t.changedMs;
        }
    }

    private void publishUnifiedAnchorMetadata(AnchorTrack active) {
        LauncherScrollBus.anchorLabel = active.label;
        LauncherScrollBus.anchorLeft = active.rect.left;
        LauncherScrollBus.anchorTop = active.rect.top;
        LauncherScrollBus.anchorRight = active.rect.right;
        LauncherScrollBus.anchorBottom = active.rect.bottom;
        LauncherScrollBus.anchorCenterX = active.rect.centerX();
        LauncherScrollBus.anchorCenterY = active.rect.centerY();
        LauncherScrollBus.anchorWorldX = active.worldX;
    }

    private void fullScanAndAcquire(long now) {
        lastFullScanMs = now;
        LauncherScrollBus.layoutScans++;
        LauncherScrollBus.lastLayoutScanMs = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            LauncherScrollBus.homeActive = false;
            clearAnchors("HOME ROOT MISSING");
            return;
        }
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !LAUNCHER.contentEquals(pkg)) {
            LauncherScrollBus.homeActive = false;
            clearAnchors("HOME NOT ACTIVE");
            return;
        }
        LauncherScrollBus.homeActive = true;

        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        ArrayList<Candidate> icons = collectIconCandidates(root, w, h);
        if (icons.isEmpty()) {
            clearAnchors("NO ICON NODES");
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

        Candidate lb = chooseLeftBottom(icons, w, h);
        Candidate rt = chooseRightTop(icons, w, h, lb);
        if (lb == null && rt == null) {
            clearAnchors("NO MOVING ICON");
            return;
        }
        acquirePair(lb, rt, basisPos, now, w);
    }

    /** Sparse incoming-page cache refresh. No anchor replacement here unless the directional
     * handoff condition in sampleAnchors() explicitly requests a full scan. */
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


    private Candidate chooseLeftBottom(ArrayList<Candidate> icons, int w, int h) {
        int maxY = Integer.MIN_VALUE;
        for (Candidate c : icons) {
            int cx = c.rect.centerX(), cy = c.rect.centerY();
            if (cy > h * 0.84f) continue; // dock
            if (cx < -w * 0.18f || cx > w * 1.18f) continue;
            maxY = Math.max(maxY, cy);
        }
        if (maxY == Integer.MIN_VALUE) return null;

        Candidate best = null;
        int rowTolerance = Math.max(24, Math.round(h * 0.075f));
        for (Candidate c : icons) {
            int cx = c.rect.centerX(), cy = c.rect.centerY();
            if (cy > h * 0.84f) continue;
            if (cx < -w * 0.18f || cx > w * 1.18f) continue;
            if (cy < maxY - rowTolerance) continue;
            if (best == null || cx < best.rect.centerX()
                    || (cx == best.rect.centerX() && cy > best.rect.centerY())) {
                best = c;
            }
        }
        return best;
    }

    private Candidate chooseRightTop(ArrayList<Candidate> icons, int w, int h, Candidate avoid) {
        int minY = Integer.MAX_VALUE;
        for (Candidate c : icons) {
            int cx = c.rect.centerX(), cy = c.rect.centerY();
            if (cy > h * 0.84f) continue;
            if (cx < -w * 0.18f || cx > w * 1.18f) continue;
            minY = Math.min(minY, cy);
        }
        if (minY == Integer.MAX_VALUE) return null;

        Candidate best = null;
        int rowTolerance = Math.max(24, Math.round(h * 0.075f));
        for (Candidate c : icons) {
            if (c == avoid && icons.size() > 1) continue;
            int cx = c.rect.centerX(), cy = c.rect.centerY();
            if (cy > h * 0.84f) continue;
            if (cx < -w * 0.18f || cx > w * 1.18f) continue;
            if (cy > minY + rowTolerance) continue;
            if (best == null || cx > best.rect.centerX()
                    || (cx == best.rect.centerX() && cy < best.rect.centerY())) {
                best = c;
            }
        }
        if (best == null && avoid != null) best = avoid;
        return best;
    }

    private void acquirePair(Candidate lb, Candidate rt, float basisPos, long now, int w) {
        leftBottom.assign(lb, basisPos, w, now);
        rightTop.assign(rt, basisPos, w, now);
        lastUnifiedPosition = Float.NaN;
        lastUnifiedMoveMs = now;
        LauncherScrollBus.anchorReacquires++;
        LauncherScrollBus.trackerState = "DUAL ANCHORS ACQUIRED";
        sampleAnchors(now);
    }
    /**
     * Store icon bounds in stable world coordinates.
     *
     * Snapshots remain PAGE-SCOPED. v0.18 blindly merged every
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

    private void clearAnchors(String reason) {
        leftBottom.clear();
        rightTop.clear();
        lastUnifiedPosition = Float.NaN;
        lastUnifiedMoveMs = 0L;
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


    private final class AnchorTrack {
        final String role;
        AccessibilityNodeInfo node;
        String label = "";
        float worldX = 0f;
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        long changedMs = 0L;
        long changes = 0L;
        float position = 0f;
        boolean valid = false;
        final Rect rect = new Rect();

        AnchorTrack(String role) { this.role = role; }

        void assign(Candidate c, float basisPos, int w, long now) {
            if (c == null) {
                clear();
                return;
            }
            node = c.node;
            label = c.label;
            worldX = c.rect.centerX() + basisPos * w;
            lastX = Integer.MIN_VALUE;
            lastY = Integer.MIN_VALUE;
            changedMs = now;
            position = basisPos;
            rect.set(c.rect);
            valid = true;
            publishTrackDiagnostics(this);
        }

        void invalidate() {
            node = null;
            valid = false;
        }

        void clear() {
            node = null;
            label = "";
            worldX = 0f;
            lastX = Integer.MIN_VALUE;
            lastY = Integer.MIN_VALUE;
            changedMs = 0L;
            position = 0f;
            rect.setEmpty();
            valid = false;
            publishTrackDiagnostics(this);
        }
    }

    private static final class AnchorSample {
        final AnchorTrack track;
        final boolean valid;
        final boolean changed;
        final float position;
        final long changedMs;
        final Rect rect;

        AnchorSample(AnchorTrack track, boolean valid, boolean changed, float position, long changedMs, Rect rect) {
            this.track = track;
            this.valid = valid;
            this.changed = changed;
            this.position = position;
            this.changedMs = changedMs;
            this.rect = rect;
        }

        static AnchorSample invalid(AnchorTrack track) {
            return new AnchorSample(track, false, false, 0f, 0L, new Rect());
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
