//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.DefaultBooleanValue;
import org.jdownloader.controlling.ffmpeg.json.StreamInfo;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.plugins.config.Order;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.translate._JDT;

@HostPlugin(revision = "$Revision: 47484 $", interfaceVersion = 3, names = { "rtbf.be" }, urls = { "http://rtbf\\.bedecrypted/\\d+" })
public class RtbfBe extends PluginForHost {
    public RtbfBe(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.rtbf.be/";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    private String  dllink               = null;
    private boolean server_issues        = false;
    private boolean possibly_geo_blocked = false;

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        server_issues = false;
        possibly_geo_blocked = false;
        this.setBrowserExclusive();
        this.br.setFollowRedirects(true);
        dllink = link.getStringProperty("directlink", null);
        final String filename = link.getStringProperty("directfilename", null);
        if (filename == null || dllink == null) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        link.setFinalFileName(filename);
        if (dllink.contains("m3u8")) {
            checkFFProbe(link, "Download a HLS Stream");
            final HLSDownloader downloader = new HLSDownloader(link, br, dllink);
            final StreamInfo streamInfo = downloader.getProbe();
            if (streamInfo == null) {
                if (dllink.contains("/drm/")) {
                    throw new PluginException(LinkStatus.ERROR_FATAL, "603 Restricted (DRM)");
                } else {
                    // throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    server_issues = true;
                }
            } else {
                final long estimatedSize = downloader.getEstimatedSize();
                if (estimatedSize > 0) {
                    link.setDownloadSize(estimatedSize);
                }
            }
        } else {
            URLConnectionAdapter con = null;
            try {
                final Browser brc = br.cloneBrowser();
                brc.setFollowRedirects(true);
                con = brc.openHeadConnection(dllink);
                if (con.getResponseCode() == 603) {
                    throw new PluginException(LinkStatus.ERROR_FATAL, "603 Restricted (DRM)");
                }
                if (this.looksLikeDownloadableContent(con)) {
                    if (con.getCompleteContentLength() > 0) {
                        link.setVerifiedFileSize(con.getCompleteContentLength());
                    }
                } else {
                    possibly_geo_blocked = con.getResponseCode() == 403;
                    server_issues = true;
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        if (possibly_geo_blocked) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "GEO-blocked");
        } else if (server_issues) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        } else if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (dllink.contains("m3u8")) {
            checkFFmpeg(link, "Download a HLS Stream");
            dl = new HLSDownloader(link, br, dllink);
            dl.startDownload();
        } else {
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                logger.warning("The final dllink seems not to be a file!");
                br.followConnection(true);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl.startDownload();
        }
    }

    public boolean allowHandle(final DownloadLink downloadLink, final PluginForHost plugin) {
        return downloadLink.getHost().equalsIgnoreCase(plugin.getHost());
    }

    @Override
    public String getDescription() {
        return "JDownloader's rtbf.be plugin helps downloading videoclips from rtbf.be.";
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return RtbfBeConfigInterface.class;
    }

    public static interface RtbfBeConfigInterface extends PluginConfigInterface {
        public static class TRANSLATION {
            public String getFastLinkcheckEnabled_label() {
                return _JDT.T.lit_enable_fast_linkcheck();
            }

            public String getGrabBESTEnabled_label() {
                return _JDT.T.lit_add_only_the_best_video_quality();
            }

            public String getOnlyBestVideoQualityOfSelectedQualitiesEnabled_label() {
                return _JDT.T.lit_add_only_the_best_video_quality_within_user_selected_formats();
            }

            public String getAddUnknownQualitiesEnabled_label() {
                return _JDT.T.lit_add_unknown_formats();
            }
        }

        public static final TRANSLATION TRANSLATION = new TRANSLATION();

        @DefaultBooleanValue(true)
        @Order(9)
        boolean isFastLinkcheckEnabled();

        void setFastLinkcheckEnabled(boolean b);

        @DefaultBooleanValue(false)
        @Order(20)
        boolean isGrabBESTEnabled();

        void setGrabBESTEnabled(boolean b);

        @AboutConfig
        @DefaultBooleanValue(false)
        @Order(21)
        boolean isOnlyBestVideoQualityOfSelectedQualitiesEnabled();

        void setOnlyBestVideoQualityOfSelectedQualitiesEnabled(boolean b);

        // @DefaultBooleanValue(true)
        // @Order(21)
        // boolean isAddUnknownQualitiesEnabled();
        //
        // void setAddUnknownQualitiesEnabled(boolean b);
        @DefaultBooleanValue(true)
        @Order(30)
        boolean isGrabHLS200pVideoEnabled();

        void setGrabHLS200pVideoEnabled(boolean b);

        @DefaultBooleanValue(true)
        @Order(40)
        boolean isGrabHLS480pVideoEnabled();

        void setGrabHLS480pVideoEnabled(boolean b);

        @DefaultBooleanValue(true)
        @Order(70)
        boolean isGrabHLS570pVideoEnabled();

        void setGrabHLS570pVideoEnabled(boolean b);

        @DefaultBooleanValue(true)
        @Order(80)
        boolean isGrabHLS720pVideoEnabled();

        void setGrabHLS720pVideoEnabled(boolean b);

        @DefaultBooleanValue(true)
        @Order(80)
        boolean isGrabHLS1080pVideoEnabled();

        void setGrabHLS1080pVideoEnabled(boolean b);

        @DefaultBooleanValue(true)
        @Order(90)
        boolean isGrabHTTP200pVideoEnabled();

        void setGrabHTTP200pVideoEnabled(boolean b);

        @DefaultBooleanValue(true)
        @Order(100)
        boolean isGrabHTTP480VideoEnabled();

        void setGrabHTTP480VideoEnabled(boolean b);

        @DefaultBooleanValue(true)
        @Order(110)
        boolean isGrabHTTP720VideoEnabled();

        void setGrabHTTP720VideoEnabled(boolean b);
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public void resetPluginGlobals() {
    }
}