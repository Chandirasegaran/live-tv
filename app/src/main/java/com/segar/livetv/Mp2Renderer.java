package com.segar.livetv;

import android.os.Handler;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Plays MP2 (MPEG-1 Layer II) audio with the pure-Java {@link Mp2} decoder. Added after the
 * platform renderers, so a TV with its own MP2 decoder still uses that.
 */
@OptIn(markerClass = UnstableApi.class)
final class Mp2Renderer extends DecoderAudioRenderer<Mp2Renderer.Decoder> {

    Mp2Renderer(Handler handler, AudioRendererEventListener listener, AudioSink sink) {
        super(handler, listener, sink);
    }

    @Override
    public String getName() {
        return "Mp2Renderer";
    }

    @Override
    protected int supportsFormatInternal(Format format) {
        if (!MimeTypes.AUDIO_MPEG_L2.equals(format.sampleMimeType)) return C.FORMAT_UNSUPPORTED_TYPE;
        if (format.sampleRate != 32000 && format.sampleRate != 44100 && format.sampleRate != 48000) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE; // MPEG-2 low-sample-rate variant isn't supported
        }
        if (!sinkSupportsFormat(Util.getPcmFormat(C.ENCODING_PCM_16BIT, 2, format.sampleRate))) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        return C.FORMAT_HANDLED;
    }

    @Override
    protected Decoder createDecoder(Format format, CryptoConfig cryptoConfig) {
        android.util.Log.i("Mp2Renderer", "no platform MP2 decoder; using the Java one at " + format.sampleRate + " Hz");
        return new Decoder(format.sampleRate);
    }

    @Override
    protected Format getOutputFormat(Decoder decoder) {
        return Util.getPcmFormat(C.ENCODING_PCM_16BIT, 2, decoder.sampleRate);
    }

    /** One MP2 frame in, 1152 interleaved 16-bit stereo samples out. */
    static final class Decoder
            extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {
        final int sampleRate;
        private final Mp2 mp2 = new Mp2();
        private final short[] pcm = new short[Mp2.SAMPLES_PER_FRAME * 2];
        private byte[] frame = new byte[2048];

        Decoder(int sampleRate) {
            super(new DecoderInputBuffer[16], new SimpleDecoderOutputBuffer[16]);
            this.sampleRate = sampleRate;
        }

        @Override
        public String getName() {
            return "java-mp2";
        }

        @Override
        protected DecoderInputBuffer createInputBuffer() {
            return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
        }

        @Override
        protected SimpleDecoderOutputBuffer createOutputBuffer() {
            return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
        }

        @Override
        protected DecoderException createUnexpectedDecodeException(Throwable error) {
            return new DecoderException("MP2 decode failed", error);
        }

        @Override
        protected DecoderException decode(DecoderInputBuffer input, SimpleDecoderOutputBuffer output, boolean reset) {
            if (reset) mp2.reset();
            ByteBuffer in = input.data;
            int len = in == null ? 0 : in.remaining();
            if (len > frame.length) frame = new byte[len];
            if (len > 0) in.get(frame, 0, len);
            if (len == 0 || !mp2.decode(frame, 0, len, pcm)) {
                output.shouldBeSkipped = true; // not an MP2 frame: drop it rather than stop playback
                return null;
            }
            ByteBuffer out = output.init(input.timeUs, pcm.length * 2).order(ByteOrder.nativeOrder());
            out.asShortBuffer().put(pcm);
            out.position(0);
            out.limit(pcm.length * 2);
            return null;
        }
    }
}
