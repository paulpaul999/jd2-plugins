package org.jdownloader.plugins.components.containers;

import org.appwork.storage.TypeRef;
import org.jdownloader.plugins.components.hls.HlsContainer;

/**
 *
 * custom video container to support vimeo.
 *
 * @author raztoki
 *
 */
public class VimeoContainer extends VideoContainer {
    public static final TypeRef<VimeoContainer> TYPE_REF = new TypeRef<VimeoContainer>() {
                                                         };
    private Quality                             quality;
    private String                              rawQuality;

    public String getRawQuality() {
        return rawQuality;
    }

    public void setRawQuality(String rawQuality) {
        this.rawQuality = rawQuality;
    }

    private String lang;

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    private Source source;
    private String codec;
    private Long   estimatedSize = null;

    public VimeoContainer() {
    }

    public Long getEstimatedSize() {
        return estimatedSize;
    }

    public void setEstimatedSize(Long estimatedSize) {
        this.estimatedSize = estimatedSize;
    }

    // order is important, worst to best
    public enum Quality {
        MOBILE,
        SD,
        HD, // 720
        FHD, // 1080
        UHD,
        UHD_4K,
        ORIGINAL,
        SOURCE
    }

    // order is important, methods that can resume vs not resume.
    public enum Source {
        HLS, // hls. (currently can't resume)
        DASH, // dash in segments. (think this can resume)
        WEB, // standard mp4. (can resume)
        DOWNLOAD, // from download button. (can resume)
        SUBTITLE;
    }

    /**
     * @return the quality
     */
    public final Quality getQuality() {
        return quality;
    }

    /**
     * @return the codec
     */
    public final String getCodec() {
        return codec;
    }

    /**
     * @param quality
     *            the quality to set
     */
    public final void setQuality(Quality quality) {
        this.quality = quality;
    }

    /**
     * sets quality reference based on current Height value
     */
    public final void updateQualityByHeight() {
        setQuality(getQuality(getHeight()));
    }

    /**
     * @param codec
     *            the codec to set
     */
    public final void setCodec(String codec) {
        this.codec = codec;
    }

    /**
     * @return the source
     */
    public Source getSource() {
        return source;
    }

    /**
     * @param source
     *            the source to set
     */
    public void setSource(Source source) {
        this.source = source;
    }

    /**
     * determines from input value
     *
     * @param i
     * @return
     */
    public static Quality getQuality(final int height) {
        if (height == -1) {
            return null;
        } else {
            if (height >= 2160) {
                return Quality.UHD_4K;
            } else if (height >= 1440) {
                return Quality.UHD;
            } else if (height >= 1080) {
                return Quality.FHD;
            } else if (height >= 720) {
                return Quality.HD;
            } else {
                return Quality.SD;
            }
        }
    }

    /**
     * create VimeoVideoContainer from HLSContainer
     *
     * @param container
     * @return
     */
    public static VimeoContainer createVimeoVideoContainer(HlsContainer container) {
        final VimeoContainer vvm = new VimeoContainer();
        vvm.setCodec(container.getCodecs());
        vvm.setWidth(container.getWidth());
        vvm.setHeight(container.getHeight());
        vvm.setBitrate(container.getBandwidth());
        vvm.setFramerate(container.getFramerate());
        vvm.setSource(Source.HLS);
        vvm.setQuality(getQuality(container.getHeight()));
        vvm._setDownloadurlAndExtension(container.getDownloadurl(), ".mp4");
        return vvm;
    }

    /**
     * create specific linkid to allow multiple entries (can be annoying if you never want to download dupe).
     *
     * @param id
     * @return
     */
    public String createLinkID(final String id) {
        final String linkid;
        if (Source.SUBTITLE.equals(getSource())) {
            linkid = id.concat("_").concat(toString(getSource())).concat(toString(getLang()));
        } else {
            linkid = id.concat("_").concat(getQuality().toString()).concat("_").concat(String.valueOf(getWidth())).concat("x").concat(String.valueOf(getHeight())).concat(toString(getSource()).concat(toString(getLang())));
        }
        return linkid;
    }

    private String toString(Object object) {
        if (object == null) {
            return "";
        } else {
            return "_".concat(object.toString());
        }
    }

    @Override
    public String toString() {
        return super.toString().concat(getQuality() != null ? "|" + getQuality().toString() : "").concat(getSource() != null ? "|" + getSource().toString() : "");
    }

    /** Internal String to differ between different qualities. */
    public String bestString() {
        if (Source.SUBTITLE.equals(getSource())) {
            return getLang();
        } else if (getQuality() == Quality.ORIGINAL || getQuality() == Quality.SOURCE) {
            /*
             * Special case: Original download is an exception as the resolution does not matter: If wished, it should always be added as it
             * will definitely be the BEST quality available.
             */
            return String.valueOf(getWidth()).concat("x").concat(String.valueOf(getHeight())).concat(getQuality().toString()).concat(getSource().toString());
        } else {
            return String.valueOf(getWidth()).concat("x").concat(String.valueOf(getHeight()));
        }
    }
}
