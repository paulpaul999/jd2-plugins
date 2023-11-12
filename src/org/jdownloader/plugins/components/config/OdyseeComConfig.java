package org.jdownloader.plugins.components.config;

import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.DefaultEnumValue;
import org.appwork.storage.config.annotations.DescriptionForConfigEntry;
import org.appwork.storage.config.annotations.LabelInterface;
import org.jdownloader.plugins.config.Order;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginHost;
import org.jdownloader.plugins.config.Type;

@PluginHost(host = "odysee.com", type = Type.HOSTER)
public interface OdyseeComConfig extends PluginConfigInterface {
    public static enum PreferredStreamQuality implements LabelInterface {
        BEST {
            @Override
            public String getLabel() {
                return "Best";
            }
        },
        Q144P {
            @Override
            public String getLabel() {
                return "144p";
            }
        },
        Q360P {
            @Override
            public String getLabel() {
                return "360p";
            }
        },
        Q720P {
            @Override
            public String getLabel() {
                return "720p";
            }
        },
        Q1080P {
            @Override
            public String getLabel() {
                return "1080p";
            }
        },
        Q2160P {
            @Override
            public String getLabel() {
                return "2160p";
            }
        };
    }

    @AboutConfig
    @DefaultEnumValue("BEST")
    @DescriptionForConfigEntry("If your preferred stream quality is not found, best quality will be downloaded instead.")
    @Order(10)
    PreferredStreamQuality getPreferredStreamQuality();

    void setPreferredStreamQuality(PreferredStreamQuality quality);
}