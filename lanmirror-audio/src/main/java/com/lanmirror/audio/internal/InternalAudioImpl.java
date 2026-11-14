package com.lanmirror.audio.internal;

import android.content.Context;
import android.media.projection.MediaProjection;

import com.lanmirror.core.api.AudioExtensions;
import com.lanmirror.core.api.Wire;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges platform audio to the optional core extension contracts.
 */
public final class InternalAudioImpl {
    private InternalAudioImpl() {
    }

    public static AudioExtensions.SenderFactory sender(MediaProjection projection) {
        return (address, port, high, low, status) -> new Sender(projection, address, port, high, low, status);
    }

    public static AudioExtensions.ReceiverFactory receiver(Context context) {
        return listener -> new Receiver(context, listener);
    }

    private static final class Sender implements AudioExtensions.Sender {
        private final AudioExtensions.Status status;
        private final AudioLaneSender lane;
        private final InternalAudioCapture capture;
        private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean();
        private volatile boolean failed;

        Sender(MediaProjection projection, InetAddress address, int port, long high, long low, AudioExtensions.Status status) {
            this.status = status;
            lane = new AudioLaneSender(address, port, high, low, new AudioLaneSender.Listener() {
                public void onStatus(String text) {
                    if (!failed) report(text);
                }

                public void onError(String text) {
                    failed = true;
                    report(text);
                    close();
                }
            });
            try {
                capture = new InternalAudioCapture(projection, new InternalAudioCapture.Listener() {
                    public void onFrame(PcmAudioFrame frame) {
                        lane.offer(frame);
                    }

                    public void onStatus(String text) {
                        if (!failed) report(text);
                    }

                    public void onError(String text) {
                        failed = true;
                        report(text);
                        lane.finishWithStatus(text);
                        stopCapture();
                    }
                });
            } catch (RuntimeException error) {
                lane.close();
                throw error;
            }
        }

        public void start() {
            if (closed.get() || !started.compareAndSet(false, true)) return;
            try {
                lane.start();
                if (!closed.get()) capture.start();
            } catch (RuntimeException error) {
                failed = true;
                report("内部声音不可用：" + describe(error) + "；视频继续");
                close();
            }
        }

        private void stopCapture() {
            capture.close();
        }

        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            capture.close();
            lane.close();
        }

        public void awaitStopped() {
            capture.awaitStopped();
        }

        private void report(String text) {
            if (!closed.get() && status != null) try {
                status.onStatus(text);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static final class Receiver implements AudioExtensions.Receiver {
        private final Object gate = new Object();
        private final AudioSelectionState selection = new AudioSelectionState();
        private final Map<Long, Integer> members = new HashMap<>();
        private final Map<Long, Endpoint> endpoints = new HashMap<>();
        private final AudioExtensions.ReceiverListener listener;
        private final RealtimeAudioPlayer player;
        private long applied = -1;
        private boolean closed;

        Receiver(Context context, AudioExtensions.ReceiverListener listener) {
            if (listener == null) throw new IllegalArgumentException("Listener required");
            this.listener = listener;
            player = new RealtimeAudioPlayer(context, new RealtimeAudioPlayer.Listener() {
                public void onStatus(long id, String text) {
                    playbackStatus(id, text, false);
                }

                public void onError(long id, String text) {
                    playbackStatus(id, text, true);
                }
            });
        }

        public AudioExtensions.Endpoint reserve(InetAddress address, long id, AudioExtensions.Status status) throws IOException {
            synchronized (gate) {
                if (closed) return null;
            }
            Endpoint endpoint = new Endpoint(address, id, status);
            synchronized (gate) {
                if (!closed) {
                    Endpoint old = endpoints.put(id, endpoint);
                    if (old != null) old.close();
                    return endpoint;
                }
            }
            endpoint.close();
            return null;
        }

        public void joined(int slot, long id) {
            synchronized (gate) {
                if (closed) return;
                members.put(id, slot);
                if (selection.joined(slot, id)) publishSelection();
            }
        }

        public void left(long id) {
            synchronized (gate) {
                if (closed) return;
                members.remove(id);
                if (selection.left(id)) publishSelection();
            }
        }

        public void toggle(int slot, long id) {
            synchronized (gate) {
                if (!closed && selection.toggle(slot, id)) publishSelection();
            }
        }

        private void publishSelection() {
            long selected = closed ? 0 : selection.selected();
            if (selected != applied) {
                applied = selected;
                player.select(selected);
            }
            if (!closed) try {
                listener.onSelection(selected, selection.revision());
            } catch (RuntimeException ignored) {
            }
            String failure = player.failureReason();
            if (selected > 0 && failure != null) playbackStatus(selected, failure, true);
        }

        private void playbackStatus(long id, String text, boolean error) {
            synchronized (gate) {
                if (closed) return;
                if (error) {
                    for (long member : members.keySet().toArray(new Long[0]))
                        notifyPlayback(member, text, true);
                } else if (members.containsKey(id)) notifyPlayback(id, text, false);
            }
        }

        private void notifyPlayback(long id, String text, boolean error) {
            try {
                listener.onPlaybackStatus(id, text, error);
            } catch (RuntimeException ignored) {
            }
        }

        public void close() {
            Endpoint[] active;
            synchronized (gate) {
                if (closed) return;
                closed = true;
                selection.clear();
                publishSelection();
                members.clear();
                active = endpoints.values().toArray(new Endpoint[0]);
                endpoints.clear();
            }
            for (Endpoint endpoint : active) endpoint.close();
            player.close();
        }

        private final class Endpoint implements AudioExtensions.Endpoint {
            private final long id;
            private final AudioLaneReceiver lane;
            private final AtomicBoolean ended = new AtomicBoolean();

            Endpoint(InetAddress address, long id, AudioExtensions.Status status) throws IOException {
                this.id = id;
                lane = new AudioLaneReceiver(address, new AudioLaneReceiver.Listener() {
                    public void onFrame(PcmAudioFrame frame, long localCapturedAtNs) {
                        synchronized (gate) {
                            if (!closed && !ended.get() && members.containsKey(id))
                                player.offer(id, frame, localCapturedAtNs);
                        }
                    }

                    public void onStatus(String text) {
                        if (!ended.get() && status != null) try {
                            status.onStatus(text);
                        } catch (RuntimeException ignored) {
                        }
                    }
                });
            }

            public int port() {
                return lane.port();
            }

            public long tokenHigh() {
                return lane.tokenHigh;
            }

            public long tokenLow() {
                return lane.tokenLow;
            }

            public void start(Wire.ClockSync clock) {
                if (ended.get()) return;
                try {
                    lane.start(clock);
                } catch (RuntimeException error) {
                    close();
                    throw error;
                }
            }

            public void close() {
                if (!ended.compareAndSet(false, true)) return;
                lane.close();
                synchronized (gate) {
                    if (endpoints.get(id) == this) endpoints.remove(id);
                }
            }
        }
    }

    private static String describe(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
