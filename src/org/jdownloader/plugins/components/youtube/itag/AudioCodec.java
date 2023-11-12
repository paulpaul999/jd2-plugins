package org.jdownloader.plugins.components.youtube.itag;

import org.appwork.storage.config.annotations.LabelInterface;
import org.appwork.storage.config.annotations.TooltipInterface;
import org.jdownloader.plugins.components.youtube.YT_STATICS;
import org.jdownloader.plugins.components.youtube.variants.AbstractVariant;
import org.jdownloader.plugins.components.youtube.variants.AudioInterface;
import org.jdownloader.translate._JDT;

public enum AudioCodec implements LabelInterface, TooltipInterface {
    AAC("Advanced Audio Codec", "AAC"),
    VORBIS("Vorbis Audio", "Vorbis"),
    OPUS("Opus Audio", "Opus"),
    OPUS_SPATIAL(null, "Opus 6Ch") {
        public String getLabelLong() {
            return _JDT.T.AudioCodec_opus_spatial();
        }
    },
    MP3("MP3", "MP3"),
    AMR("Adaptive Multi-Rate Codec", "ARM"),
    AAC_SPATIAL(null, "AAC 6Ch") {
        public String getLabelLong() {
            return _JDT.T.AudioCodec_aac_spatial();
        }
    },
    AC3_SPATIAL(null, "AC-3 6Ch") {
        public String getLabelLong() {
            return _JDT.T.AudioCodec_ac3_spatial();
        }
    },
    EC3_SPATIAL(null, "EC-3 6Ch") {
        public String getLabelLong() {
            return _JDT.T.AudioCodec_ec3_spatial();
        }
    },
    DTSE_SPATIAL(null, "DTSE 6Ch") {
        public String getLabelLong() {
            return _JDT.T.AudioCodec_dtse_spatial();
        }
    },
    VORBIS_SPATIAL(null, "Vorbis 4Ch") {
        public String getLabelLong() {
            return _JDT.T.AudioCodec_vorbis_spatial();
        }
    };
    private String label;
    private String labelLong;

    public String getLabelLong() {
        return labelLong;
    }

    private AudioCodec(String labelLong, String label) {
        this.label = label;
        this.labelLong = labelLong;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String getTooltip() {
        return getLabelLong();
    }

    public static AudioCodec getByVariant(AbstractVariant o1) {
        if (o1 instanceof AudioInterface) {
            return ((AudioInterface) o1).getAudioCodec();
        } else {
            return null;
        }
    }

    public static int getSortId(AbstractVariant v) {
        final AudioCodec res = getByVariant(v);
        if (res == null) {
            return -1;
        } else {
            final Number intObj = YT_STATICS.SORTIDS_AUDIO_CODEC.get(res);
            if (intObj == null) {
                return -1;
            } else {
                return intObj.intValue();
            }
        }
    }
}
