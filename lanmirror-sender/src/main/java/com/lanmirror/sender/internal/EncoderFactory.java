package com.lanmirror.sender.internal;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.lanmirror.core.api.StreamProfile;

import java.io.IOException;
import java.util.Locale;

final class EncoderFactory {
    private EncoderFactory() {
    }

    private static boolean hardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated();
        String name = info.getName().toLowerCase(Locale.US);
        return !name.contains("google") && !name.contains("android") && !name.contains("ffmpeg");
    }

    static Encoder create(int width, int height, int bitrate) throws IOException {
        String lastFailure = "没有可用硬件编码器";
        for (MediaCodecInfo candidate : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!candidate.isEncoder() || !hardware(candidate)) continue;
            MediaCodecInfo.CodecCapabilities caps;
            try {
                caps = candidate.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
            } catch (IllegalArgumentException unsupported) {
                continue;
            }
            if (caps.getVideoCapabilities() == null || !caps.getVideoCapabilities().areSizeAndRateSupported(width, height, StreamProfile.FPS))
                continue;
            if (!caps.getVideoCapabilities().getBitrateRange().contains(bitrate)) {
                lastFailure = candidate.getName() + ": 请求" + bitrate / 1000000 + "Mbps，设备声明范围" + caps.getVideoCapabilities().getBitrateRange();
                continue;
            }
            int selectedProfile = 0, selectedLevel = 0;
            int[] preferredProfiles = Build.VERSION.SDK_INT >= 29
                    ? new int[]{MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileMain, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh}
                    : new int[]{MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline};
            for (int preferred : preferredProfiles) {
                int level = (long) width * height >= 2000000L ? MediaCodecInfo.CodecProfileLevel.AVCLevel42 : MediaCodecInfo.CodecProfileLevel.AVCLevel32;
                if (StreamProfile.needsLevel5(preferred == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, width, height, bitrate))
                    level = MediaCodecInfo.CodecProfileLevel.AVCLevel5;
                for (MediaCodecInfo.CodecProfileLevel p : caps.profileLevels) {
                    if (p.profile == preferred && p.level >= level) {
                        selectedProfile = preferred;
                        selectedLevel = level;
                        break;
                    }
                }
                if (selectedProfile != 0) break;
            }
            if (selectedProfile == 0) {
                lastFailure = candidate.getName() + ": no suitable AVC profile/level for requested 60fps/bitrate";
                continue;
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                MediaCodec codec = null;
                Surface surface = null;
                try {
                    MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                    format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                    format.setInteger(MediaFormat.KEY_FRAME_RATE, StreamProfile.FPS);
                    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    format.setInteger(MediaFormat.KEY_PROFILE, selectedProfile);
                    format.setInteger(MediaFormat.KEY_LEVEL, selectedLevel);
                    if (Build.VERSION.SDK_INT >= 29)
                        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                    if (caps.getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
                        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                    if (attempt == 0) {
                        format.setInteger(MediaFormat.KEY_LATENCY, 0);
                        format.setInteger(MediaFormat.KEY_OPERATING_RATE, StreamProfile.FPS);
                        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 16667L);
                        if (Build.VERSION.SDK_INT >= 29)
                            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, StreamProfile.FPS);
                    }
                    if (!caps.isFormatSupported(format)) {
                        lastFailure = candidate.getName() + ": full requested 60fps/profile/level/bitrate format not supported";
                        continue;
                    }
                    codec = MediaCodec.createByCodecName(candidate.getName());
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    surface = codec.createInputSurface();
                    codec.start();
                    return new Encoder(codec, surface, candidate.getName(), attempt == 0);
                } catch (IOException | RuntimeException e) {
                    lastFailure = candidate.getName() + ": " + SenderEngine.message(e);
                    if (surface != null) surface.release();
                    if (codec != null) try {
                        codec.release();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        throw new IOException("设备不支持 " + width + "×" + height + " AVC@60 / " + bitrate / 1000000 + "Mbps；未自动降帧/码率。可手动选18/12Mbps或720p60。 " + lastFailure);
    }

    static final class Encoder {
        final MediaCodec codec;
        final Surface surface;
        final String name;
        final boolean hints;

        Encoder(MediaCodec codec, Surface surface, String name, boolean hints) {
            this.codec = codec;
            this.surface = surface;
            this.name = name;
            this.hints = hints;
        }

        void close() {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                codec.release();
            } catch (RuntimeException ignored) {
            }
            surface.release();
        }
    }
}
