package com.example.samsungwallpaperprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * v0.17: bounds-only engine.
 *
 * All old virtual-offset / touch-slop / predicted-fling / custom-spring code is removed.
 * The wallpaper renders one continuous position published by LauncherAccessibilityService,
 * which is derived from the real getBoundsInScreen() X coordinate of one launcher icon.
 */
public class ProbeWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new BoundsGpuEngine();
    }

    private final class BoundsGpuEngine extends Engine {
        private final Object stateLock = new Object();
        private SharedPreferences prefs;
        private RenderThread renderThread;

        private volatile boolean visible = false;
        private volatile int surfaceW = 1;
        private volatile int surfaceH = 1;

        private int pageCount = 4;
        private int lastConfigGeneration = -1;
        private boolean showDebug = false;
        private float displayPosition = 0f;
        private float motionEnergy = 0f;
        private String source = "BOUNDS WAIT";

        // Grid cache. Geometry is intentionally unchanged from the v0.8 branch.
        private int gridCols = Prefs.DEFAULT_COLS;
        private int gridRows = Prefs.DEFAULT_ROWS;
        private float gridX0 = Prefs.DEFAULT_X0;
        private float gridY0 = Prefs.DEFAULT_Y0;
        private float cellWidth = Prefs.DEFAULT_CELL_WIDTH;
        private float cellHeight = Prefs.DEFAULT_CELL_HEIGHT;
        private float gapX = Prefs.DEFAULT_GAP_X;
        private float gapY = Prefs.DEFAULT_GAP_Y;
        private float glassOpacity = Prefs.DEFAULT_GLASS_OPACITY;
        private ArrayList<Cell>[] cellsByPage;
        private volatile boolean gridDirty = true;

        private final class Cell {
            final int row, col;
            Cell(int row, int col) { this.row = row; this.col = col; }
        }

        private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
                (sharedPreferences, key) -> {
                    if (key == null) return;
                    if (key.startsWith("cell_")
                            || Prefs.KEY_GRID_COLS.equals(key)
                            || Prefs.KEY_GRID_ROWS.equals(key)
                            || Prefs.KEY_GRID_X0.equals(key)
                            || Prefs.KEY_GRID_Y0.equals(key)
                            || Prefs.KEY_CELL_WIDTH.equals(key)
                            || Prefs.KEY_CELL_HEIGHT.equals(key)
                            || Prefs.KEY_GAP_X.equals(key)
                            || Prefs.KEY_GAP_Y.equals(key)
                            || Prefs.KEY_GLASS_OPACITY.equals(key)
                            || Prefs.KEY_PAGE_COUNT.equals(key)) {
                        gridDirty = true;
                    }
                    if (Prefs.KEY_SHOW_DEBUG.equals(key)) {
                        showDebug = sharedPreferences.getBoolean(Prefs.KEY_SHOW_DEBUG, showDebug);
                    }
                };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            // Bounds tracking is authoritative now. We deliberately do not request touch events.
            setTouchEventsEnabled(false);
            setOffsetNotificationsEnabled(false);
            prefs = getSharedPreferences(Prefs.PREFS, Context.MODE_PRIVATE);
            Prefs.ensureV06GridDefaults(prefs,
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            prefs.registerOnSharedPreferenceChangeListener(prefsListener);
            loadPersistentState(true);
        }

        private void loadPersistentState(boolean forcePosition) {
            synchronized (stateLock) {
                pageCount = clampInt(prefs.getInt(Prefs.KEY_PAGE_COUNT, 4), 2, 9);
                showDebug = prefs.getBoolean(Prefs.KEY_SHOW_DEBUG, false);
                int generation = prefs.getInt(Prefs.KEY_CONFIG_GENERATION, 0);
                if (forcePosition || generation != lastConfigGeneration) {
                    int saved = clampInt(prefs.getInt(Prefs.KEY_SAVED_PAGE, 0), 0, pageCount - 1);
                    if (!LauncherScrollBus.positionValid) displayPosition = saved;
                }
                lastConfigGeneration = generation;
                gridDirty = true;
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            if (renderThread == null) {
                renderThread = new RenderThread(holder);
                renderThread.start();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceW = Math.max(1, width);
            surfaceH = Math.max(1, height);
            gridDirty = true;
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (isVisible) {
                LauncherScrollBus.onWallpaperVisible();
                int generation = prefs.getInt(Prefs.KEY_CONFIG_GENERATION, lastConfigGeneration);
                if (generation != lastConfigGeneration) loadPersistentState(false);
                if (renderThread != null) renderThread.wakeUp();
            } else {
                LauncherScrollBus.onWallpaperHidden();
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            LauncherScrollBus.onWallpaperHidden();
            if (renderThread != null) {
                renderThread.requestStop();
                try { renderThread.join(1500L); } catch (InterruptedException ignored) {}
                renderThread = null;
            }
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            LauncherScrollBus.onWallpaperHidden();
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
            if (renderThread != null) {
                renderThread.requestStop();
                renderThread = null;
            }
            super.onDestroy();
        }

        private void consumeBoundsPosition(float dt) {
            synchronized (stateLock) {
                long now = SystemClock.uptimeMillis();
                if (LauncherScrollBus.serviceConnected
                        && LauncherScrollBus.positionValid
                        && now - LauncherScrollBus.positionUptimeMs < 100L) {
                    // Direct real coordinate. No homemade easing and no touch prediction.
                    displayPosition = LauncherScrollBus.position;
                    source = "BOUNDS REAL";
                } else if (LauncherScrollBus.authoritativePage >= 0) {
                    // If the anchor is being reacquired, hold the last known settled page instead
                    // of inventing a transition.
                    if (!LauncherScrollBus.positionValid) {
                        displayPosition = LauncherScrollBus.authoritativePage;
                    }
                    source = LauncherScrollBus.serviceConnected ? "BOUNDS HOLD" : "SERVICE OFF";
                } else {
                    source = LauncherScrollBus.serviceConnected ? "NO ANCHOR" : "SERVICE OFF";
                }

                float desiredEnergy = clamp(Math.abs(LauncherScrollBus.velocityPagesPerSec) * 0.34f, 0f, 1f);
                float response = desiredEnergy > motionEnergy ? 16f : 5f;
                motionEnergy += (desiredEnergy - motionEnergy) * clamp(dt * response, 0f, 1f);
            }
        }

        @SuppressWarnings("unchecked")
        private void rebuildGridCache() {
            int w = Math.max(1, surfaceW), h = Math.max(1, surfaceH);
            Prefs.ensureV06GridDefaults(prefs, w, h);
            synchronized (stateLock) {
                pageCount = clampInt(prefs.getInt(Prefs.KEY_PAGE_COUNT, pageCount), 2, 9);
                gridCols = clampInt(prefs.getInt(Prefs.KEY_GRID_COLS, Prefs.DEFAULT_COLS), 2, 7);
                gridRows = clampInt(prefs.getInt(Prefs.KEY_GRID_ROWS, Prefs.DEFAULT_ROWS), 2, 9);
                gridX0 = clamp(prefs.getFloat(Prefs.KEY_GRID_X0, Prefs.DEFAULT_X0), -0.20f, 1.20f);
                gridY0 = clamp(prefs.getFloat(Prefs.KEY_GRID_Y0, Prefs.DEFAULT_Y0), -0.20f, 1.20f);
                cellWidth = clamp(prefs.getFloat(Prefs.KEY_CELL_WIDTH, Prefs.DEFAULT_CELL_WIDTH), 0.03f, 0.40f);
                cellHeight = clamp(prefs.getFloat(Prefs.KEY_CELL_HEIGHT, Prefs.DEFAULT_CELL_HEIGHT), 0.015f, 0.25f);
                gapX = clamp(prefs.getFloat(Prefs.KEY_GAP_X, Prefs.DEFAULT_GAP_X), 0f, 0.40f);
                gapY = clamp(prefs.getFloat(Prefs.KEY_GAP_Y, Prefs.DEFAULT_GAP_Y), 0f, 0.30f);
                glassOpacity = clamp(prefs.getFloat(Prefs.KEY_GLASS_OPACITY, Prefs.DEFAULT_GLASS_OPACITY), 0.05f, 0.95f);

                cellsByPage = (ArrayList<Cell>[]) new ArrayList[pageCount];
                for (int p = 0; p < pageCount; p++) {
                    ArrayList<Cell> cells = new ArrayList<>();
                    for (int r = 0; r < gridRows; r++) {
                        for (int c = 0; c < gridCols; c++) {
                            String pkg = prefs.getString(Prefs.cellKey(p, r, c), null);
                            if (pkg != null && !pkg.isEmpty()) cells.add(new Cell(r, c));
                        }
                    }
                    cellsByPage[p] = cells;
                }
                gridDirty = false;
            }
        }

        private final class RenderThread extends Thread {
            private static final long TARGET_FRAME_NS = 8_333_333L; // 120 Hz

            private final SurfaceHolder holder;
            private volatile boolean running = true;
            private final Object waitLock = new Object();

            private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
            private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
            private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

            private int bgProgram, glassProgram, texProgram;
            private FloatBuffer quad;
            private int debugTexture = 0;
            private Bitmap debugBitmap;
            private Canvas debugCanvas;
            private Paint debugPaint;
            private long lastDebugUploadMs = 0L;

            private long fpsStartNs = 0L;
            private int fpsFrames = 0;
            private float measuredFps = 0f;

            RenderThread(SurfaceHolder holder) {
                super("IridescentGL120");
                this.holder = holder;
            }

            void requestStop() { running = false; wakeUp(); }
            void wakeUp() { synchronized (waitLock) { waitLock.notifyAll(); } }

            @Override
            public void run() {
                if (!initEgl()) return;
                initGl();
                long lastNs = System.nanoTime();
                long nextFrameNs = lastNs;

                while (running) {
                    if (!visible) {
                        synchronized (waitLock) {
                            try { waitLock.wait(100L); } catch (InterruptedException ignored) {}
                        }
                        lastNs = System.nanoTime();
                        nextFrameNs = lastNs;
                        continue;
                    }

                    long nowNs = System.nanoTime();
                    float dt = clamp((nowNs - lastNs) / 1_000_000_000f, 0.001f, 0.05f);
                    lastNs = nowNs;

                    int generation = prefs.getInt(Prefs.KEY_CONFIG_GENERATION, lastConfigGeneration);
                    if (generation != lastConfigGeneration) loadPersistentState(false);
                    if (gridDirty) rebuildGridCache();

                    consumeBoundsPosition(dt);
                    render(nowNs);
                    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) break;
                    updateFps(nowNs);

                    // eglSwapBuffers normally blocks to vsync. The cap below only prevents a
                    // non-blocking driver from running the wallpaper faster than 120 Hz.
                    nextFrameNs += TARGET_FRAME_NS;
                    long sleepNs = nextFrameNs - System.nanoTime();
                    if (sleepNs > 300_000L) {
                        LockSupport.parkNanos(sleepNs);
                    } else if (sleepNs < -40_000_000L) {
                        nextFrameNs = System.nanoTime();
                    }
                }
                releaseGl();
            }

            private boolean initEgl() {
                EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API);
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false;
                int[] version = new int[2];
                if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false;

                int[] attrib = {
                        EGL14.EGL_RENDERABLE_TYPE, 4,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_NONE
                };
                EGLConfig[] configs = new EGLConfig[1];
                int[] num = new int[1];
                if (!EGL14.eglChooseConfig(eglDisplay, attrib, 0, configs, 0, 1, num, 0) || num[0] == 0) return false;

                int[] ctxAttrib = {0x3098, 2, EGL14.EGL_NONE};
                eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0);
                if (eglContext == EGL14.EGL_NO_CONTEXT) return false;

                int[] surfAttrib = {EGL14.EGL_NONE};
                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], holder.getSurface(), surfAttrib, 0);
                if (eglSurface == EGL14.EGL_NO_SURFACE) return false;
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false;
                EGL14.eglSwapInterval(eglDisplay, 1);
                return true;
            }

            private void initGl() {
                float[] verts = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
                quad = ByteBuffer.allocateDirect(verts.length * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                quad.put(verts).position(0);

                bgProgram = buildProgram(VS_BG, FS_BG);
                glassProgram = buildProgram(VS_GLASS, FS_GLASS);
                texProgram = buildProgram(VS_TEX, FS_TEX);

                int[] tex = new int[1];
                GLES20.glGenTextures(1, tex, 0);
                debugTexture = tex[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, debugTexture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                debugBitmap = Bitmap.createBitmap(900, 410, Bitmap.Config.ARGB_8888);
                debugCanvas = new Canvas(debugBitmap);
                debugPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            }

            private void render(long nowNs) {
                int w = Math.max(1, surfaceW), h = Math.max(1, surfaceH);
                GLES20.glViewport(0, 0, w, h);
                GLES20.glClearColor(0.01f, 0.01f, 0.03f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                float pos, energy, opacity, x0, y0, cw, ch, gx, gy;
                int pages;
                ArrayList<Cell>[] pageCells;
                boolean debug;
                synchronized (stateLock) {
                    pos = displayPosition;
                    energy = motionEnergy;
                    opacity = glassOpacity;
                    x0 = gridX0; y0 = gridY0; cw = cellWidth; ch = cellHeight; gx = gapX; gy = gapY;
                    pages = pageCount;
                    pageCells = cellsByPage;
                    debug = showDebug;
                }

                drawBackground(w, h, nowNs / 1_000_000_000f, pos, energy);
                if (pageCells != null) drawGlass(w, h, pos, pages, pageCells, x0, y0, cw, ch, gx, gy, opacity);
                if (debug && LauncherScrollBus.positionValid
                        && SystemClock.uptimeMillis() - LauncherScrollBus.positionUptimeMs < 120L) {
                    drawBoundsMarker(w, h);
                }
                if (debug) drawDebug(w, h);
            }

            private void drawBackground(int w, int h, float time, float pos, float energy) {
                GLES20.glUseProgram(bgProgram);
                bindQuad(bgProgram, "aPos");
                GLES20.glUniform2f(GLES20.glGetUniformLocation(bgProgram, "uRes"), w, h);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(bgProgram, "uTime"), time);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(bgProgram, "uPos"), pos);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(bgProgram, "uEnergy"), energy);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }

            private void drawGlass(int w, int h, float pos, int pages, ArrayList<Cell>[] pageCells,
                                   float x0, float y0, float cw, float ch, float gx, float gy, float opacity) {
                GLES20.glUseProgram(glassProgram);
                bindQuad(glassProgram, "aPos");
                int uScreen = GLES20.glGetUniformLocation(glassProgram, "uScreen");
                int uCenter = GLES20.glGetUniformLocation(glassProgram, "uCenter");
                int uSize = GLES20.glGetUniformLocation(glassProgram, "uSize");
                int uOpacity = GLES20.glGetUniformLocation(glassProgram, "uOpacity");
                GLES20.glUniform2f(uScreen, w, h);
                GLES20.glUniform2f(uSize, cw * w, ch * h);
                GLES20.glUniform1f(uOpacity, opacity);

                // All configured pages are cheap enough at 4x6 and guarantee continuity.
                for (int page = 0; page < pages; page++) {
                    if (pageCells[page] == null || pageCells[page].isEmpty()) continue;
                    float pageShift = (page - pos) * w;
                    for (Cell cell : pageCells[page]) {
                        float cx = (x0 + cell.col * (cw + gx)) * w + pageShift;
                        float cy = (y0 + cell.row * (ch + gy)) * h;
                        if (cx < -w * 0.35f || cx > w * 1.35f || cy < -h * 0.15f || cy > h * 1.15f) continue;
                        GLES20.glUniform2f(uCenter, cx, cy);
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    }
                }
            }

            private void drawBoundsMarker(int w, int h) {
                int left = LauncherScrollBus.anchorLeft;
                int top = LauncherScrollBus.anchorTop;
                int right = LauncherScrollBus.anchorRight;
                int bottom = LauncherScrollBus.anchorBottom;
                float bw = Math.max(10f, right - left);
                float bh = Math.max(10f, bottom - top);
                float cx = (left + right) * 0.5f;
                float cy = (top + bottom) * 0.5f;

                GLES20.glUseProgram(glassProgram);
                bindQuad(glassProgram, "aPos");
                GLES20.glUniform2f(GLES20.glGetUniformLocation(glassProgram, "uScreen"), w, h);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(glassProgram, "uCenter"), cx, cy);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(glassProgram, "uSize"), bw, bh);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(glassProgram, "uOpacity"), 0.95f);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }

            private void drawDebug(int w, int h) {
                long now = SystemClock.uptimeMillis();
                if (now - lastDebugUploadMs > 140L) {
                    lastDebugUploadMs = now;
                    updateDebugBitmap();
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, debugTexture);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, debugBitmap, 0);
                }
                GLES20.glUseProgram(texProgram);
                bindQuad(texProgram, "aPos");
                GLES20.glUniform2f(GLES20.glGetUniformLocation(texProgram, "uScreen"), w, h);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(texProgram, "uCenter"), w * 0.50f, h * 0.165f);
                GLES20.glUniform2f(GLES20.glGetUniformLocation(texProgram, "uSize"), w * 0.94f, h * 0.31f);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, debugTexture);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(texProgram, "uTex"), 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }

            private void updateDebugBitmap() {
                debugBitmap.eraseColor(Color.TRANSPARENT);
                debugPaint.setStyle(Paint.Style.FILL);
                debugPaint.setColor(Color.argb(220, 0, 0, 0));
                debugCanvas.drawRoundRect(new RectF(8, 8, 892, 402), 38, 38, debugPaint);
                debugPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                debugPaint.setTextSize(54f);
                debugPaint.setColor(Color.WHITE);
                debugCanvas.drawText("Bounds Engine v0.17", 38, 68, debugPaint);
                debugPaint.setTypeface(android.graphics.Typeface.DEFAULT);
                debugPaint.setTextSize(31f);

                long age = Math.max(0L, SystemClock.uptimeMillis() - LauncherScrollBus.positionUptimeMs);
                String l1, l2, l3, l4, l5, l6, l7;
                synchronized (stateLock) {
                    l1 = String.format(Locale.US, "FPS %.1f/120  TRACK %.1fHz  %s", measuredFps, LauncherScrollBus.trackerHz, source);
                    l2 = String.format(Locale.US, "POS %.4f  PAGE %d/%d  v=%.3f", displayPosition,
                            LauncherScrollBus.authoritativePage < 0 ? 0 : LauncherScrollBus.authoritativePage + 1,
                            pageCount, LauncherScrollBus.velocityPagesPerSec);
                    l3 = String.format(Locale.US, "ANCHOR %s  p%d r%d c%d", trim(LauncherScrollBus.anchorLabel, 28),
                            LauncherScrollBus.anchorPage + 1, LauncherScrollBus.anchorRow + 1, LauncherScrollBus.anchorCol + 1);
                    l4 = String.format(Locale.US, "X %d  rest %.1f  dx %.1f  age %dms",
                            LauncherScrollBus.anchorCenterX, LauncherScrollBus.anchorRestX,
                            LauncherScrollBus.anchorCenterX - LauncherScrollBus.anchorRestX, age);
                    l5 = String.format(Locale.US, "snapshot %d/%d  scans %d  reacq %d",
                            LauncherScrollBus.visibleSlotsInSnapshot, LauncherScrollBus.configuredSlots,
                            LauncherScrollBus.layoutScans, LauncherScrollBus.anchorReacquires);
                    l6 = String.format(Locale.US, "bias %.1f, %.1f  samples %d  changes %d",
                            LauncherScrollBus.layoutBiasX, LauncherScrollBus.layoutBiasY,
                            LauncherScrollBus.boundsSamples, LauncherScrollBus.boundsChanges);
                    l7 = trim("STATE " + LauncherScrollBus.trackerState, 70);
                }

                debugPaint.setColor(Color.rgb(145, 255, 180)); debugCanvas.drawText(l1, 38, 112, debugPaint);
                debugPaint.setColor(Color.rgb(185, 220, 255)); debugCanvas.drawText(l2, 38, 156, debugPaint);
                debugPaint.setColor(Color.rgb(255, 235, 125)); debugCanvas.drawText(l3, 38, 200, debugPaint);
                debugPaint.setColor(Color.WHITE); debugCanvas.drawText(l4, 38, 244, debugPaint);
                debugCanvas.drawText(l5, 38, 288, debugPaint);
                debugCanvas.drawText(l6, 38, 332, debugPaint);
                debugPaint.setColor(Color.rgb(255, 226, 150)); debugCanvas.drawText(l7, 38, 376, debugPaint);
            }

            private void bindQuad(int program, String attr) {
                int loc = GLES20.glGetAttribLocation(program, attr);
                quad.position(0);
                GLES20.glEnableVertexAttribArray(loc);
                GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad);
            }

            private int buildProgram(String vs, String fs) {
                int v = compile(GLES20.GL_VERTEX_SHADER, vs);
                int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
                int p = GLES20.glCreateProgram();
                GLES20.glAttachShader(p, v);
                GLES20.glAttachShader(p, f);
                GLES20.glLinkProgram(p);
                int[] ok = new int[1];
                GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
                if (ok[0] == 0) throw new RuntimeException("GL link: " + GLES20.glGetProgramInfoLog(p));
                GLES20.glDeleteShader(v);
                GLES20.glDeleteShader(f);
                return p;
            }

            private int compile(int type, String src) {
                int s = GLES20.glCreateShader(type);
                GLES20.glShaderSource(s, src);
                GLES20.glCompileShader(s);
                int[] ok = new int[1];
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
                if (ok[0] == 0) throw new RuntimeException("GL shader: " + GLES20.glGetShaderInfoLog(s));
                return s;
            }

            private void updateFps(long nowNs) {
                if (fpsStartNs == 0L) fpsStartNs = nowNs;
                fpsFrames++;
                long elapsed = nowNs - fpsStartNs;
                if (elapsed >= 1_000_000_000L) {
                    measuredFps = fpsFrames * 1_000_000_000f / elapsed;
                    fpsFrames = 0;
                    fpsStartNs = nowNs;
                }
            }

            private void releaseGl() {
                if (debugBitmap != null) debugBitmap.recycle();
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                    EGL14.eglTerminate(eglDisplay);
                }
            }
        }

        private String trim(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max);
        }

        private int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private static final String VS_BG =
                "attribute vec2 aPos; varying vec2 vUv; void main(){ vUv=aPos*0.5+0.5; gl_Position=vec4(aPos,0.0,1.0); }";

        private static final String FS_BG =
                "precision mediump float; varying vec2 vUv; uniform vec2 uRes; uniform float uTime; uniform float uPos; uniform float uEnergy;" +
                "float g(vec2 p, vec2 c, float r){ float d=length(p-c); return 1.0-smoothstep(0.0,r,d); }" +
                "void main(){" +
                " vec2 p=vUv; float asp=uRes.x/uRes.y; vec2 q=vec2((p.x-0.5)*asp,p.y-0.5);" +
                " vec3 col=mix(vec3(0.008,0.014,0.04),vec3(0.035,0.01,0.06),p.y);" +
                " float ph=uPos*0.62; float t=uTime;" +
                " vec2 c1=vec2((-0.24+0.20*sin(ph+t*0.08))*asp,-0.20+0.06*cos(t*0.17+ph));" +
                " vec2 c2=vec2(( 0.27+0.16*cos(ph*1.11-t*0.06))*asp,-0.06+0.08*sin(t*0.14-ph));" +
                " vec2 c3=vec2((-0.15+0.21*cos(ph*0.91+1.7))*asp, 0.27+0.04*sin(t*0.23));" +
                " vec2 c4=vec2(( 0.32-0.18*sin(ph*0.77+0.8))*asp, 0.33-0.04*cos(t*0.19));" +
                " float split=uEnergy*0.055*asp; c1.x-=split; c2.x+=split; c3.x+=split*0.7; c4.x-=split*0.45;" +
                " col+=vec3(0.00,0.72,1.00)*g(q,c1,0.72);" +
                " col+=vec3(0.32,0.05,0.95)*g(q,c2,0.64);" +
                " col+=vec3(0.95,0.00,0.62)*g(q,c3,0.58);" +
                " col+=vec3(1.00,0.28,0.02)*g(q,c4,0.48);" +
                " float band=1.0-smoothstep(0.02+uEnergy*0.02,0.12+uEnergy*0.05,abs(fract((p.x+p.y*0.42)+uPos*0.10+t*0.012)-0.50));" +
                " col+=band*vec3(0.16,0.18,0.22);" +
                " float vig=smoothstep(0.34,0.78,length(q)); col*=mix(1.0,0.45,vig);" +
                " gl_FragColor=vec4(col,1.0); }";

        private static final String VS_GLASS =
                "attribute vec2 aPos; varying vec2 vLocal; uniform vec2 uScreen; uniform vec2 uCenter; uniform vec2 uSize;" +
                "void main(){ vLocal=aPos; vec2 px=uCenter+aPos*uSize*0.5; vec2 clip=vec2(px.x/uScreen.x*2.0-1.0,1.0-px.y/uScreen.y*2.0); gl_Position=vec4(clip,0.0,1.0); }";

        private static final String FS_GLASS =
                "precision mediump float; varying vec2 vLocal; uniform float uOpacity;" +
                "float sdRoundBox(vec2 p, vec2 b, float r){ vec2 q=abs(p)-b+r; return min(max(q.x,q.y),0.0)+length(max(q,0.0))-r; }" +
                "void main(){ float d=sdRoundBox(vLocal,vec2(1.0),0.32); float inside=1.0-smoothstep(-0.02,0.02,d);" +
                " float edge=1.0-smoothstep(0.00,0.085,abs(d)); float inner=1.0-smoothstep(0.10,0.20,abs(d+0.10));" +
                " float shine=smoothstep(-0.35,0.75,-vLocal.y+vLocal.x*0.18)*0.16;" +
                " vec3 col=vec3(0.92,0.95,1.0)+shine; float a=inside*(0.045+uOpacity*0.10)+edge*(0.30+uOpacity*0.42)+inner*0.05;" +
                " gl_FragColor=vec4(col,clamp(a,0.0,0.82)*inside); }";

        private static final String VS_TEX =
                "attribute vec2 aPos; varying vec2 vUv; uniform vec2 uScreen; uniform vec2 uCenter; uniform vec2 uSize;" +
                "void main(){ vUv=aPos*0.5+0.5; vec2 px=uCenter+aPos*uSize*0.5; vec2 clip=vec2(px.x/uScreen.x*2.0-1.0,1.0-px.y/uScreen.y*2.0); gl_Position=vec4(clip,0.0,1.0); }";

        private static final String FS_TEX =
                "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; void main(){ gl_FragColor=texture2D(uTex,vUv); }";
    }
}
