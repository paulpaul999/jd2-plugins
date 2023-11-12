package org.jdownloader.plugins.components.config;

import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.DefaultEnumValue;
import org.appwork.storage.config.annotations.DescriptionForConfigEntry;
import org.appwork.storage.config.annotations.LabelInterface;
import org.jdownloader.plugins.config.Order;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginHost;
import org.jdownloader.plugins.config.Type;

@PluginHost(host = "xtremestream.co", type = Type.HOSTER)
public interface XtremestreamCoConfig extends PluginConfigInterface {
    public static enum Quality implements LabelInterface {
        Q360 {
            @Override
            public String getLabel() {
                return "360p";
            }
        },
        Q480 {
            @Override
            public String getLabel() {
                return "480p";
            }
        },
        Q720 {
            @Override
            public String getLabel() {
                return "720p";
            }
        },
        Q1080 {
            @Override
            public String getLabel() {
                return "1080p";
            }
        },
        Q2160 {
            @Override
            public String getLabel() {
                return "2160p";
            }
        };
    }

    @AboutConfig
    @DefaultEnumValue("Q1080")
    @DescriptionForConfigEntry("If your preferred stream quality is not found, best quality will be downloaded instead.")
    @Order(10)
    Quality getPreferredStreamQuality();

    void setPreferredStreamQuality(Quality quality);
}