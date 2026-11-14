package com.lanmirror.receiver.internal;

import com.lanmirror.core.api.*;
import com.lanmirror.receiver.api.ReceiverSession;

import android.view.Surface;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Smooth transport adapter. Network workers NEVER call MediaCodec native methods.
 */
public final class SmoothReceiverImpl {
    private final Surface surface;
    private final String code;
    private final int port;
    private final ReceiverSession.Listener listener;
    private final Object lifecycle = new Object();
    private Worker current;
    private long epoch;

    public SmoothReceiverImpl(Surface surface, String code, int port, ReceiverSession.Listener listener) {
        if (surface == null || listener == null || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid receiver arguments");
        Wire.requireCode(code);
        this.surface = surface;
        this.code = code;
        this.port = port;
        this.listener = listener;
    }

    public void start() {
        synchronized (lifecycle) {
            if (current != null) return;
            current = new Worker(++epoch);
            current.thread.start();
        }
    }

    public void stop() {
        Worker worker;
        synchronized (lifecycle) {
            worker = current;
        }
        if (worker == null) return;
        worker.cancel();
        if (Thread.currentThread() != worker.thread) try {
            worker.thread.join(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Worker implements Runnable {
        final long id;
        final Thread thread = new Thread(this, "smooth-receiver-server");
        final Object resources = new Object();
        volatile boolean cancelled;
        ServerSocket server;
        Socket accepted;
        Session session;
        long connectionSequence;

        Worker(long id) {
            this.id = id;
        }

        boolean live() {
            synchronized (lifecycle) {
                return !cancelled && epoch == id;
            }
        }

        void status(String text) {
            if (live()) try {
                listener.onStatus(text);
            } catch (RuntimeException ignored) {
            }
        }

        void size(int width, int height) {
            if (live()) try {
                listener.onVideoSize(width, height);
            } catch (RuntimeException ignored) {
            }
        }

        void cancel() {
            cancelled = true;
            synchronized (resources) {
                closeQuietly(accepted);
                closeQuietly(server);
                if (session != null) session.abort();
            }
            thread.interrupt();
        }

        public void run() {
            try {
                ServerSocket listening = new ServerSocket();
                synchronized (resources) {
                    if (cancelled) {
                        closeQuietly(listening);
                        return;
                    }
                    server = listening;
                }
                listening.setReuseAddress(true);
                listening.bind(new InetSocketAddress(port));
                status("流畅模式监听 TCP " + port + "；异步AVC / 150–250ms目标");
                while (!cancelled) {
                    Socket peer = listening.accept();
                    Session next = new Session(peer, ++connectionSequence);
                    synchronized (resources) {
                        if (cancelled) {
                            closeQuietly(peer);
                            break;
                        }
                        accepted = peer;
                        session = next;
                    }
                    try {
                        try {
                            listener.onConnectionStarted(next.connectionId);
                        } catch (RuntimeException ignored) {
                        }
                        next.run();
                    } catch (IOException | RuntimeException e) {
                        next.endReason.compareAndSet(null, "流畅接收中断：" + describe(e));
                    } finally {
                        next.close();
                        synchronized (resources) {
                            if (accepted == peer) accepted = null;
                            if (session == next) session = null;
                        }
                        try {
                            listener.onConnectionEnded(next.connectionId);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    if (!cancelled)
                        status((next.endReason.get() == null ? "发送结束" : next.endReason.get()) + "；流畅接收等待重连");
                }
            } catch (IOException | RuntimeException e) {
                status("流畅监听失败：" + describe(e));
            } finally {
                cancel();
                synchronized (lifecycle) {
                    if (current == this) current = null;
                }
            }
        }

        private final class Session {
            final Socket peer;
            final long connectionId;
            boolean firstFrameReported;
            final FrameBufferPool pool = new FrameBufferPool(4 * 1024 * 1024, 16 * 1024 * 1024, 24);
            final ArrayBlockingQueue<Control> controls = new ArrayBlockingQueue<>(16);
            final Object controlGate = new Object();
            volatile boolean closed;
            final java.util.concurrent.atomic.AtomicReference<String> endReason = new java.util.concurrent.atomic.AtomicReference<>();

            void failure(String reason) {
                endReason.compareAndSet(null, reason);
                status(reason);
                abort();
            }

            volatile AsyncSurfaceDecoder decoder;
            volatile DatagramSocket datagrams;
            volatile UdpVideoTransport.Receiver udp;
            Thread udpThread, controlThread;
            DataOutputStream output;
            long lastSyncNs = Long.MIN_VALUE, lastHeartbeatNs;
            volatile long lastDecoderReportNs = System.nanoTime(), lastNetworkReadNs = System.nanoTime();
            volatile String lastDecoderReport = "waiting for codec owner report";

            Session(Socket peer, long connectionId) {
                this.peer = peer;
                this.connectionId = connectionId;
            }

            void firstFrame() {
                synchronized (resources) {
                    if (!live() || session != this || closed || firstFrameReported) return;
                    firstFrameReported = true;
                }
                try {
                    listener.onFirstFrame(connectionId);
                } catch (RuntimeException ignored) {
                }
            }

            void run() throws IOException {
                peer.setTcpNoDelay(true);
                peer.setReceiveBufferSize(256 * 1024);
                peer.setSoTimeout(10000);
                DataInputStream input = new DataInputStream(new BufferedInputStream(peer.getInputStream(), 64 * 1024));
                output = new DataOutputStream(new BufferedOutputStream(peer.getOutputStream(), 1024));
                DatagramSocket socket = new DatagramSocket(0);
                socket.setSoTimeout(10);
                datagrams = socket;
                long sessionId;
                do {
                    sessionId = new SecureRandom().nextLong();
                } while (sessionId == 0);
                boolean useUdp = SmoothWire.accept(input, output, code, sessionId, socket.getLocalPort());
                Wire.ClockSync clock = Wire.serverClockSync(input, output);
                peer.setSoTimeout(0);
                if (closed || cancelled) return;
                controlThread = new Thread(this::writeControls, "smooth-receiver-controls");
                controlThread.start();
                decoder = new AsyncSurfaceDecoder(surface, clock, new AsyncSurfaceDecoder.Listener() {
                    public void onConfigured(long generation) {
                        enqueue(new Control(SmoothWire.CONFIG_ACK, generation));
                    }

                    public void onVideoSize(int w, int h) {
                        size(w, h);
                    }

                    public void onFirstFrameRendered() {
                        firstFrame();
                    }

                    public void onStatus(String text) {
                        lastDecoderReportNs = System.nanoTime();
                        lastDecoderReport = text;
                        UdpVideoTransport.Receiver transport = udp;
                        String network = transport == null ? "TCP ordered video; no wireless loss measurement" :
                                "UDP complete/lost-frame/invalid/reordered/NACK=" + transport.completedFrames() + "/" + transport.lostFrames() + "/" + transport.invalidDatagrams() + "/" + transport.reorderedDatagrams() + "/" + transport.nackRequests() + " (lost-frame includes recovery/dependency discard, NOT wireless packet-loss rate)";
                        status(text + "\nPool new/reuse=" + pool.allocations() + "/" + pool.reuseCount() + " retained=" + pool.retainedBytes() / 1048576.0 + "MiB / " + pool.retainedCount() + " buffers\n" + network);
                    }

                    public void onNeedKeyFrame() {
                        requestSync();
                    }

                    public void onError(Exception error) {
                        failure("异步解码错误：" + describe(error));
                    }
                });
                if (useUdp) {
                    udp = new UdpVideoTransport.Receiver(socket, peer.getInetAddress(), sessionId, pool, new UdpVideoTransport.Listener() {
                        public void onFrame(StreamFrame frame) {
                            lastNetworkReadNs = System.nanoTime();
                            AsyncSurfaceDecoder d = decoder;
                            if (closed || d == null) frame.release();
                            else d.offer(frame);
                        }

                        public void onGap() {
                            AsyncSurfaceDecoder d = decoder;
                            if (d != null) d.discontinuity();
                            requestSync();
                        }
                    });
                    udpThread = new Thread(() -> {
                        try {
                            while (!closed && !cancelled) udp.receiveOnce();
                        } catch (IOException | RuntimeException e) {
                            if (!closed) {
                                failure("UDP接收错误：" + describe(e));
                            }
                        }
                    }, "smooth-udp-reader");
                    udpThread.start();
                } else {
                    socket.close();
                    datagrams = null;
                }
                status("流畅模式已连接 " + (useUdp ? "UDP视频/TCP控制" : "TCP视频/控制") + "；真实异步Codec callback；RTT " + clock.roundTripNs / 1e6 + "ms");
                while (!closed && !cancelled) {
                    SmoothWire.Message message = SmoothWire.readMessage(input, pool);
                    lastNetworkReadNs = System.nanoTime();
                    if (message.type == SmoothWire.CONFIG) {
                        decoder.configure(message.config);
                    } else if (message.type == SmoothWire.VIDEO) {
                        StreamFrame frame = message.frame;
                        if (frame == null) throw new IOException("Missing VIDEO payload");
                        // The agreed transport is authoritative; never mix TCP and UDP dependency chains.
                        if (useUdp) {
                            frame.release();
                            throw new IOException("Unexpected TCP VIDEO during UDP session");
                        }
                        decoder.offer(frame);
                    } else if (message.type == SmoothWire.END) {
                        break;
                    } else {
                        if (message.frame != null) message.frame.release();
                        throw new IOException("Unexpected sender control type " + message.type);
                    }
                }
            }

            void requestSync() {
                synchronized (controlGate) {
                    long now = System.nanoTime();
                    if (lastSyncNs != Long.MIN_VALUE && now - lastSyncNs < 300_000_000L) return;
                    if (closed) return;
                    lastSyncNs = now;
                    enqueue(new Control(SmoothWire.REQUEST_SYNC, 0));
                }
            }

            void enqueue(Control control) {
                if (closed) return;
                if (!controls.offer(control)) {
                    failure("控制队列异常积压，结束连接");
                }
            }

            void writeControls() {
                try {
                    while (!closed) {
                        Control control = controls.poll(250, TimeUnit.MILLISECONDS);
                        heartbeat();
                        if (control == null) continue;
                        if (control.type == SmoothWire.CONFIG_ACK)
                            SmoothWire.writeConfigAck(output, control.generation);
                        else if (control.type == SmoothWire.REQUEST_SYNC)
                            SmoothWire.writeSync(output);
                        else if (control.type == SmoothWire.END) SmoothWire.writeEnd(output);
                    }
                } catch (IOException | InterruptedException | RuntimeException e) {
                    if (!closed) {
                        failure("控制通道结束：" + describe(e));
                    }
                }
            }

            void heartbeat() {
                long now = System.nanoTime(), quiet = now - lastDecoderReportNs;
                if (decoder == null || closed || quiet < 2_000_000_000L || now - lastHeartbeatNs < 1_000_000_000L)
                    return;
                lastHeartbeatNs = now;
                UdpVideoTransport.Receiver transport = udp;
                status("DIAG: codec-owner report paused " + quiet / 1000000 + "ms; TCP socket closed=" + peer.isClosed()
                        + "; since complete network message/frame=" + (now - lastNetworkReadNs) / 1000000 + "ms"
                        + (transport == null ? "" : "; UDP completed=" + transport.completedFrames())
                        + ". Independent observation only: not a native-call wakeup or proven deadlock.\nLast codec report:\n" + lastDecoderReport);
            }

            void abort() {
                closed = true;
                closeQuietly(peer);
                DatagramSocket s = datagrams;
                if (s != null) s.close();
                if (controlThread != null) controlThread.interrupt();
                if (udpThread != null) udpThread.interrupt();
            }

            void close() {
                abort();
                UdpVideoTransport.Receiver receiver = udp;
                if (receiver != null) receiver.close();
                joinBounded(udpThread);
                joinBounded(controlThread);
                AsyncSurfaceDecoder d = decoder;
                decoder = null;
                if (d != null) d.close();
                controls.clear();
                pool.close();
            }
        }
    }

    private static final class Control {
        final int type;
        final long generation;

        Control(int type, long generation) {
            this.type = type;
            this.generation = generation;
        }
    }

    private static void joinBounded(Thread thread) {
        if (thread != null && Thread.currentThread() != thread) try {
            thread.join(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Closeable resource) {
        if (resource != null) try {
            resource.close();
        } catch (IOException ignored) {
        }
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
