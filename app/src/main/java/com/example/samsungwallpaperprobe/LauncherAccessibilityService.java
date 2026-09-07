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
 * v0.21 — immutable settled-page cache.
 *
 * No layout snapshot is ever modified during a swipe. Each cached moving icon stores:
 *   pageIndex + resting local X/Y.
 * A live AccessibilityNodeInfo only supplies current X. Therefore every known icon lives in the
 * same absolute coordinate system and anchors can be handed off without rebasing.
 */
public class LauncherAccessibilityService extends AccessibilityService {
    private static final String LAUNCHER = "com.sec.android.app.launcher";
    private static final long POLL_MS = 8L;
    private static final long IDLE_MS = 120L;
    private static final long SETTLE_MS = 135L;
    private static final long REACQUIRE_MIN_MS = 58L;
    private static final int MAX_TREE_NODES = 1800;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean running = false;
    private long seenHomeEpoch = -1L;
    private long seenRescanGeneration = -1L;
    private long lastAcquireScanMs = 0L;
    private long lastPositionChangeMs = 0L;
    private int lastSettledPage = -1;

    private final AnchorTrack leftBottom = new AnchorTrack("LEFT-BOTTOM");
    private final AnchorTrack rightTop = new AnchorTrack("RIGHT-TOP");

    private float lastUnifiedPosition = Float.NaN;
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
            boolean manualRescan = seenRescanGeneration != LauncherScrollBus.rescanGeneration;
            if (homeEntry) {
                seenHomeEpoch = LauncherScrollBus.homeEpoch;
                // First scan is legal because opening Home starts at a settled launcher page.
                captureSettledPageAndAcquire(now, resolveInitialPage());
            } else if (manualRescan) {
                seenRescanGeneration = LauncherScrollBus.rescanGeneration;
                int p = settledPageCandidate(now);
                if (p >= 0) captureSettledPageAndAcquire(now, p);
            } else {
                boolean ok = sampleAnchors(now);
                if (!ok && now - lastAcquireScanMs >= REACQUIRE_MIN_MS) {
                    // Search visible nodes only to attach them to ALREADY CACHED icons.
                    // This scan never changes layout ownership/coordinates.
                    reacquireKnownAnchors(now);
                }
                maybeCaptureAfterSettle(now);
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
        LauncherScrollBus.trackerState = "SETTLED CACHE READY";
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
        if (LauncherScrollBus.wallpaperVisible) sampleAnchors(SystemClock.uptimeMillis());
    }

