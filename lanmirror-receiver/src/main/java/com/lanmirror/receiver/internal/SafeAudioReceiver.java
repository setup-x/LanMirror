package com.lanmirror.receiver.internal;

import com.lanmirror.core.api.AudioExtensions;
import com.lanmirror.core.api.Wire;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

/**
 * Audio extension failures must never escape into the video lifecycle.
 */
final class SafeAudioReceiver implements AudioExtensions.Receiver {
    private final Object gate = new Object();
    private final Set<SafeEndpoint> endpoints = new HashSet<>();
    private AudioExtensions.Receiver receiver;
    private boolean closed;

    SafeAudioReceiver(AudioExtensions.ReceiverFactory factory, AudioExtensions.ReceiverListener listener) {
        if (factory != null) try {
            receiver = factory.create(listener);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public AudioExtensions.Endpoint reserve(InetAddress address, long id, AudioExtensions.Status status) {
        AudioExtensions.Receiver current;
        synchronized (gate) {
            if (closed) return null;
            current = receiver;
        }
        if (current == null) return null;
        AudioExtensions.Endpoint raw = null;
        try {
            raw = current.reserve(address, id, status);
            if (raw == null) return null;
            SafeEndpoint endpoint = new SafeEndpoint(raw, status);
            synchronized (gate) {
                if (!closed) {
                    endpoints.add(endpoint);
                    return endpoint;
                }
            }
        } catch (IOException | RuntimeException error) {
            report(status, "音频监听不可用，继续视频：" + describe(error));
        }
        quiet(raw);
        return null;
    }

    @Override
    public void joined(int slot, long id) {
        invoke(r -> r.joined(slot, id));
    }

    @Override
    public void left(long id) {
        invoke(r -> r.left(id));
    }

    @Override
    public void toggle(int slot, long id) {
        invoke(r -> r.toggle(slot, id));
    }

    private interface Action {
        void run(AudioExtensions.Receiver receiver);
    }

    private void invoke(Action action) {
        AudioExtensions.Receiver current;
        synchronized (gate) {
            if (closed) return;
            current = receiver;
        }
        if (current != null) try {
            action.run(current);
        } catch (RuntimeException error) {
            close();
        }
    }

    @Override
    public void close() {
        AudioExtensions.Receiver current;
        SafeEndpoint[] active;
        synchronized (gate) {
            if (closed) return;
            closed = true;
            current = receiver;
            receiver = null;
            active = endpoints.toArray(new SafeEndpoint[0]);
            endpoints.clear();
        }
        for (SafeEndpoint endpoint : active) endpoint.close();
        quiet(current);
    }

    private final class SafeEndpoint implements AudioExtensions.Endpoint {
        private final AudioExtensions.Endpoint delegate;
        private final AudioExtensions.Status status;
        private final int port;
        private final long high, low;
        private final java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean(), started = new java.util.concurrent.atomic.AtomicBoolean();

        SafeEndpoint(AudioExtensions.Endpoint delegate, AudioExtensions.Status status) {
            this.delegate = delegate;
            this.status = status;
            port = delegate.port();
            high = delegate.tokenHigh();
            low = delegate.tokenLow();
            if (port < 1 || port > 65535 || (high | low) == 0)
                throw new IllegalArgumentException("Invalid audio endpoint");
        }

        public int port() {
            return port;
        }

        public long tokenHigh() {
            return high;
        }

        public long tokenLow() {
            return low;
        }

        public void start(Wire.ClockSync clock) {
            if (ended.get() || !started.compareAndSet(false, true)) return;
            try {
                delegate.start(clock);
            } catch (RuntimeException error) {
                report(status, "音频启动失败，继续视频：" + describe(error));
                close();
            }
        }

        public void close() {
            if (!ended.compareAndSet(false, true)) return;
            synchronized (gate) {
                endpoints.remove(this);
            }
            quiet(delegate);
        }
    }

    private static void quiet(AudioExtensions.Session session) {
        if (session != null) try {
            session.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static void report(AudioExtensions.Status status, String text) {
        if (status != null) try {
            status.onStatus(text);
        } catch (RuntimeException ignored) {
        }
    }

    private static String describe(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
