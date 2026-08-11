package org.kivy.android;

import android.media.AudioRecord;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Drains an AudioRecord from a Java thread so Python never sits in the read.
 *
 * AudioRecord.read() blocks until a block is available. Doing that from the
 * Python capture loop means the drain only happens when the interpreter gets
 * around to it: measured jitter on that path was sd 6-8 ms with spikes to 80 ms,
 * against a buffer budget of about 30 ms. Anything that holds the GIL - garbage
 * collection, another thread, the effect pipeline itself - shows up as audio
 * arriving late or, once the ring overflows, not at all.
 *
 * Here the read runs on its own thread at URGENT_AUDIO priority and hands
 * finished blocks to a bounded queue. Python then only ever does a non-blocking
 * take, so a slow frame costs a dropped block rather than stalling the capture.
 *
 * The drop policy is deliberately "oldest first". For a visualiser, audio that
 * is already late is worthless - keeping it would just push every later block
 * further behind. Dropping the stale block keeps what Python sees close to now,
 * and getDropped() makes the cost visible instead of silent.
 */
public class AudioPump implements Runnable {

    private static final String TAG = "AudioPump";

    private final AudioRecord recorder;
    private final int blockBytes;
    private final ArrayBlockingQueue<byte[]> queue;

    private volatile boolean running = false;
    private volatile long dropped = 0;
    private volatile long read = 0;
    private Thread thread = null;

    /**
     * @param recorder   already-configured, not yet necessarily recording
     * @param blockBytes bytes per block; must match what the caller reads
     * @param capacity   blocks to hold before dropping. Two or three is right:
     *                   this is latency, not safety.
     */
    public AudioPump(AudioRecord recorder, int blockBytes, int capacity) {
        this.recorder = recorder;
        this.blockBytes = blockBytes;
        this.queue = new ArrayBlockingQueue<byte[]>(Math.max(1, capacity));
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "LedFxAudioPump");
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        queue.clear();
    }

    /**
     * Next block, or null if none arrived within timeoutMs.
     *
     * Returning null rather than blocking indefinitely matters: the Python
     * caller has to stay responsive to shutdown even if the recorder wedges.
     */
    public byte[] take(int timeoutMs) {
        try {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Blocks currently waiting. Persistently &gt;1 means Python is behind. */
    public int getQueued() {
        return queue.size();
    }

    /** Blocks discarded because the consumer could not keep up. */
    public long getDropped() {
        return dropped;
    }

    /** Blocks successfully read from the recorder. */
    public long getRead() {
        return read;
    }

    @Override
    public void run() {
        // The whole point of this class. Without it the reader competes with
        // ordinary background threads and inherits their scheduling latency.
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Exception e) {
            Log.w(TAG, "Could not raise thread priority: " + e.getMessage());
        }

        while (running) {
            byte[] block = new byte[blockBytes];
            int got = 0;
            // read() may return a short count; keep filling so a block is
            // always exactly one period and never straddles two.
            while (running && got < blockBytes) {
                int n = recorder.read(block, got, blockBytes - got);
                if (n <= 0) {
                    // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT etc. Back off
                    // rather than spin; the Python side will restart the device.
                    Log.w(TAG, "AudioRecord.read returned " + n);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    break;
                }
                got += n;
            }
            if (got != blockBytes) continue;

            read++;
            if (!queue.offer(block)) {
                queue.poll();          // drop the oldest, keep latency low
                queue.offer(block);
                dropped++;
                if (dropped % 100 == 1) {
                    Log.w(TAG, "consumer behind, dropped " + dropped + " blocks");
                }
            }
        }
    }
}
