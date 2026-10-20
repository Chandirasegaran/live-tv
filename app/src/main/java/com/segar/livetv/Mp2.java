package com.segar.livetv;

/**
 * MPEG-1 Audio Layer II (MP2) decoder: one frame in, 1152 PCM samples per channel out.
 * Many Indian satellite-sourced channels carry MP2 audio, and plenty of Android TVs have no
 * decoder for it. Ported from pl_mpeg by Dominic Szablewski (MIT licence), which is based on
 * kjmp2 by Martin J. Fiedler. https://github.com/phoboslab/pl_mpeg
 */
final class Mp2 {
    static final int SAMPLES_PER_FRAME = 1152;

    private static final int MODE_JOINT_STEREO = 1;
    private static final int MODE_MONO = 3;

    private static final int[] SAMPLE_RATE = {44100, 48000, 32000};
    private static final int[] BIT_RATE = {32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};
    private static final int[] SCALEFACTOR_BASE = {0x02000000, 0x01965FEA, 0x01428A30};

    private static final float[] SYNTHESIS_WINDOW = {
            0.0f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -1.0f,
            -1.0f, -1.0f, -1.0f, -1.5f, -1.5f, -2.0f, -2.0f, -2.5f,
            -2.5f, -3.0f, -3.5f, -3.5f, -4.0f, -4.5f, -5.0f, -5.5f,
            -6.5f, -7.0f, -8.0f, -8.5f, -9.5f, -10.5f, -12.0f, -13.0f,
            -14.5f, -15.5f, -17.5f, -19.0f, -20.5f, -22.5f, -24.5f, -26.5f,
            -29.0f, -31.5f, -34.0f, -36.5f, -39.5f, -42.5f, -45.5f, -48.5f,
            -52.0f, -55.5f, -58.5f, -62.5f, -66.0f, -69.5f, -73.5f, -77.0f,
            -80.5f, -84.5f, -88.0f, -91.5f, -95.0f, -98.0f, -101.0f, -104.0f,
            106.5f, 109.0f, 111.0f, 112.5f, 113.5f, 114.0f, 114.0f, 113.5f,
            112.0f, 110.5f, 107.5f, 104.0f, 100.0f, 94.5f, 88.5f, 81.5f,
            73.0f, 63.5f, 53.0f, 41.5f, 28.5f, 14.5f, -1.0f, -18.0f,
            -36.0f, -55.5f, -76.5f, -98.5f, -122.0f, -147.0f, -173.5f, -200.5f,
            -229.5f, -259.5f, -290.5f, -322.5f, -355.5f, -389.5f, -424.0f, -459.5f,
            -495.5f, -532.0f, -568.5f, -605.0f, -641.5f, -678.0f, -714.0f, -749.0f,
            -783.5f, -817.0f, -849.0f, -879.5f, -908.5f, -935.0f, -959.5f, -981.0f,
            -1000.5f, -1016.0f, -1028.5f, -1037.5f, -1042.5f, -1043.5f, -1040.0f, -1031.5f,
            1018.5f, 1000.0f, 976.0f, 946.5f, 911.0f, 869.5f, 822.0f, 767.5f,
            707.0f, 640.0f, 565.5f, 485.0f, 397.0f, 302.5f, 201.0f, 92.5f,
            -22.5f, -144.0f, -272.5f, -407.0f, -547.5f, -694.0f, -846.0f, -1003.0f,
            -1165.0f, -1331.5f, -1502.0f, -1675.5f, -1852.5f, -2031.5f, -2212.5f, -2394.0f,
            -2576.5f, -2758.5f, -2939.5f, -3118.5f, -3294.5f, -3467.5f, -3635.5f, -3798.5f,
            -3955.0f, -4104.5f, -4245.5f, -4377.5f, -4499.0f, -4609.5f, -4708.0f, -4792.5f,
            -4863.5f, -4919.0f, -4958.0f, -4979.5f, -4983.0f, -4967.5f, -4931.5f, -4875.0f,
            -4796.0f, -4694.5f, -4569.5f, -4420.0f, -4246.0f, -4046.0f, -3820.0f, -3567.0f,
            3287.0f, 2979.5f, 2644.0f, 2280.5f, 1888.0f, 1467.5f, 1018.5f, 541.0f,
            35.0f, -499.0f, -1061.0f, -1650.0f, -2266.5f, -2909.0f, -3577.0f, -4270.0f,
            -4987.5f, -5727.5f, -6490.0f, -7274.0f, -8077.5f, -8899.5f, -9739.0f, -10594.5f,
            -11464.5f, -12347.0f, -13241.0f, -14144.5f, -15056.0f, -15973.5f, -16895.5f, -17820.0f,
            -18744.5f, -19668.0f, -20588.0f, -21503.0f, -22410.5f, -23308.5f, -24195.0f, -25068.5f,
            -25926.5f, -26767.0f, -27589.0f, -28389.0f, -29166.5f, -29919.0f, -30644.5f, -31342.0f,
            -32009.5f, -32645.0f, -33247.0f, -33814.5f, -34346.0f, -34839.5f, -35295.0f, -35710.0f,
            -36084.5f, -36417.5f, -36707.5f, -36954.0f, -37156.5f, -37315.0f, -37428.0f, -37496.0f,
            37519.0f, 37496.0f, 37428.0f, 37315.0f, 37156.5f, 36954.0f, 36707.5f, 36417.5f,
            36084.5f, 35710.0f, 35295.0f, 34839.5f, 34346.0f, 33814.5f, 33247.0f, 32645.0f,
            32009.5f, 31342.0f, 30644.5f, 29919.0f, 29166.5f, 28389.0f, 27589.0f, 26767.0f,
            25926.5f, 25068.5f, 24195.0f, 23308.5f, 22410.5f, 21503.0f, 20588.0f, 19668.0f,
            18744.5f, 17820.0f, 16895.5f, 15973.5f, 15056.0f, 14144.5f, 13241.0f, 12347.0f,
            11464.5f, 10594.5f, 9739.0f, 8899.5f, 8077.5f, 7274.0f, 6490.0f, 5727.5f,
            4987.5f, 4270.0f, 3577.0f, 2909.0f, 2266.5f, 1650.0f, 1061.0f, 499.0f,
            -35.0f, -541.0f, -1018.5f, -1467.5f, -1888.0f, -2280.5f, -2644.0f, -2979.5f,
            3287.0f, 3567.0f, 3820.0f, 4046.0f, 4246.0f, 4420.0f, 4569.5f, 4694.5f,
            4796.0f, 4875.0f, 4931.5f, 4967.5f, 4983.0f, 4979.5f, 4958.0f, 4919.0f,
            4863.5f, 4792.5f, 4708.0f, 4609.5f, 4499.0f, 4377.5f, 4245.5f, 4104.5f,
            3955.0f, 3798.5f, 3635.5f, 3467.5f, 3294.5f, 3118.5f, 2939.5f, 2758.5f,
            2576.5f, 2394.0f, 2212.5f, 2031.5f, 1852.5f, 1675.5f, 1502.0f, 1331.5f,
            1165.0f, 1003.0f, 846.0f, 694.0f, 547.5f, 407.0f, 272.5f, 144.0f,
            22.5f, -92.5f, -201.0f, -302.5f, -397.0f, -485.0f, -565.5f, -640.0f,
            -707.0f, -767.5f, -822.0f, -869.5f, -911.0f, -946.5f, -976.0f, -1000.0f,
            1018.5f, 1031.5f, 1040.0f, 1043.5f, 1042.5f, 1037.5f, 1028.5f, 1016.0f,
            1000.5f, 981.0f, 959.5f, 935.0f, 908.5f, 879.5f, 849.0f, 817.0f,
            783.5f, 749.0f, 714.0f, 678.0f, 641.5f, 605.0f, 568.5f, 532.0f,
            495.5f, 459.5f, 424.0f, 389.5f, 355.5f, 322.5f, 290.5f, 259.5f,
            229.5f, 200.5f, 173.5f, 147.0f, 122.0f, 98.5f, 76.5f, 55.5f,
            36.0f, 18.0f, 1.0f, -14.5f, -28.5f, -41.5f, -53.0f, -63.5f,
            -73.0f, -81.5f, -88.5f, -94.5f, -100.0f, -104.0f, -107.5f, -110.5f,
            -112.0f, -113.5f, -114.0f, -114.0f, -113.5f, -112.5f, -111.0f, -109.0f,
            106.5f, 104.0f, 101.0f, 98.0f, 95.0f, 91.5f, 88.0f, 84.5f,
            80.5f, 77.0f, 73.5f, 69.5f, 66.0f, 62.5f, 58.5f, 55.5f,
            52.0f, 48.5f, 45.5f, 42.5f, 39.5f, 36.5f, 34.0f, 31.5f,
            29.0f, 26.5f, 24.5f, 22.5f, 20.5f, 19.0f, 17.5f, 15.5f,
            14.5f, 13.0f, 12.0f, 10.5f, 9.5f, 8.5f, 8.0f, 7.0f,
            6.5f, 5.5f, 5.0f, 4.5f, 4.0f, 3.5f, 3.5f, 3.0f,
            2.5f, 2.5f, 2.0f, 2.0f, 1.5f, 1.5f, 1.0f, 1.0f,
            1.0f, 1.0f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f
    };

