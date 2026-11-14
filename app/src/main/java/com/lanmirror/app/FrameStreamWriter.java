package com.lanmirror.app;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Optional v2 writer over the ORIGINAL blocking stream. Single writer owner only. */
public final class FrameStreamWriter {
    private final DataOutputStream output;
    private final boolean direct;
    private final byte[] header = new byte[8 + Wire.VIDEO_HEADER_SIZE];
    private final ByteBuffer headerFields = ByteBuffer.wrap(header);
    public FrameStreamWriter(DataOutputStream output, boolean direct) {
        if (output == null) throw new IllegalArgumentException("Missing output");
        this.output = output; this.direct = direct;
    }
    public void write(byte[] data, long pts, int flags, long encodedAtNs) throws IOException {
        if (data == null) throw new IllegalArgumentException("Missing frame");
        if (data.length > Wire.MAX_PACKET - Wire.VIDEO_HEADER_SIZE) throw new IOException("VIDEO exceeds MAX_PACKET");
        if (!direct) {
            Wire.writeVideo(output, ByteBuffer.wrap(data), pts, flags, encodedAtNs); return;
        }
        // No gathering channel, no pool, no delayed batching. Preserve original source timestamp.
        headerFields.clear();
        headerFields.putInt(Wire.VIDEO).putInt(Wire.VIDEO_HEADER_SIZE + data.length);
        headerFields.putLong(pts).putInt(flags).putLong(encodedAtNs);
        output.write(header, 0, header.length);
        output.write(data, 0, data.length);
        output.flush();
    }
}
