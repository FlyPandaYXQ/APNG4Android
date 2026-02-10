package com.github.penfeizhou.animation.decode;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.github.penfeizhou.animation.executor.FrameDecoderExecutor;
import com.github.penfeizhou.animation.io.Reader;
import com.github.penfeizhou.animation.io.Writer;
import com.github.penfeizhou.animation.loader.Loader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

public abstract class FrameSeqDecoder<R extends Reader, W extends Writer> {
    private static final String TAG = FrameSeqDecoder.class.getSimpleName();
    private final int taskId;
    // 修复1：加volatile保障多线程可见性
    private volatile float speed = 1.0f;

    private final Loader mLoader;
    private final Handler workerHandler;
    protected List<Frame<R, W>> frames = new ArrayList<>();
    protected int frameIndex = -1;
    protected static final double MB = 1024.0 * 1024.0;
    private int playCount;
    private Integer loopLimit = null;
    private final Set<RenderListener> renderListeners = new HashSet<>();
    private final AtomicBoolean paused = new AtomicBoolean(true);
    private static final Rect RECT_EMPTY = new Rect();
    private final Runnable renderTask = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) {
                Log.d(TAG, renderTask + ",run");
            }
            if (paused.get()) {
                return;
            }
            if (canStep()) {
                long start = System.currentTimeMillis();
                long delay = step();
                long cost = System.currentTimeMillis() - start;
                workerHandler.removeCallbacks(renderTask);
                // 修复2：延迟计算结合速度系数，避免耗时扣减后间隔异常
                long actualDelay = Math.max(0, delay - cost);
                workerHandler.postDelayed(this, actualDelay);
                for (RenderListener renderListener : renderListeners) {
                    if (frameBuffer != null) {
                        renderListener.onRender(frameBuffer);
                    }
                }
            } else {
                stop();
            }
        }
    };
    protected int sampleSize = 1;

    private final Set<Bitmap> cacheBitmaps = new HashSet<>();
    private final Object cacheBitmapsLock = new Object();

    protected Map<Bitmap, Canvas> cachedCanvas = new WeakHashMap<>();
    protected ByteBuffer frameBuffer;
    protected volatile Rect fullRect;
    private W mWriter = getWriter();
    private R mReader = null;
    public static final boolean DEBUG = false;
    private boolean finished = false;

    private enum State {
        IDLE,
        RUNNING,
        INITIALIZING,
        FINISHING,
    }

    private volatile State mState = State.IDLE;

    protected abstract W getWriter();

    protected abstract R getReader(Reader reader);

    protected Bitmap obtainBitmap(int width, int height) {
        synchronized (cacheBitmapsLock) {
            Bitmap ret = null;
            Iterator<Bitmap> iterator = cacheBitmaps.iterator();
            while (iterator.hasNext()) {
                int reuseSize = width * height * 4;
                ret = iterator.next();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    if (ret != null && ret.getAllocationByteCount() >= reuseSize) {
                        iterator.remove();
                        if ((ret.getWidth() != width || ret.getHeight() != height)) {
                            if (width > 0 && height > 0) {
                                ret.reconfigure(width, height, Bitmap.Config.ARGB_8888);
                            }
                        }
                        ret.eraseColor(0);
                        return ret;
                    }
                } else {
                    if (ret != null && ret.getByteCount() >= reuseSize) {
                        if (ret.getWidth() == width && ret.getHeight() == height) {
                            iterator.remove();
                            ret.eraseColor(0);
                        }
                        return ret;
                    }
                }
            }

            if (width <= 0 || height <= 0) {
                return null;
            }
            try {
                Bitmap.Config config = Bitmap.Config.ARGB_8888;
                ret = Bitmap.createBitmap(width, height, config);
            } catch (Exception e) {
                e.printStackTrace();
            } catch (OutOfMemoryError e) {
                e.printStackTrace();
            }
            return ret;
        }
    }

    // 修复3：完善setSpeed逻辑，补充参数合法性校验+非RUNNING状态缓存生效
    private static final float MIN_SPEED = 0.1f; // 最小播放速度
    private static final float MAX_SPEED = 100.0f; // 最大播放速度
    private static final String INVALID_SPEED_MSG = "速度系数必须是大于0的有效浮点数（如0.5~10.0）";
    private static final float SPEED_SCALE_FACTOR = 1.0f; // 速度缩放因子
    /**
     * 设置动画播放速度系数
     * @param speed 原始速度系数（范围建议0.5~10.0），会先缩放再限制上下限
     */
    public void setSpeed(float speed) {
        // 1. 打印入参日志（DEBUG级别，调试用）
        Log.d(TAG, "setSpeed: 原始输入速度 = " + speed);

        // 2. 严格校验：排除非正数、NaN、无穷大
        if (speed <= 0 || Float.isNaN(speed) || Float.isInfinite(speed)) {
            Log.e(TAG, "setSpeed: 速度校验失败，输入无效 | 输入值 = " + speed
                    + " | 校验条件：speed>0 且 非NaN 且 非无穷大");
            throw new IllegalArgumentException(INVALID_SPEED_MSG);
        }

        // 3. 速度缩放 + 上下限限制（确保最终速度在0.1~10.0之间）
        float finalSpeed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed / SPEED_SCALE_FACTOR));
        this.speed = finalSpeed;

        // 4. 打印最终生效速度（INFO级别，记录关键状态）
        Log.i(TAG, "setSpeed: 速度设置成功 | 原始输入 = " + speed
                + " | 缩放后 = " + (speed / SPEED_SCALE_FACTOR)
                + " | 最终生效速度 = " + finalSpeed);

        // 5. 仅运行中状态重启渲染任务，增加日志记录+空指针防护
        if (mState == State.RUNNING) {
            Log.d(TAG, "setSpeed: 动画处于RUNNING状态，重启渲染任务应用新速度");
            if (workerHandler != null && renderTask != null) {
                workerHandler.removeCallbacks(renderTask);
                workerHandler.post(renderTask);
            } else {
                Log.w(TAG, "setSpeed: 动画处于RUNNING状态，但workerHandler/renderTask为空，无法重启任务");
            }
        } else {
            Log.d(TAG, "setSpeed: 动画当前状态 = " + mState + "，仅缓存速度，启动时自动应用");
        }
    }

    public float getSpeed() {
        return this.speed;
    }

    protected void recycleBitmap(Bitmap bitmap) {
        synchronized (cacheBitmapsLock) {
            if (bitmap != null) {
                cacheBitmaps.add(bitmap);
            }
        }
    }

    public interface RenderListener {
        void onStart();

        void onRender(ByteBuffer byteBuffer);

        void onEnd();
    }

    public FrameSeqDecoder(Loader loader, @Nullable RenderListener renderListener) {
        this.mLoader = loader;
        if (renderListener != null) {
            this.renderListeners.add(renderListener);
        }
        this.taskId = FrameDecoderExecutor.getInstance().generateTaskId();
        this.workerHandler = new Handler(FrameDecoderExecutor.getInstance().getLooper(taskId));
    }

    public void addRenderListener(final RenderListener renderListener) {
        this.workerHandler.post(new Runnable() {
            @Override
            public void run() {
                renderListeners.add(renderListener);
            }
        });
    }

    public void removeRenderListener(final RenderListener renderListener) {
        this.workerHandler.post(new Runnable() {
            @Override
            public void run() {
                renderListeners.remove(renderListener);
            }
        });
    }

    public void stopIfNeeded() {
        this.workerHandler.post(new Runnable() {
            @Override
            public void run() {
                if (renderListeners.size() == 0) {
                    stop();
                }
            }
        });
    }

    public Rect getBounds() {
        if (fullRect == null) {
            if (mState == State.FINISHING) {
                Log.e(TAG, "In finishing,do not interrupt");
            }
            final Thread thread = Thread.currentThread();
            workerHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (fullRect == null) {
                            if (mReader == null) {
                                mReader = getReader(mLoader.obtain());
                            } else {
                                mReader.reset();
                            }
                            initCanvasBounds(read(mReader));
                        }
                    } catch (Exception | OutOfMemoryError e) {
                        e.printStackTrace();
                        fullRect = RECT_EMPTY;
                    } finally {
                        LockSupport.unpark(thread);
                    }
                }
            });
            LockSupport.park(thread);
        }
        return fullRect == null ? RECT_EMPTY : fullRect;
    }

    private void initCanvasBounds(Rect rect) {
        fullRect = rect;
        long bufferSize = ((long) rect.width() * rect.height() / ((long) sampleSize * sampleSize) + 1) * 4;

        try {
            frameBuffer = ByteBuffer.allocate((int) bufferSize);
            if (mWriter == null) {
                mWriter = getWriter();
            }
        } catch (OutOfMemoryError error) {
            Log.e(TAG, String.format(
                            "OutOfMemoryError in FrameSeqDecoder: Buffer needed: %.2fMB (%,d bytes)",
                            bufferSize / MB, bufferSize
                    )
            );
            frameBuffer = null;
            fullRect = RECT_EMPTY;
            throw error;
        }
    }

    public int getFrameCount() {
        return this.frames.size();
    }

    public int getFrameIndex() {
        return frameIndex;
    }

    protected abstract int getLoopCount();

    public void start() {
        if (fullRect == RECT_EMPTY) {
            return;
        }
        if (mState == State.RUNNING || mState == State.INITIALIZING) {
            Log.i(TAG, debugInfo() + " Already started");
            return;
        }
        if (mState == State.FINISHING) {
            Log.e(TAG, debugInfo() + " Processing,wait for finish at " + mState);
        }
        if (DEBUG) {
            Log.i(TAG, debugInfo() + "Set state to INITIALIZING");
        }
        mState = State.INITIALIZING;
        if (Looper.myLooper() == workerHandler.getLooper()) {
            innerStart();
        } else {
            workerHandler.post(new Runnable() {
                @Override
                public void run() {
                    innerStart();
                }
            });
        }
    }

    @WorkerThread
    private void innerStart() {
        paused.compareAndSet(true, false);

        final long start = System.currentTimeMillis();
        try {
            if (getFrameCount() == 0) {
                try {
                    if (mReader == null) {
                        mReader = getReader(mLoader.obtain());
                    } else {
                        mReader.reset();
                    }
                    initCanvasBounds(read(mReader));
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        } finally {
            Log.i(TAG, debugInfo() + " Set state to RUNNING,cost " + (System.currentTimeMillis() - start));
            mState = State.RUNNING;
        }
        if (getNumPlays() == 0 || !finished) {
            this.frameIndex = -1;
            workerHandler.removeCallbacks(renderTask);
            // 修复4：启动时直接运行任务，确保缓存的speed立即生效
            renderTask.run();
            for (RenderListener renderListener : renderListeners) {
                renderListener.onStart();
            }
        } else {
            Log.i(TAG, debugInfo() + " No need to started");
        }
    }

    @WorkerThread
    private void innerStop() {
        workerHandler.removeCallbacks(renderTask);
        frames.clear();
        synchronized (cacheBitmapsLock) {
            for (Bitmap bitmap : cacheBitmaps) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
            cacheBitmaps.clear();
        }
        if (frameBuffer != null) {
            frameBuffer = null;
        }
        cachedCanvas.clear();
        try {
            if (mReader != null) {
                mReader.close();
                mReader = null;
            }
            if (mWriter != null) {
                mWriter.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        release();
        if (DEBUG) {
            Log.i(TAG, debugInfo() + " release and Set state to IDLE");
        }
        mState = State.IDLE;
        for (RenderListener renderListener : renderListeners) {
            renderListener.onEnd();
        }
    }

    public void stop() {
        if (fullRect == RECT_EMPTY) {
            return;
        }
        if (mState == State.FINISHING || mState == State.IDLE) {
            Log.i(TAG, debugInfo() + "No need to stop");
            return;
        }
        if (mState == State.INITIALIZING) {
            Log.e(TAG, debugInfo() + "Processing,wait for finish at " + mState);
        }
        if (DEBUG) {
            Log.i(TAG, debugInfo() + " Set state to finishing");
        }
        mState = State.FINISHING;
        if (Looper.myLooper() == workerHandler.getLooper()) {
            innerStop();
        } else {
            workerHandler.post(new Runnable() {
                @Override
                public void run() {
                    innerStop();
                }
            });
        }
    }

    private String debugInfo() {
        if (DEBUG) {
            return String.format("thread is %s, decoder is %s,state is %s", Thread.currentThread(), FrameSeqDecoder.this, mState.toString());
        }
        return "";
    }

    protected abstract void release();

    public boolean isRunning() {
        return mState == State.RUNNING || mState == State.INITIALIZING;
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void setLoopLimit(int limit) {
        this.loopLimit = limit;
    }

    public void reset() {
        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                playCount = 0;
                frameIndex = -1;
                finished = false;
            }
        });
    }

    public void pause() {
        workerHandler.removeCallbacks(renderTask);
        paused.compareAndSet(false, true);
    }

    // 修复5：resume时重启任务，确保最新speed生效
    public void resume() {
        if (paused.compareAndSet(true, false)) {
            workerHandler.removeCallbacks(renderTask);
            workerHandler.post(renderTask);
        }
    }

    public int getSampleSize() {
        return sampleSize;
    }

    public int setDesiredSize(int width, int height) {
        final int sample = getDesiredSample(width, height);
        if (sample != getSampleSize()) {
            final boolean tempRunning = isRunning();
            workerHandler.removeCallbacks(renderTask);
            workerHandler.post(new Runnable() {
                @Override
                public void run() {
                    innerStop();
                    try {
                        sampleSize = sample;
                        initCanvasBounds(read(getReader(mLoader.obtain())));
                        if (tempRunning) {
                            innerStart();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
        return sample;
    }

    protected int getDesiredSample(int desiredWidth, int desiredHeight) {
        if (desiredWidth == 0 || desiredHeight == 0) {
            return 1;
        }
        int radio = Math.min(getBounds().width() / desiredWidth, getBounds().height() / desiredHeight);
        int sample = 1;
        while ((sample * 2) <= radio) {
            sample *= 2;
        }
        return sample;
    }

    protected abstract Rect read(R reader) throws IOException;

    private int getNumPlays() {
        return this.loopLimit != null ? this.loopLimit : this.getLoopCount();
    }

    private boolean canStep() {
        if (!isRunning()) {
            return false;
        }
        if (getFrameCount() == 0) {
            return false;
        }
        if (getNumPlays() <= 0) {
            return true;
        }
        if (this.playCount < getNumPlays() - 1) {
            return true;
        } else if (this.playCount == getNumPlays() - 1 && this.frameIndex < this.getFrameCount() - 1) {
            return true;
        }
        finished = true;
        return false;
    }

    @WorkerThread
    private long step() {
        frameIndex++;
        if (frameIndex >= getFrameCount()) {
            playCount++;
            if (loopLimit == null || playCount < loopLimit) {
                frameIndex = 0;
            } else {
                finished = true;
                return 0;
            }
        }

        Frame<R, W> frame = frames.get(frameIndex);
        renderFrame(frame);

        // 修复6：兜底frameDuration为0的情况，避免除以speed后异常
        long originalDelay = frame.frameDuration <= 0 ? 100 : frame.frameDuration; // 默认100ms兜底
        long adjustedDelay = (long) (originalDelay / speed);
        return Math.max(10, adjustedDelay); // 最低10ms，防止ANR
    }

    protected abstract void renderFrame(Frame<R, W> frame);

    public Frame<R, W> getFrame(int index) {
        if (index < 0 || index >= frames.size()) {
            return null;
        }
        return frames.get(index);
    }

    public Bitmap getFrameBitmap(int index) throws IOException {
        if (mState != State.IDLE) {
            Log.e(TAG, debugInfo() + ",stop first");
            return null;
        }
        mState = State.RUNNING;
        paused.compareAndSet(true, false);
        if (frames.size() == 0) {
            if (mReader == null) {
                mReader = getReader(mLoader.obtain());
            } else {
                mReader.reset();
            }
            initCanvasBounds(read(mReader));
        }
        if (index < 0) {
            index += this.frames.size();
        }
        if (index < 0) {
            index = 0;
        }
        frameIndex = -1;
        while (frameIndex < index) {
            if (canStep()) {
                step();
            } else {
                break;
            }
        }
        frameBuffer.rewind();
        Bitmap bitmap = Bitmap.createBitmap(getBounds().width() / getSampleSize(), getBounds().height() / getSampleSize(), Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(frameBuffer);
        innerStop();
        return bitmap;
    }

    public int getMemorySize() {
        synchronized (cacheBitmapsLock) {
            int size = 0;
            for (Bitmap bitmap : cacheBitmaps) {
                if (bitmap.isRecycled()) {
                    continue;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    size += bitmap.getAllocationByteCount();
                } else {
                    size += bitmap.getByteCount();
                }
            }
            if (frameBuffer != null) {
                size += frameBuffer.capacity();
            }
            return size;
        }
    }

}