    // Quantizer lookup, step 1: bitrate classes [mono/stereo][bitrate index]
    private static final int[][] QUANT_LUT_STEP_1 = {
            {0, 0, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2},
            {0, 0, 0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 2, 2}};
    // Step 2: bitrate class, sample rate -> B2 table (bit 6+) and sblimit (low 6 bits)
    private static final int TAB_A = 27 | 64, TAB_B = 30 | 64, TAB_C = 8, TAB_D = 12;
    private static final int[][] QUANT_LUT_STEP_2 = {
            {TAB_C, TAB_C, TAB_D},
            {TAB_A, TAB_A, TAB_A},
            {TAB_B, TAB_A, TAB_B}};
    // Step 3: B2 table, subband -> nbal (upper 4 bits), row index (lower 4 bits)
    private static final int[][] QUANT_LUT_STEP_3 = {
            {0x44, 0x44, 0x34, 0x34, 0x34, 0x34, 0x34, 0x34, 0x34, 0x34, 0x34, 0x34},
            {0x43, 0x43, 0x43, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
                    0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31,
                    0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20}};
    // Step 4: table row, allocation value -> quantizer index (1-based; 0 = none)
    private static final int[][] QUANT_LUT_STEP_4 = {
            {0, 1, 2, 17},
            {0, 1, 2, 3, 4, 5, 6, 17},
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17},
            {0, 1, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17},
            {0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17},
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}};
    // Quantizer specs {levels, grouped, bits}, indexed by quantizer index - 1
    private static final int[][] QUANT_TAB = {
            {3, 1, 5}, {5, 1, 7}, {7, 0, 3}, {9, 1, 10}, {15, 0, 4}, {31, 0, 5}, {63, 0, 6},
            {127, 0, 7}, {255, 0, 8}, {511, 0, 9}, {1023, 0, 10}, {2047, 0, 11}, {4095, 0, 12},
            {8191, 0, 13}, {16383, 0, 14}, {32767, 0, 15}, {65535, 0, 16}};

    private final float[] d = new float[1024];
    private final float[][] v = new float[2][1024];
    private final float[] u = new float[32];
    private final int[][] allocation = new int[2][32]; // quantizer index + 1, 0 = none
    private final int[][] scaleFactorInfo = new int[2][32];
    private final int[][][] scaleFactor = new int[2][32][3];
    private final int[][][] sample = new int[2][32][3];
    private int vPos;

    private byte[] buf;
    private int bitPos, bitEnd;

    Mp2() {
        System.arraycopy(SYNTHESIS_WINDOW, 0, d, 0, 512);
        System.arraycopy(SYNTHESIS_WINDOW, 0, d, 512, 512);
    }

    /** Clears the synthesis filter history, e.g. after a seek or channel change. */
    void reset() {
        for (float[] ch : v) java.util.Arrays.fill(ch, 0f);
        vPos = 0;
    }

    /** Sample rate of the frame starting at {@code data[offset]}, or 0 if it isn't MPEG-1 Layer II. */
    static int sampleRate(byte[] data, int offset) {
        if (data.length - offset < 4 || (data[offset] & 0xFF) != 0xFF || (data[offset + 1] & 0xE0) != 0xE0) return 0;
        int version = (data[offset + 1] >> 3) & 3, layer = (data[offset + 1] >> 1) & 3;
        int rate = (data[offset + 2] >> 2) & 3;
        return version == 3 && layer == 2 && rate < 3 ? SAMPLE_RATE[rate] : 0;
    }

    /**
     * Decodes one frame into {@code out} as interleaved 16-bit stereo (mono is duplicated).
     * Returns false if the data isn't a valid MPEG-1 Layer II frame.
     */
    boolean decode(byte[] data, int offset, int length, short[] out) {
        if (sampleRate(data, offset) == 0) return false;
        buf = data;
        bitPos = offset * 8 + 13; // past the 11-bit sync word and 2-bit version
        bitEnd = (offset + length) * 8;
        read(2); // layer
        boolean hasCrc = read(1) == 0;
        int bitrateIndex = read(4) - 1;
        int samplerateIndex = read(2);
        if (bitrateIndex < 0 || bitrateIndex > 13) return false;
        read(1); // padding
        read(1); // private
        int mode = read(2);
        int bound;
        if (mode == MODE_JOINT_STEREO) {
            bound = (read(2) + 1) << 2;
        } else {
            read(2);
            bound = mode == MODE_MONO ? 0 : 32;
        }
        read(4); // copyright, original, emphasis
        if (hasCrc) read(16);

        // Quantizer tables
        int tab1 = mode == MODE_MONO ? 0 : 1;
        int tab2 = QUANT_LUT_STEP_1[tab1][bitrateIndex];
        int tab3 = QUANT_LUT_STEP_2[tab2][samplerateIndex];
        int sblimit = tab3 & 63;
        tab3 >>= 6;
        if (bound > sblimit) bound = sblimit;

        // Allocation
        for (int sb = 0; sb < bound; sb++) {
            allocation[0][sb] = readAllocation(sb, tab3);
            allocation[1][sb] = readAllocation(sb, tab3);
        }
        for (int sb = bound; sb < sblimit; sb++) {
            allocation[0][sb] = allocation[1][sb] = readAllocation(sb, tab3);
        }

        // Scale factor selector information
        int channels = mode == MODE_MONO ? 1 : 2;
        for (int sb = 0; sb < sblimit; sb++) {
            for (int ch = 0; ch < channels; ch++) {
                if (allocation[ch][sb] != 0) scaleFactorInfo[ch][sb] = read(2);
            }
            if (mode == MODE_MONO) scaleFactorInfo[1][sb] = scaleFactorInfo[0][sb];
        }

        // Scale factors
        for (int sb = 0; sb < sblimit; sb++) {
            for (int ch = 0; ch < channels; ch++) {
                if (allocation[ch][sb] == 0) continue;
                int[] sf = scaleFactor[ch][sb];
                switch (scaleFactorInfo[ch][sb]) {
                    case 0: sf[0] = read(6); sf[1] = read(6); sf[2] = read(6); break;
                    case 1: sf[0] = sf[1] = read(6); sf[2] = read(6); break;
                    case 2: sf[0] = sf[1] = sf[2] = read(6); break;
                    default: sf[0] = read(6); sf[1] = sf[2] = read(6); break;
                }
            }
            if (mode == MODE_MONO) System.arraycopy(scaleFactor[0][sb], 0, scaleFactor[1][sb], 0, 3);
        }

        // Coefficient input and reconstruction
        int outPos = 0;
        for (int part = 0; part < 3; part++) {
            for (int granule = 0; granule < 4; granule++) {
                for (int sb = 0; sb < bound; sb++) {
                    readSamples(0, sb, part);
                    readSamples(1, sb, part);
                }
                for (int sb = bound; sb < sblimit; sb++) {
                    readSamples(0, sb, part);
                    System.arraycopy(sample[0][sb], 0, sample[1][sb], 0, 3);
                }
                for (int sb = sblimit; sb < 32; sb++) {
                    java.util.Arrays.fill(sample[0][sb], 0);
                    java.util.Arrays.fill(sample[1][sb], 0);
                }

                // Synthesis
                for (int p = 0; p < 3; p++) {
                    vPos = (vPos - 64) & 1023;
                    for (int ch = 0; ch < 2; ch++) {
                        idct36(sample[ch], p, v[ch], vPos);
                        java.util.Arrays.fill(u, 0f);
                        float[] vc = v[ch];
                        int dIndex = 512 - (vPos >> 1);
                        int vIndex = (vPos % 128) >> 1;
                        while (vIndex < 1024) {
                            for (int i = 0; i < 32; ++i) u[i] += d[dIndex++] * vc[vIndex++];
                            vIndex += 128 - 32;
                            dIndex += 64 - 32;
                        }
                        dIndex -= 512 - 32;
                        vIndex = (128 - 32 + 1024) - vIndex;
                        while (vIndex < 1024) {
                            for (int i = 0; i < 32; ++i) u[i] += d[dIndex++] * vc[vIndex++];
                            vIndex += 128 - 32;
                            dIndex += 64 - 32;
                        }
                        for (int j = 0; j < 32; j++) {
                            int s = Math.round(u[j] / -1090519040.0f * 32767f);
                            out[((outPos + j) << 1) + ch] = (short) Math.max(-32768, Math.min(32767, s));
                        }
                    }
                    outPos += 32;
                }
            }
        }
        return true;
    }

    private int readAllocation(int sb, int tab3) {
        int tab4 = QUANT_LUT_STEP_3[tab3][sb];
        return QUANT_LUT_STEP_4[tab4 & 15][read(tab4 >> 4)];
    }

    private void readSamples(int ch, int sb, int part) {
        int q = allocation[ch][sb];
        int[] s = sample[ch][sb];
        if (q == 0) {
            s[0] = s[1] = s[2] = 0;
            return;
        }
        int sf = scaleFactor[ch][sb][part];
        if (sf == 63) {
            sf = 0;
        } else {
            int shift = sf / 3;
            sf = (SCALEFACTOR_BASE[sf % 3] + ((1 << shift) >> 1)) >> shift;
        }
        int[] spec = QUANT_TAB[q - 1];
        int adj = spec[0];
        if (spec[1] != 0) {
            int val = read(spec[2]);
            s[0] = val % adj;
            val /= adj;
            s[1] = val % adj;
            s[2] = val / adj;
        } else {
            s[0] = read(spec[2]);
            s[1] = read(spec[2]);
            s[2] = read(spec[2]);
        }
        int scale = 65536 / (adj + 1);
        adj = ((adj + 1) >> 1) - 1;
        for (int i = 0; i < 3; i++) {
            int val = (adj - s[i]) * scale;
            s[i] = (val * (sf >> 12) + ((val * (sf & 4095) + 2048) >> 12)) >> 12;
        }
    }

    /** Reads {@code n} bits, big-endian; reads past the end return zeros. */
    private int read(int n) {
        int value = 0;
        for (int i = 0; i < n; i++) {
            int bit = 0;
            if (bitPos < bitEnd) bit = (buf[bitPos >> 3] >> (7 - (bitPos & 7))) & 1;
            bitPos++;
            value = (value << 1) | bit;
        }
        return value;
    }

    private static void idct36(int[][] s, int ss, float[] d, int dp) {
        float t01, t02, t03, t04, t05, t06, t07, t08, t09, t10, t11, t12,
            t13, t14, t15, t16, t17, t18, t19, t20, t21, t22, t23, t24,
            t25, t26, t27, t28, t29, t30, t31, t32, t33;

        t01 = (float)(s[0][ss] + s[31][ss]); t02 = (float)(s[0][ss] - s[31][ss]) * 0.500602998235f;
        t03 = (float)(s[1][ss] + s[30][ss]); t04 = (float)(s[1][ss] - s[30][ss]) * 0.505470959898f;
        t05 = (float)(s[2][ss] + s[29][ss]); t06 = (float)(s[2][ss] - s[29][ss]) * 0.515447309923f;
        t07 = (float)(s[3][ss] + s[28][ss]); t08 = (float)(s[3][ss] - s[28][ss]) * 0.53104259109f;
        t09 = (float)(s[4][ss] + s[27][ss]); t10 = (float)(s[4][ss] - s[27][ss]) * 0.553103896034f;
        t11 = (float)(s[5][ss] + s[26][ss]); t12 = (float)(s[5][ss] - s[26][ss]) * 0.582934968206f;
        t13 = (float)(s[6][ss] + s[25][ss]); t14 = (float)(s[6][ss] - s[25][ss]) * 0.622504123036f;
        t15 = (float)(s[7][ss] + s[24][ss]); t16 = (float)(s[7][ss] - s[24][ss]) * 0.674808341455f;
        t17 = (float)(s[8][ss] + s[23][ss]); t18 = (float)(s[8][ss] - s[23][ss]) * 0.744536271002f;
        t19 = (float)(s[9][ss] + s[22][ss]); t20 = (float)(s[9][ss] - s[22][ss]) * 0.839349645416f;
        t21 = (float)(s[10][ss] + s[21][ss]); t22 = (float)(s[10][ss] - s[21][ss]) * 0.972568237862f;
        t23 = (float)(s[11][ss] + s[20][ss]); t24 = (float)(s[11][ss] - s[20][ss]) * 1.16943993343f;
        t25 = (float)(s[12][ss] + s[19][ss]); t26 = (float)(s[12][ss] - s[19][ss]) * 1.48416461631f;
        t27 = (float)(s[13][ss] + s[18][ss]); t28 = (float)(s[13][ss] - s[18][ss]) * 2.05778100995f;
        t29 = (float)(s[14][ss] + s[17][ss]); t30 = (float)(s[14][ss] - s[17][ss]) * 3.40760841847f;
        t31 = (float)(s[15][ss] + s[16][ss]); t32 = (float)(s[15][ss] - s[16][ss]) * 10.1900081235f;

        t33 = t01 + t31; t31 = (t01 - t31) * 0.502419286188f;
        t01 = t03 + t29; t29 = (t03 - t29) * 0.52249861494f;
        t03 = t05 + t27; t27 = (t05 - t27) * 0.566944034816f;
        t05 = t07 + t25; t25 = (t07 - t25) * 0.64682178336f;
        t07 = t09 + t23; t23 = (t09 - t23) * 0.788154623451f;
        t09 = t11 + t21; t21 = (t11 - t21) * 1.06067768599f;
        t11 = t13 + t19; t19 = (t13 - t19) * 1.72244709824f;
        t13 = t15 + t17; t17 = (t15 - t17) * 5.10114861869f;
        t15 = t33 + t13; t13 = (t33 - t13) * 0.509795579104f;
        t33 = t01 + t11; t01 = (t01 - t11) * 0.601344886935f;
        t11 = t03 + t09; t09 = (t03 - t09) * 0.899976223136f;
        t03 = t05 + t07; t07 = (t05 - t07) * 2.56291544774f;
        t05 = t15 + t03; t15 = (t15 - t03) * 0.541196100146f;
        t03 = t33 + t11; t11 = (t33 - t11) * 1.30656296488f;
        t33 = t05 + t03; t05 = (t05 - t03) * 0.707106781187f;
        t03 = t15 + t11; t15 = (t15 - t11) * 0.707106781187f;
        t03 += t15;
        t11 = t13 + t07; t13 = (t13 - t07) * 0.541196100146f;
        t07 = t01 + t09; t09 = (t01 - t09) * 1.30656296488f;
        t01 = t11 + t07; t07 = (t11 - t07) * 0.707106781187f;
        t11 = t13 + t09; t13 = (t13 - t09) * 0.707106781187f;
        t11 += t13; t01 += t11;
        t11 += t07; t07 += t13;
        t09 = t31 + t17; t31 = (t31 - t17) * 0.509795579104f;
        t17 = t29 + t19; t29 = (t29 - t19) * 0.601344886935f;
        t19 = t27 + t21; t21 = (t27 - t21) * 0.899976223136f;
        t27 = t25 + t23; t23 = (t25 - t23) * 2.56291544774f;
        t25 = t09 + t27; t09 = (t09 - t27) * 0.541196100146f;
        t27 = t17 + t19; t19 = (t17 - t19) * 1.30656296488f;
        t17 = t25 + t27; t27 = (t25 - t27) * 0.707106781187f;
        t25 = t09 + t19; t19 = (t09 - t19) * 0.707106781187f;
        t25 += t19;
        t09 = t31 + t23; t31 = (t31 - t23) * 0.541196100146f;
        t23 = t29 + t21; t21 = (t29 - t21) * 1.30656296488f;
        t29 = t09 + t23; t23 = (t09 - t23) * 0.707106781187f;
        t09 = t31 + t21; t31 = (t31 - t21) * 0.707106781187f;
        t09 += t31;    t29 += t09;    t09 += t23;    t23 += t31;
        t17 += t29;    t29 += t25;    t25 += t09;    t09 += t27;
        t27 += t23;    t23 += t19; t19 += t31;
        t21 = t02 + t32; t02 = (t02 - t32) * 0.502419286188f;
        t32 = t04 + t30; t04 = (t04 - t30) * 0.52249861494f;
        t30 = t06 + t28; t28 = (t06 - t28) * 0.566944034816f;
        t06 = t08 + t26; t08 = (t08 - t26) * 0.64682178336f;
        t26 = t10 + t24; t10 = (t10 - t24) * 0.788154623451f;
        t24 = t12 + t22; t22 = (t12 - t22) * 1.06067768599f;
        t12 = t14 + t20; t20 = (t14 - t20) * 1.72244709824f;
        t14 = t16 + t18; t16 = (t16 - t18) * 5.10114861869f;
        t18 = t21 + t14; t14 = (t21 - t14) * 0.509795579104f;
        t21 = t32 + t12; t32 = (t32 - t12) * 0.601344886935f;
        t12 = t30 + t24; t24 = (t30 - t24) * 0.899976223136f;
        t30 = t06 + t26; t26 = (t06 - t26) * 2.56291544774f;
        t06 = t18 + t30; t18 = (t18 - t30) * 0.541196100146f;
        t30 = t21 + t12; t12 = (t21 - t12) * 1.30656296488f;
        t21 = t06 + t30; t30 = (t06 - t30) * 0.707106781187f;
        t06 = t18 + t12; t12 = (t18 - t12) * 0.707106781187f;
        t06 += t12;
        t18 = t14 + t26; t26 = (t14 - t26) * 0.541196100146f;
        t14 = t32 + t24; t24 = (t32 - t24) * 1.30656296488f;
        t32 = t18 + t14; t14 = (t18 - t14) * 0.707106781187f;
        t18 = t26 + t24; t24 = (t26 - t24) * 0.707106781187f;
        t18 += t24; t32 += t18;
        t18 += t14; t26 = t14 + t24;
        t14 = t02 + t16; t02 = (t02 - t16) * 0.509795579104f;
        t16 = t04 + t20; t04 = (t04 - t20) * 0.601344886935f;
        t20 = t28 + t22; t22 = (t28 - t22) * 0.899976223136f;
        t28 = t08 + t10; t10 = (t08 - t10) * 2.56291544774f;
        t08 = t14 + t28; t14 = (t14 - t28) * 0.541196100146f;
        t28 = t16 + t20; t20 = (t16 - t20) * 1.30656296488f;
        t16 = t08 + t28; t28 = (t08 - t28) * 0.707106781187f;
        t08 = t14 + t20; t20 = (t14 - t20) * 0.707106781187f;
        t08 += t20;
        t14 = t02 + t10; t02 = (t02 - t10) * 0.541196100146f;
        t10 = t04 + t22; t22 = (t04 - t22) * 1.30656296488f;
        t04 = t14 + t10; t10 = (t14 - t10) * 0.707106781187f;
        t14 = t02 + t22; t02 = (t02 - t22) * 0.707106781187f;
        t14 += t02;    t04 += t14;    t14 += t10;    t10 += t02;
        t16 += t04;    t04 += t08;    t08 += t14;    t14 += t28;
        t28 += t10;    t10 += t20;    t20 += t02;    t21 += t16;
        t16 += t32;    t32 += t04;    t04 += t06;    t06 += t08;
        t08 += t18;    t18 += t14;    t14 += t30;    t30 += t28;
        t28 += t26;    t26 += t10;    t10 += t12;    t12 += t20;
        t20 += t24;    t24 += t02;

        d[dp + 48] = -t33;
        d[dp + 49] = d[dp + 47] = -t21;
        d[dp + 50] = d[dp + 46] = -t17;
        d[dp + 51] = d[dp + 45] = -t16;
        d[dp + 52] = d[dp + 44] = -t01;
        d[dp + 53] = d[dp + 43] = -t32;
        d[dp + 54] = d[dp + 42] = -t29;
        d[dp + 55] = d[dp + 41] = -t04;
        d[dp + 56] = d[dp + 40] = -t03;
        d[dp + 57] = d[dp + 39] = -t06;
        d[dp + 58] = d[dp + 38] = -t25;
        d[dp + 59] = d[dp + 37] = -t08;
        d[dp + 60] = d[dp + 36] = -t11;
        d[dp + 61] = d[dp + 35] = -t18;
        d[dp + 62] = d[dp + 34] = -t09;
        d[dp + 63] = d[dp + 33] = -t14;
        d[dp + 32] = -t05;
        d[dp + 0] = t05; d[dp + 31] = -t30;
        d[dp + 1] = t30; d[dp + 30] = -t27;
        d[dp + 2] = t27; d[dp + 29] = -t28;
        d[dp + 3] = t28; d[dp + 28] = -t07;
        d[dp + 4] = t07; d[dp + 27] = -t26;
        d[dp + 5] = t26; d[dp + 26] = -t23;
        d[dp + 6] = t23; d[dp + 25] = -t10;
        d[dp + 7] = t10; d[dp + 24] = -t15;
        d[dp + 8] = t15; d[dp + 23] = -t12;
        d[dp + 9] = t12; d[dp + 22] = -t19;
        d[dp + 10] = t19; d[dp + 21] = -t20;
        d[dp + 11] = t20; d[dp + 20] = -t13;
        d[dp + 12] = t13; d[dp + 19] = -t24;
        d[dp + 13] = t24; d[dp + 18] = -t31;
        d[dp + 14] = t31; d[dp + 17] = -t02;
        d[dp + 15] = t02; d[dp + 16] = 0f;
    }
}
