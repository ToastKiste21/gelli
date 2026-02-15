package com.dkanada.gramophone.service.playback;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@OptIn(markerClass = UnstableApi.class)
public class ReplayGainAudioProcessor extends BaseAudioProcessor {

    private volatile float gainDb = 0f;
    private volatile float preAmpDb = 0f;
    private volatile boolean enabled = false;
    private volatile float linearGain = 1f;

    public void setGainDb(float gainDb) {
        this.gainDb = gainDb;
        recalculateLinearGain();
    }

    public void setPreAmpDb(float preAmpDb) {
        this.preAmpDb = preAmpDb;
        recalculateLinearGain();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        recalculateLinearGain();
    }

    private void recalculateLinearGain() {
        if (enabled) {
            linearGain = (float) Math.pow(10, (gainDb + preAmpDb) / 20.0);
        } else {
            linearGain = 1f;
        }
    }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            return inputAudioFormat;
        }
        return AudioFormat.NOT_SET;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) {
            return;
        }

        ByteBuffer outputBuffer = replaceOutputBuffer(remaining);
        float gain = this.linearGain;

        if (gain == 1f) {
            outputBuffer.put(inputBuffer);
        } else {
            while (inputBuffer.remaining() >= 2) {
                short sample = inputBuffer.getShort();
                int amplified = (int) (sample * gain);
                if (amplified > Short.MAX_VALUE) {
                    amplified = Short.MAX_VALUE;
                } else if (amplified < Short.MIN_VALUE) {
                    amplified = Short.MIN_VALUE;
                }
                outputBuffer.putShort((short) amplified);
            }
        }

        outputBuffer.flip();
    }
}