    private int resolveInitialPage() {
        int pages = pages();
        int saved = clampInt(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0), 0, pages - 1);
        if (LauncherScrollBus.authoritativePage >= 0) {
            return clampInt(LauncherScrollBus.authoritativePage, 0, pages - 1);
        }
        return saved;
    }

    private boolean sampleAnchors(long now) {
        AnchorSample lb = sampleOne(leftBottom, now);
        AnchorSample rt = sampleOne(rightTop, now);

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

            // Both formulas should be identical. Prefer whichever actually changed most recently.
            // Average only when the two measurements already agree within ~2 px.
            if (Math.abs(lb.position - rt.position) * w <= 2.2f) {
                pos = (lb.position + rt.position) * 0.5f;
                active = lb.changedMs >= rt.changedMs ? leftBottom : rightTop;
            } else if (lb.changedMs > rt.changedMs) {
                pos = lb.position;
                active = leftBottom;
            } else if (rt.changedMs > lb.changedMs) {
                pos = rt.position;
                active = rightTop;
            } else {
                // Same timestamp but different quantised bounds: choose the value closer to the
                // previous continuous position, avoiding directional heuristics.
                float prev = Float.isNaN(lastUnifiedPosition) ? lb.position : lastUnifiedPosition;
                if (Math.abs(lb.position - prev) <= Math.abs(rt.position - prev)) {
                    pos = lb.position; active = leftBottom;
                } else {
                    pos = rt.position; active = rightTop;
                }
            }
        } else if (lb.valid) {
            pos = lb.position; active = leftBottom;
            LauncherScrollBus.anchorDisagreementPx = 0f;
        } else {
            pos = rt.position; active = rightTop;
            LauncherScrollBus.anchorDisagreementPx = 0f;
        }

        boolean changed = Float.isNaN(lastUnifiedPosition) || Math.abs(pos - lastUnifiedPosition) > 0.00012f;
        if (changed) {
            LauncherScrollBus.previousChangedPosition = LauncherScrollBus.lastChangedPosition;
            LauncherScrollBus.previousChangedUptimeMs = LauncherScrollBus.lastChangedUptimeMs;
            LauncherScrollBus.lastChangedPosition = pos;
            LauncherScrollBus.lastChangedUptimeMs = now;
            LauncherScrollBus.boundsChanges++;
            lastPositionChangeMs = now;

            if (LauncherScrollBus.previousChangedUptimeMs > 0L && now > LauncherScrollBus.previousChangedUptimeMs) {
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
        } else if (now - lastPositionChangeMs > 95L) {
            LauncherScrollBus.velocityPagesPerSec = 0f;
        }
        lastUnifiedPosition = pos;

        LauncherScrollBus.positionValid = true;
        LauncherScrollBus.position = pos;
        LauncherScrollBus.positionUptimeMs = now;
        LauncherScrollBus.positionSequence++;
        LauncherScrollBus.boundsSamples++;
        LauncherScrollBus.activeAnchorRole = active.role;
        publishUnifiedAnchor(active);
        LauncherScrollBus.trackerState = changed ? "REAL BOUNDS CHANGED" : "REAL BOUNDS POLL";

        pollWindowCount++;
        if (pollWindowStart == 0L) pollWindowStart = now;
        long pe = now - pollWindowStart;
        if (pe >= 1000L) {
            LauncherScrollBus.trackerPollHz = pollWindowCount * 1000f / pe;
            pollWindowCount = 0;
            pollWindowStart = now;
        }

        // Reacquire BEFORE both source nodes disappear, but only against immutable known snapshots.
        boolean edge = false;
        if (lb.valid && (lb.rect.right < w * 0.16f || lb.rect.left > w * 0.84f)) edge = true;
        if (rt.valid && (rt.rect.right < w * 0.16f || rt.rect.left > w * 0.84f)) edge = true;
        if (edge && now - lastAcquireScanMs >= REACQUIRE_MIN_MS) reacquireKnownAnchors(now);
        return true;
    }

    private AnchorSample sampleOne(AnchorTrack t, long now) {
        if (t.node == null || t.pageIndex < 0) {
            t.invalidate();
            publishTrack(t);
            return AnchorSample.invalid();
        }
        try {
            if (!t.node.refresh()) {
                t.invalidate();
                publishTrack(t);
                return AnchorSample.invalid();
            }
        } catch (Throwable e) {
            t.invalidate();
            publishTrack(t);
            return AnchorSample.invalid();
        }

        Rect r = new Rect();
        t.node.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) {
            t.invalidate();
            publishTrack(t);
            return AnchorSample.invalid();
        }

        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int cx = r.centerX();
        float pos = t.pageIndex + (t.restCenterX - cx) / w;
        boolean changed = cx != t.lastX || r.centerY() != t.lastY;
        if (changed) {
            t.changedMs = now;
            t.changes++;
        }
        t.lastX = cx;
        t.lastY = r.centerY();
        t.position = pos;
        t.rect.set(r);
        t.valid = true;
        publishTrack(t);
        return new AnchorSample(true, changed, pos, t.changedMs, new Rect(r));
    }

    /**
     * Search the live tree for nodes that correspond to ALREADY CACHED icons. No cache mutation.
     * Each match can independently estimate absolute position. We choose matches near our current
     * position and then pick left-bottom / right-top among those, enabling page-to-page handoff.
     */
    private void reacquireKnownAnchors(long now) {
        lastAcquireScanMs = now;
        AccessibilityNodeInfo root = launcherRoot();
        if (root == null) return;

        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        ArrayList<Candidate> visible = collectIconCandidates(root, w, h);
        LauncherScrollBus.IconBox[] cache = LauncherScrollBus.iconSnapshot;
        if (visible.isEmpty() || cache.length == 0) return;

        float reference = LauncherScrollBus.positionValid ? LauncherScrollBus.position
                : (LauncherScrollBus.authoritativePage >= 0 ? LauncherScrollBus.authoritativePage : resolveInitialPage());

        ArrayList<KnownMatch> matches = new ArrayList<>();
        for (Candidate c : visible) {
            if (c.rect.centerY() > h * 0.84f) continue; // dock cannot define page offset
            String nl = normalize(c.label);
            for (LauncherScrollBus.IconBox b : cache) {
                if (!b.movesWithPages || b.pageIndex < 0) continue;
                if (!normalize(b.label).equals(nl)) continue;
                if (Math.abs(b.centerY - c.rect.centerY()) > Math.max(72f, b.height * 0.65f)) continue;
                float p = b.pageIndex + (b.localCenterX - c.rect.centerX()) / w;
                if (Math.abs(p - reference) > 0.72f) continue;
                matches.add(new KnownMatch(c, b, p));
            }
        }
        if (matches.isEmpty()) return;

        KnownMatch lb = chooseLeftBottomMatch(matches, h);
        KnownMatch rt = chooseRightTopMatch(matches, h, lb);
        if (lb == null && rt == null) return;

        assignMatch(leftBottom, lb, now);
        assignMatch(rightTop, rt != null ? rt : lb, now);
        LauncherScrollBus.anchorReacquires++;
        LauncherScrollBus.trackerState = "KNOWN ANCHOR HANDOFF";
        lastUnifiedPosition = Float.NaN;
        sampleAnchors(now);
    }

    private KnownMatch chooseLeftBottomMatch(ArrayList<KnownMatch> ms, int h) {
        KnownMatch best = null;
        for (KnownMatch m : ms) {
            if (best == null || m.c.rect.centerY() > best.c.rect.centerY()
                    || (m.c.rect.centerY() == best.c.rect.centerY()
                    && m.c.rect.centerX() < best.c.rect.centerX())) best = m;
        }
        return best;
    }

    private KnownMatch chooseRightTopMatch(ArrayList<KnownMatch> ms, int h, KnownMatch avoid) {
        KnownMatch best = null;
        for (KnownMatch m : ms) {
            if (m == avoid && ms.size() > 1) continue;
            if (best == null || m.c.rect.centerY() < best.c.rect.centerY()
                    || (m.c.rect.centerY() == best.c.rect.centerY()
                    && m.c.rect.centerX() > best.c.rect.centerX())) best = m;
        }
        return best == null ? avoid : best;
    }

    private void assignMatch(AnchorTrack t, KnownMatch m, long now) {
        if (m == null) { t.clear(); return; }
        t.node = m.c.node;
        t.label = m.b.label;
        t.pageIndex = m.b.pageIndex;
        t.restCenterX = m.b.localCenterX;
        t.restCenterY = m.b.centerY;
        t.lastX = Integer.MIN_VALUE;
        t.lastY = Integer.MIN_VALUE;
        t.changedMs = now;
        t.position = m.position;
        t.rect.set(m.c.rect);
        t.valid = true;
        publishTrack(t);
    }

    private void maybeCaptureAfterSettle(long now) {
        int p = settledPageCandidate(now);
        if (p < 0) return;
        if (p == lastSettledPage && now - LauncherScrollBus.lastLayoutScanMs < 700L) return;
        captureSettledPageAndAcquire(now, p);
    }

    private int settledPageCandidate(long now) {
        if (!LauncherScrollBus.positionValid) return -1;
        float pos = LauncherScrollBus.position;
        int p = Math.round(pos);
        if (p < 0 || p >= pages()) return -1;
        if (Math.abs(pos - p) > 0.025f) return -1;
        if (now - lastPositionChangeMs < SETTLE_MS) return -1;
        return p;
    }

    /** Capture is the ONLY function allowed to mutate the page-local layout cache. */
    private void captureSettledPageAndAcquire(long now, int page) {
        AccessibilityNodeInfo root = launcherRoot();
        if (root == null) return;
        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        ArrayList<Candidate> icons = collectIconCandidates(root, w, h);
        if (icons.isEmpty()) return;

        publishSettledSnapshot(icons, page, w, h);
        lastSettledPage = page;
        publishPage(page, now);

        Candidate lb = chooseLeftBottom(icons, w, h);
        Candidate rt = chooseRightTop(icons, w, h, lb);
        if (lb != null) assignFresh(leftBottom, lb, page, now);
        else leftBottom.clear();
        if (rt != null) assignFresh(rightTop, rt, page, now);
        else if (lb != null) assignFresh(rightTop, lb, page, now);
        else rightTop.clear();

        LauncherScrollBus.anchorReacquires++;
        LauncherScrollBus.trackerState = "SETTLED PAGE " + (page + 1) + " CAPTURED";
        lastUnifiedPosition = Float.NaN;
        sampleAnchors(now);
    }

    private void assignFresh(AnchorTrack t, Candidate c, int page, long now) {
        t.node = c.node;
        t.label = c.label;
        t.pageIndex = page;
        t.restCenterX = c.rect.centerX();
        t.restCenterY = c.rect.centerY();
        t.lastX = Integer.MIN_VALUE;
        t.lastY = Integer.MIN_VALUE;
        t.changedMs = now;
        t.position = page;
        t.rect.set(c.rect);
        t.valid = true;
        publishTrack(t);
    }

    private void publishSettledSnapshot(ArrayList<Candidate> icons, int page, int w, int h) {
        LauncherScrollBus.layoutScans++;
        LauncherScrollBus.lastLayoutScanMs = SystemClock.uptimeMillis();

        ArrayList<LauncherScrollBus.IconBox> merged = new ArrayList<>();
        for (LauncherScrollBus.IconBox b : LauncherScrollBus.iconSnapshot) {
            // Replace this moving page atomically. Dock is also refreshed by the current settled scan.
            if (b.movesWithPages && b.pageIndex == page) continue;
            if (!b.movesWithPages) continue;
            merged.add(b);
        }

        ArrayList<LauncherScrollBus.IconBox> freshPage = new ArrayList<>();
        ArrayList<LauncherScrollBus.IconBox> freshDock = new ArrayList<>();
        for (Candidate c : icons) {
            boolean moves = c.rect.centerY() <= h * 0.84f;
            LauncherScrollBus.IconBox b = new LauncherScrollBus.IconBox(
                    c.rect.centerX(), c.rect.centerY(), c.rect.width(), c.rect.height(),
                    c.label, moves, moves ? page : -1);
            addOrReplace(moves ? freshPage : freshDock, b);
        }
        for (LauncherScrollBus.IconBox b : freshPage) merged.add(b);
        for (LauncherScrollBus.IconBox b : freshDock) merged.add(b);

        LauncherScrollBus.iconSnapshot = merged.toArray(new LauncherScrollBus.IconBox[0]);
        LauncherScrollBus.iconSnapshotVersion++;

        boolean[] seen = new boolean[pages()];
        for (LauncherScrollBus.IconBox b : LauncherScrollBus.iconSnapshot) {
            if (b.movesWithPages && b.pageIndex >= 0 && b.pageIndex < seen.length) seen[b.pageIndex] = true;
        }
        int count = 0;
        for (boolean v : seen) if (v) count++;
        LauncherScrollBus.cachedPageCount = count;
    }

    private void addOrReplace(ArrayList<LauncherScrollBus.IconBox> list, LauncherScrollBus.IconBox n) {
        String nl = normalize(n.label);
        for (int i = 0; i < list.size(); i++) {
            LauncherScrollBus.IconBox o = list.get(i);
            boolean sameLabel = normalize(o.label).equals(nl);
            float dx = Math.abs(o.localCenterX - n.localCenterX);
            float dy = Math.abs(o.centerY - n.centerY);
            boolean sameSlot = dx < Math.max(28f, Math.min(o.width, n.width) * 0.38f)
                    && dy < Math.max(28f, Math.min(o.height, n.height) * 0.32f);
            if (sameLabel || sameSlot) {
                float oa = Math.max(1f, o.width * o.height);
                float na = Math.max(1f, n.width * n.height);
                if (na <= oa * 1.08f) list.set(i, n);
                return;
            }
        }
        list.add(n);
    }

    private AccessibilityNodeInfo launcherRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            LauncherScrollBus.homeActive = false;
            return null;
        }
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !LAUNCHER.contentEquals(pkg)) {
            LauncherScrollBus.homeActive = false;
            return null;
        }
        LauncherScrollBus.homeActive = true;
        return root;
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
            if (looksLikeIconNode(n, r, label, w, h)) raw.add(new Candidate(n, r, label));
            int cc = n.getChildCount();
            for (int i = 0; i < cc && seen + q.size() < MAX_TREE_NODES; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.addLast(child);
            }
        }

        ArrayList<Candidate> out = new ArrayList<>();
        for (Candidate c : raw) {
            int hit = -1;
            for (int i = 0; i < out.size(); i++) {
                Candidate o = out.get(i);
                float overlap = overlapRatio(o.rect, c.rect);
                boolean sameLabel = normalize(o.label).equals(normalize(c.label));
                boolean sameGeometry = Math.abs(o.rect.centerX() - c.rect.centerX()) <= 6
                        && Math.abs(o.rect.centerY() - c.rect.centerY()) <= 6 && overlap > 0.84f;
                if ((sameLabel && overlap > 0.72f) || sameGeometry) { hit = i; break; }
            }
            if (hit < 0) out.add(c);
            else {
                Candidate o = out.get(hit);
                int oa = Math.max(1, o.rect.width() * o.rect.height());
                int na = Math.max(1, c.rect.width() * c.rect.height());
                if ((c.node.isClickable() && !o.node.isClickable()) || na < oa) out.set(hit, c);
            }
        }
        return out;
    }

    private boolean looksLikeIconNode(AccessibilityNodeInfo n, Rect r, String label, int w, int h) {
        if (label.isEmpty() || !n.isClickable() || !n.isVisibleToUser()) return false;
        if (r.right < -w * 0.18f || r.left > w * 1.18f) return false;
        int rw = r.width(), rh = r.height();
        if (rw < w * 0.055f || rw > w * 0.42f) return false;
        if (rh < h * 0.035f || rh > h * 0.22f) return false;
        int cy = r.centerY();
        return cy >= h * 0.055f && cy <= h * 0.985f;
    }

    private Candidate chooseLeftBottom(ArrayList<Candidate> icons, int w, int h) {
        Candidate best = null;
        for (Candidate c : icons) {
            int cx = c.rect.centerX(), cy = c.rect.centerY();
            if (cy > h * 0.84f) continue;
            if (cx < -w * 0.10f || cx > w * 1.10f) continue;
            if (best == null || cy > best.rect.centerY()
                    || (cy == best.rect.centerY() && cx < best.rect.centerX())) best = c;
        }
        return best;
    }

    private Candidate chooseRightTop(ArrayList<Candidate> icons, int w, int h, Candidate avoid) {
        Candidate best = null;
        for (Candidate c : icons) {
            if (c == avoid && icons.size() > 1) continue;
            int cx = c.rect.centerX(), cy = c.rect.centerY();
            if (cy > h * 0.84f) continue;
            if (cx < -w * 0.10f || cx > w * 1.10f) continue;
            if (best == null || cy < best.rect.centerY()
                    || (cy == best.rect.centerY() && cx > best.rect.centerX())) best = c;
        }
        return best == null ? avoid : best;
    }

    private void publishTrack(AnchorTrack t) {
        if ("LEFT-BOTTOM".equals(t.role)) {
            LauncherScrollBus.anchorLeftBottomValid = t.valid;
            LauncherScrollBus.anchorLeftBottomLabel = t.label;
            LauncherScrollBus.anchorLeftBottomX = t.lastX == Integer.MIN_VALUE ? 0 : t.lastX;
            LauncherScrollBus.anchorLeftBottomPosition = t.position;
        } else {
            LauncherScrollBus.anchorRightTopValid = t.valid;
            LauncherScrollBus.anchorRightTopLabel = t.label;
            LauncherScrollBus.anchorRightTopX = t.lastX == Integer.MIN_VALUE ? 0 : t.lastX;
            LauncherScrollBus.anchorRightTopPosition = t.position;
        }
    }

    private void publishUnifiedAnchor(AnchorTrack t) {
        LauncherScrollBus.anchorLabel = t.label;
        LauncherScrollBus.anchorLeft = t.rect.left;
        LauncherScrollBus.anchorTop = t.rect.top;
        LauncherScrollBus.anchorRight = t.rect.right;
        LauncherScrollBus.anchorBottom = t.rect.bottom;
    }

    private void publishPage(int page, long now) {
        LauncherScrollBus.authoritativePage = page;
        LauncherScrollBus.authoritativePageUptimeMs = now;
        prefs.edit().putInt(Prefs.KEY_SAVED_PAGE, page).apply();
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

    private int pages() {
        return clampInt(prefs.getInt(Prefs.KEY_PAGE_COUNT, 4), 2, 9);
    }

    private int clampInt(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

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

    private static final class KnownMatch {
        final Candidate c;
        final LauncherScrollBus.IconBox b;
        final float position;
        KnownMatch(Candidate c, LauncherScrollBus.IconBox b, float position) {
            this.c = c; this.b = b; this.position = position;
        }
    }

    private static final class AnchorSample {
        final boolean valid, changed;
        final float position;
        final long changedMs;
        final Rect rect;
        AnchorSample(boolean valid, boolean changed, float position, long changedMs, Rect rect) {
            this.valid = valid; this.changed = changed; this.position = position;
            this.changedMs = changedMs; this.rect = rect;
        }
        static AnchorSample invalid() { return new AnchorSample(false, false, 0f, 0L, new Rect()); }
    }

    private final class AnchorTrack {
        final String role;
        AccessibilityNodeInfo node;
        String label = "";
        int pageIndex = -1;
        float restCenterX = 0f;
        float restCenterY = 0f;
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        long changedMs = 0L;
        long changes = 0L;
        float position = 0f;
        boolean valid = false;
        final Rect rect = new Rect();
        AnchorTrack(String role) { this.role = role; }
        void invalidate() { node = null; valid = false; }
        void clear() {
            node = null; label = ""; pageIndex = -1; restCenterX = 0f; restCenterY = 0f;
            lastX = Integer.MIN_VALUE; lastY = Integer.MIN_VALUE; changedMs = 0L;
            position = 0f; rect.setEmpty(); valid = false; publishTrack(this);
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
