package com.lanmirror.app;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Pure JVM tests, no Android or network dependencies. */
public final class OutboundFrameQueueTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError("Check " + checks + ": " + message);
    }
    private static byte[] data(int length, int value) {
        byte[] data = new byte[length]; Arrays.fill(data, (byte)value); return data;
    }
    private static OutboundFrameQueue queue(int frames, int bytes) {
        OutboundFrameQueue queue = new OutboundFrameQueue(frames, bytes);
        queue.beginGeneration(1920, 1080); return queue;
    }
    private static OutboundFrameQueue.Frame poll(OutboundFrameQueue q) throws InterruptedException {
        OutboundFrameQueue.Frame frame = q.poll(0);
        check(frame != null, "Expected frame"); return frame;
    }
    private static void illegal(Runnable action, String message) {
        try { action.run(); throw new AssertionError("Expected IllegalArgumentException: " + message); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void initialConfigAndIdr() throws Exception {
        OutboundFrameQueue q = queue(2, 32);
        check(q.generation() == 1 && q.recoveryCount() == 1, "Initial generation/recovery event");
        check(q.consumeKeyframeRequest() && !q.consumeKeyframeRequest(), "Consume initial request once");
        check(!q.offer(0, 0, data(1, 9), 100), "No P before initial IDR");
        byte[] csd = {1, 2}; check(q.offer(0, 2, csd), "Cache CSD"); csd[0] = 99;
        check(q.offer(0, 2, new byte[] {1, 2}), "CSD dedup");
        check(q.depth() == 0 && q.queuedBytes() == 0, "CSD not counted as queued video");
        byte[] source = {3, 4, 5}; check(q.offer(123, 1, source, 456), "Initial IDR accepted"); source[0] = 88;
        OutboundFrameQueue.Frame key = poll(q);
        check(key.generation == 1 && key.width == 1920 && key.height == 1080, "Frame generation/size");
        check(key.ptsUs == 123 && key.encodedAtNs == 456, "Do not replace original output time");
        check(key.startsGop && key.codecConfig != null && key.codecConfig.length == 1, "Bootstrap CSD snapshot");
        check(key.data[0] == 3 && key.codecConfig[0][0] == 1, "Input arrays copied");
        q.markSent(key);
        check(q.offer(124, 0, data(2, 6), 457), "Normal P accepted");
        OutboundFrameQueue.Frame p = poll(q); check(!p.startsGop && p.codecConfig == null, "P is not bootstrap");
        check(q.offer(125, 1, data(2, 7), 458), "Periodic IDR accepted");
        OutboundFrameQueue.Frame periodic = poll(q);
        check(!periodic.startsGop && periodic.codecConfig == null, "Periodic key does not replay CSD");
        check(q.droppedFrames() == 1, "Only initial rejected P counted");
    }
    private static void overflowAndCsd() throws Exception {
        OutboundFrameQueue q = queue(2, 32); q.offer(0, 2, new byte[] {1, 2, 3});
        q.offer(1, 1, data(5, 1), 10); q.offer(2, 0, data(5, 2), 20);
        check(!q.offer(3, 0, data(5, 3), 30), "Overflow P rejected");
        check(q.depth() == 0 && q.queuedBytes() == 0 && q.droppedFrames() == 3, "Entire pending GOP plus incoming P dropped");
        check(q.isAwaitingKeyFrame() && q.consumeKeyframeRequest(), "Overflow starts key request");
        check(!q.offer(4, 0, data(5, 4), 40), "Later dependent P also rejected");
        check(q.offer(5, 1, data(5, 5), 50), "Fresh key bootstrap");
        OutboundFrameQueue.Frame key = poll(q);
        check(key.startsGop && key.codecConfig.length == 1 && key.codecConfig[0][2] == 3, "CSD survives overflow");
        q.markSent(key);
        q.offer(6, 0, data(5, 6)); q.offer(7, 0, data(5, 7));
        check(q.offer(8, 1, data(5, 8)), "Incoming KEY itself can bootstrap overflowing GOP");
        key = poll(q); check(key.startsGop && key.codecConfig.length == 1 && q.depth() == 0, "Overflow KEY is only pending frame");
        check(q.recoveryCount() == 3, "Initial plus two overflow events");
    }
    private static void pendingAndInflightRequestIdempotence() throws Exception {
        OutboundFrameQueue q = queue(2, 32); q.consumeKeyframeRequest();
        q.offer(1, 1, data(4, 1)); q.offer(2, 0, data(4, 2));
        long events = q.recoveryCount(); q.requestRecovery(); q.requestRecovery();
        check(q.depth() == 2 && q.recoveryCount() == events && !q.consumeKeyframeRequest(), "Repeated control cannot kill pending bootstrap");
        OutboundFrameQueue.Frame key = poll(q);
        check(q.hasRecoveryInFlight(), "Polled bootstrap is protected in flight");
        q.requestRecovery(); q.requestRecovery();
        check(q.depth() == 1 && !q.isAwaitingKeyFrame() && !q.consumeKeyframeRequest(), "Repeated control cannot kill in-flight bootstrap or pending P");
        q.markSent(key); check(!q.hasRecoveryInFlight(), "Successful network send releases protection");
        q.requestRecovery(); check(q.depth() == 0 && q.isAwaitingKeyFrame() && q.consumeKeyframeRequest(), "New recovery permitted after actual send");
        events = q.recoveryCount(); q.requestRecovery(); q.requestRecovery();
        check(q.recoveryCount() == events && !q.consumeKeyframeRequest(), "Waiting does not endlessly regenerate request");
        check(!q.offer(3, 0, data(1, 3)), "New recovery never accepts dependent P");
    }
    private static void overflowWhileBootstrapInFlight() throws Exception {
        OutboundFrameQueue q = queue(2, 32); q.offer(1, 1, data(3, 1));
        OutboundFrameQueue.Frame first = poll(q);
        q.offer(2, 0, data(3, 2)); q.offer(3, 0, data(3, 3));
        check(!q.offer(4, 0, data(3, 4)), "Overflow dependent P rejects");
        check(q.hasRecoveryInFlight() && q.depth() == 0 && q.droppedFrames() == 3, "Already-polled key not killed; pending P GOP cleared");
        check(q.consumeKeyframeRequest() && q.isAwaitingKeyFrame(), "Overflow requests another fresh key despite old in-flight key");
        q.requestRecovery(); check(!q.consumeKeyframeRequest(), "Repeated request does not reset recovery");
        q.offer(5, 1, data(3, 5));
        q.markSent(first); q.requestRecovery();
        check(q.depth() == 1 && !q.isAwaitingKeyFrame(), "Old completion cannot kill newly pending bootstrap");
        OutboundFrameQueue.Frame second = poll(q); q.markSent(first);
        check(q.hasRecoveryInFlight(), "Double old ack cannot release newer bootstrap");
        q.markSent(second); check(!q.hasRecoveryInFlight(), "New ack releases its own token");
    }
    private static void staleGenerationAndClose() throws Exception {
        OutboundFrameQueue q = queue(2, 32); q.offer(0, 2, new byte[] {7}); q.offer(1, 1, data(2, 1));
        OutboundFrameQueue.Frame old = poll(q); q.offer(2, 0, data(2, 2));
        q.beginGeneration(1080, 1920);
        check(q.generation() == 2 && q.depth() == 0 && q.queuedBytes() == 0 && q.droppedFrames() == 1, "New generation clears old queued GOP");
        check(q.consumeKeyframeRequest() && !q.hasRecoveryInFlight(), "New generation owns fresh request");
        q.markSent(old); check(q.isAwaitingKeyFrame(), "Stale ack cannot complete new generation");
        q.offer(3, 1, data(2, 3)); OutboundFrameQueue.Frame current = poll(q);
        check(current.codecConfig.length == 0 && current.width == 1080 && current.height == 1920, "New generation clears CSD and uses new dimensions");
        q.markSent(old); q.requestRecovery(); check(q.hasRecoveryInFlight(), "Stale generation ack cannot release current bootstrap");
        q.markSent(current); q.requestRecovery(); check(q.isAwaitingKeyFrame(), "Valid current completion permits recovery");
        q.offer(4, 1, data(2, 4)); long generation = q.generation(); q.close(); q.close();
        q.beginGeneration(640, 480); q.markSent(current); q.requestRecovery();
        check(q.isClosed() && q.generation() == generation && q.depth() == 0 && q.queuedBytes() == 0, "Closed queue never revives");
        check(!q.offer(5, 1, data(2, 5)) && !q.consumeKeyframeRequest() && q.poll(1000) == null, "Closed producer/consumer exit immediately");
    }
    private static void byteAndFrameLimits() throws Exception {
        OutboundFrameQueue q = queue(2, 8); q.offer(0, 2, data(20, 1)); // CSD excluded from byte bound.
        check(q.offer(1, 1, data(5, 1)) && q.offer(2, 0, data(3, 2)), "Exact bytes and frames accepted");
        check(q.depth() == 2 && q.queuedBytes() == 8, "Both limits exact");
        check(!q.offer(3, 0, data(1, 3)), "One extra byte/frame forces GOP clear");
        check(q.depth() == 0 && q.queuedBytes() == 0, "No residual partial GOP");
        check(!q.offer(4, 1, data(9, 4)), "Oversized single KEY rejected");
        check(q.consumeKeyframeRequest() && q.depth() == 0, "Oversize requests recovery");
        q.offer(5, 1, data(8, 5)); OutboundFrameQueue.Frame key = poll(q);
        check(key.data.length == 8 && key.codecConfig[0].length == 20, "Maximum legal frame keeps separately bounded CSD");
        q.markSent(key);
        OutboundFrameQueue byteOnly = queue(4, 8); byteOnly.offer(1, 1, data(5, 1));
        check(!byteOnly.offer(2, 0, data(4, 2)) && byteOnly.depth() == 0, "Byte overflow independent of frame limit");
        illegal(() -> new OutboundFrameQueue(0, 8), "frame bounds"); illegal(() -> new OutboundFrameQueue(2, 0), "byte bounds");
        illegal(() -> q.beginGeneration(0, 1080), "dimensions");
        illegal(() -> q.offer(1, 2, new byte[0]), "empty CSD");
        try { q.poll(-1); throw new AssertionError("negative timeout"); } catch (IllegalArgumentException expected) { checks++; }
    }
    private static void csdChangesAndSnapshots() throws Exception {
        OutboundFrameQueue q = queue(2, 32); q.offer(0, 2, new byte[] {1}); q.offer(1, 1, data(1, 1));
        OutboundFrameQueue.Frame old = poll(q); q.offer(2, 0, data(1, 2));
        check(q.offer(0, 2, new byte[] {2}), "Midstream new CSD accepted");
        check(q.isAwaitingKeyFrame() && q.depth() == 0 && q.hasRecoveryInFlight(), "New CSD forces recovery but cannot unsend old bootstrap");
        check(!q.offer(3, 0, data(1, 3)), "P with changed CSD cannot pass");
        q.offer(4, 1, data(1, 4)); OutboundFrameQueue.Frame key = poll(q);
        check(key.codecConfig.length == 2 && key.codecConfig[1][0] == 2, "Next frame contains updated CSD snapshot");
        key.codecConfig[0][0] = 9; old.codecConfig[0][0] = 8;
        q.markSent(old); q.markSent(key); q.requestRecovery(); q.offer(5, 1, data(1, 5));
        key = poll(q); check(key.codecConfig[0][0] == 1 && key.codecConfig[1][0] == 2, "Frame snapshot edits never corrupt CSD cache");
        long recovery = q.recoveryCount(); q.offer(0, 2, new byte[] {2});
        check(q.recoveryCount() == recovery && !q.isAwaitingKeyFrame(), "Duplicate CSD does not reset stream");
        OutboundFrameQueue pending = queue(2, 32); pending.offer(0, 2, new byte[] {3}); pending.offer(1, 1, data(1, 1));
        pending.offer(0, 2, new byte[] {4});
        check(pending.depth() == 0 && pending.isAwaitingKeyFrame(), "Changed CSD replaces queued old-CSD bootstrap");
        pending.offer(2, 1, data(1, 2)); check(poll(pending).codecConfig.length == 2, "Replacement bootstrap has both configs");
    }
    private static void maxCsd() throws Exception {
        OutboundFrameQueue q = queue(2, 32);
        for (int i = 0; i < 8; i++) check(q.offer(0, 2, new byte[] {(byte)i}), "CSD item " + i);
        check(q.offer(0, 2, new byte[] {0}), "Dedup still accepted when cache full");
        illegal(() -> q.offer(0, 2, new byte[] {8}), "ninth distinct CSD");
        q.offer(1, 1, data(1, 1)); check(poll(q).codecConfig.length == 8, "Rejected item does not alter cache");
        OutboundFrameQueue bytes = queue(2, 32); byte[] max = data(512 * 1024, 1);
        check(bytes.offerOwned(0, 2, max, 1), "Exact CSD byte maximum"); max[0] = 9;
        illegal(() -> bytes.offer(0, 2, new byte[] {2}), "CSD bytes beyond maximum");
        bytes.offer(1, 1, data(1, 1)); check(poll(bytes).codecConfig[0][0] == 1, "Owned CSD is nevertheless copied");
        OutboundFrameQueue tooBig = queue(2, 32);
        illegal(() -> tooBig.offer(0, 2, data(512 * 1024 + 1, 1)), "single oversized CSD");
    }
    private static void ownershipAndTimestamps() throws Exception {
        OutboundFrameQueue q = queue(2, 32); byte[] owned = {1, 2, 3};
        check(q.offerOwned(123, 1, owned, 777), "Ownership transfer accepted");
        OutboundFrameQueue.Frame frame = poll(q);
        check(frame.data == owned && frame.encodedAtNs == 777, "Fast path does not recopy or replace timestamp");
        q.markSent(frame); byte[] reused = {4, 5}; q.offer(124, 0, reused, 888); reused[0] = 9;
        frame = poll(q); check(frame.data != reused && frame.data[0] == 4, "General offer preserves data across reuse");
        check(frame.encodedAtNs == 888 && 988 - frame.encodedAtNs == 100, "Queue dwell remains part of age");
    }
    private static void normalSixtyFrames() throws Exception {
        OutboundFrameQueue q = queue(2, 1024); q.offer(0, 2, data(8, 1));
        for (int i = 0; i < 60; i++) {
            int flags = i % 30 == 0 ? 1 : 0;
            check(q.offer(i * 16667L, flags, data(32, i), 1000000L + i * 16666667L), "Continuous frame " + i);
            OutboundFrameQueue.Frame frame = poll(q);
            check(frame.startsGop == (i == 0), "Only first frame bootstraps CSD");
            q.markSent(frame); check(q.depth() == 0 && q.queuedBytes() == 0, "Writer keeps up");
        }
        check(q.droppedFrames() == 0 && q.recoveryCount() == 1, "No loss in normal 60-frame stream");
    }
    private static void threeFrameCapacity() throws Exception {
        OutboundFrameQueue q = queue(3, 32);
        check(q.offer(1, 1, data(2, 1)), "Three-frame bootstrap accepted");
        OutboundFrameQueue.Frame key = poll(q);
        for (int i = 0; i < 3; i++) check(q.offer(2 + i, 0, data(2, i)), "Three pending P slots " + i);
        check(q.depth() == 3 && q.queuedBytes() == 6, "Configured capacity three is exact");
        q.requestRecovery(); check(!q.consumeKeyframeRequest(), "In-flight key protected at capacity three");
        check(!q.offer(5, 0, data(2, 5)), "Fourth P triggers full GOP recovery");
        check(q.depth() == 0 && q.droppedFrames() == 4 && q.hasRecoveryInFlight(), "Three pending P plus incoming P dropped; in-flight key preserved");
        check(q.consumeKeyframeRequest(), "Three-slot overflow generates key request");
        check(q.offer(6, 1, data(2, 6)), "Replacement key accepted after three-slot overflow");
        q.markSent(key); q.requestRecovery(); check(q.depth() == 1, "Old ack does not kill new pending bootstrap");
        OutboundFrameQueue.Frame next = poll(q); check(next.startsGop && next.codecConfig != null, "Replacement has bootstrap snapshot");
        q.markSent(next); q.close();
    }
    private static void blockingCloseAndInterruption() throws Exception {
        OutboundFrameQueue q = queue(2, 32);
        CountDownLatch entered = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                synchronized (q) {
                    entered.countDown(); // Close cannot acquire this monitor until poll waits.
                    if (q.poll(30000) != null) throw new AssertionError("Expected close/null");
                }
            } catch (Throwable t) { failure.set(t); } finally { exited.countDown(); }
        }, "queue-close-test"); reader.start();
        check(entered.await(2, TimeUnit.SECONDS), "Reader enters poll"); q.close();
        try { check(exited.await(2, TimeUnit.SECONDS), "Close wakes blocked poll promptly"); }
        finally { reader.interrupt(); reader.join(1000); }
        check(failure.get() == null && !reader.isAlive(), "No reader failure/leak");

        OutboundFrameQueue interruptQueue = queue(2, 32);
        CountDownLatch entered2 = new CountDownLatch(1), exited2 = new CountDownLatch(1);
        AtomicReference<Throwable> interrupted = new AtomicReference<>();
        Thread reader2 = new Thread(() -> {
            try {
                synchronized (interruptQueue) { entered2.countDown(); interruptQueue.poll(30000); }
                interrupted.set(new AssertionError("Expected InterruptedException"));
            } catch (InterruptedException expected) { }
            catch (Throwable t) { interrupted.set(t); }
            finally { exited2.countDown(); }
        }, "queue-interrupt-test"); reader2.start();
        check(entered2.await(2, TimeUnit.SECONDS), "Interrupt reader enters"); reader2.interrupt();
        try { check(exited2.await(2, TimeUnit.SECONDS), "Interrupt wakes poll"); }
        finally { interruptQueue.close(); reader2.interrupt(); reader2.join(1000); }
        check(interrupted.get() == null && !reader2.isAlive(), "Interruption propagated and cleaned up");
        OutboundFrameQueue timeout = queue(2, 32); long start = System.nanoTime();
        check(timeout.poll(20) == null, "Timed poll expires");
        check(System.nanoTime() - start >= 10000000L, "Positive timeout does not accidentally become poll(0)");
    }
    public static void main(String[] args) throws Exception {
        initialConfigAndIdr(); overflowAndCsd(); pendingAndInflightRequestIdempotence();
        overflowWhileBootstrapInFlight(); staleGenerationAndClose(); byteAndFrameLimits();
        csdChangesAndSnapshots(); maxCsd(); ownershipAndTimestamps(); normalSixtyFrames();
        threeFrameCapacity(); blockingCloseAndInterruption();
        System.out.println("PASS: " + checks + " OutboundFrameQueue assertions (bounds, GOP/CSD recovery, pending/inflight protection, generations, ownership, timestamps, close/interruption, 60 frames)");
    }
}
