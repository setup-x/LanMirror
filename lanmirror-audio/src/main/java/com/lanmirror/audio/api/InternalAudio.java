package com.lanmirror.audio.api;

import android.content.Context;
import android.media.projection.MediaProjection;

import com.lanmirror.audio.internal.InternalAudioImpl;
import com.lanmirror.core.api.AudioExtensions;

/**
 * 可选内部音频。宿主负责录音授权及投影生命周期；不会回退到麦克风。
 */
public final class InternalAudio {
    private InternalAudio() {
    }

    public static AudioExtensions.SenderFactory sender(MediaProjection projection) {
        return InternalAudioImpl.sender(projection);
    }

    public static AudioExtensions.ReceiverFactory receiver(Context context) {
        return InternalAudioImpl.receiver(context);
    }
}
