//jDownloader - Downloadmanager
//Copyright (C) 2012  JD-Team support@jdownloader.org
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

import java.io.IOException;
import java.util.List;

import org.appwork.utils.StringUtils;
import org.jdownloader.downloader.hds.HDSDownloader;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.plugins.components.hds.HDSContainer;
import org.jdownloader.plugins.components.hls.HlsContainer;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision: 48529 $", interfaceVersion = 3, names = { "orf.at" }, urls = { "https?://tvthek\\.orf\\.atdecrypted\\d+" })
public class ORFMediathek extends PluginForHost {
    private static final String NEW_URLFORMAT  = "https?://tvthek\\.orf\\.atdecrypted\\d+";
    private static final String TYPE_AUDIO     = "https?://ooe\\.orf\\.at/radio/stories/\\d+/";
    public static final String  Q_SUBTITLES    = "Q_SUBTITLES";
    public static final String  Q_THUMBNAIL    = "Q_THUMBNAIL";
    public static final String  Q_BEST         = "Q_BEST_2";
    public static final String  Q_LOW          = "Q_LOW";
    public static final String  Q_VERYLOW      = "Q_VERYLOW";
    public static final String  Q_MEDIUM       = "Q_MEDIUM";
    public static final String  Q_HIGH         = "Q_HIGH";
    public static final String  Q_VERYHIGH     = "Q_VERYHIGH";
    public static final String  VIDEO_SEGMENTS = "VIDEO_SEGMENTS";
    public static final String  VIDEO_GAPLESS  = "VIDEO_GAPLESS";
    public static final String  HTTP_STREAM    = "HTTP_STREAM";
    public static final String  HLS_STREAM     = "HLS_STREAM";
    public static final String  HDS_STREAM     = "HDS_STREAM";

    public ORFMediathek(PluginWrapper wrapper) {
        super(wrapper);
        setConfigElements();
    }

    @Override
    public Browser createNewBrowserInstance() {
        final Browser br = super.createNewBrowserInstance();
        br.setFollowRedirects(true);
        return br;
    }

    @Override
    public String getAGBLink() {
        return "http://orf.at";
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        return requestFileInformation(link, false);
    }

    private AvailableStatus requestFileInformation(final DownloadLink link, final boolean isDownload) throws IOException, PluginException {
        if (!link.getDownloadURL().matches(NEW_URLFORMAT)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        URLConnectionAdapter con = null;
        String dllink = null;
        if (link.getDownloadURL().matches(TYPE_AUDIO)) {
            br.getPage(link.getDownloadURL());
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            String filename = br.getRegex("role=\"article\">[\t\n\r ]+<h1>([^<>]*?)</h1>").getMatch(0);
            if (filename == null) {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED);
            }
            filename = Encoding.htmlDecode(filename).trim();
            filename += ".mp3";
            link.setFinalFileName(filename);
            final String audioID = br.getRegex("data\\-audio=\"(\\d+)\"").getMatch(0);
            if (audioID == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            br.getPage("http://bits.orf.at/filehandler/static-api/json/current/data.json?file=" + audioID);
            dllink = br.getRegex("\"url\":\"(https?[^<>\"]*?)\"").getMatch(0);
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            try {
                con = br.openGetConnection(dllink);
                this.handleConnectionErrors(link, br, con);
                if (looksLikeDownloadableContent(con, link)) {
                    if (con.getCompleteContentLength() > 0) {
                        link.setDownloadSize(con.getCompleteContentLength());
                    }
                    link.setProperty("directURL", dllink);
                } else {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                return AvailableStatus.TRUE;
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        } else if (link.getStringProperty("directURL", null) == null) {
            if (link.getBooleanProperty("offline", false)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            /* fetch fresh directURL */
            this.setBrowserExclusive();
            br.getPage(link.getPluginPatternMatcher());
            if (br.containsHTML("Keine aktuellen Sendungen vorhanden")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (true) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE);
            }
        } else {
            link.setFinalFileName(link.getStringProperty("directName", null));
        }
        if (this.isSubtitle(link) || ("http".equals(link.getStringProperty("streamingType")) && StringUtils.equalsIgnoreCase("progressive", link.getStringProperty("delivery")))) {
            final Browser br2 = br.cloneBrowser();
            dllink = link.getStringProperty("directURL");
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            try {
                con = br2.openHeadConnection(dllink);
                handleConnectionErrors(link, br2, con);
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
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

    private void handleConnectionErrors(final DownloadLink link, final Browser br, final URLConnectionAdapter con) throws PluginException, IOException {
        if (!this.looksLikeDownloadableContent(con, link)) {
            br.followConnection(true);
            if (con.getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (con.getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Broken file?");
            }
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link, true);
        download(link);
    }

    @SuppressWarnings("deprecation")
    private void download(final DownloadLink link) throws Exception {
        final String dllink = link.getStringProperty("directURL");
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (!link.getDownloadURL().matches(TYPE_AUDIO)) {
            if (dllink.contains("hinweis_fsk")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Nur von 20-06 Uhr verfügbar!", 30 * 60 * 1000l);
            }
        }
        if ("hls".equals(link.getStringProperty("delivery"))) {
            checkFFmpeg(link, "Download a HLS Stream");
            br.getPage(dllink);
            checkGeoBlockedForSegmentStreamDownloads(br);
            final HlsContainer best = HlsContainer.findBestVideoByBandwidth(HlsContainer.getHlsQualities(br));
            if (best == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl = new HLSDownloader(link, br, best.getDownloadurl());
            dl.startDownload();
        } else if ("hds".equals(link.getStringProperty("delivery"))) {
            br.getPage(dllink);
            checkGeoBlockedForSegmentStreamDownloads(br);
            final List<HDSContainer> all = HDSContainer.getHDSQualities(br);
            if (all == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final HDSContainer hit = HDSContainer.findBestVideoByResolution(all);
            if (hit == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            hit.write(link);
            final HDSDownloader dl = new HDSDownloader(link, br, hit.getFragmentURL()) {
                @Override
                protected URLConnectionAdapter onNextFragment(URLConnectionAdapter connection, int fragmentIndex) throws IOException, PluginException {
                    if (fragmentIndex == 1 && StringUtils.containsIgnoreCase(connection.getRequest().getLocation(), "geoprotection_")) {
                        connection.disconnect();
                        throw new PluginException(LinkStatus.ERROR_FATAL, "GEO-blocked");
                    }
                    return super.onNextFragment(connection, fragmentIndex);
                }
            };
            this.dl = dl;
            dl.setEstimatedDuration(hit.getDuration());
            dl.startDownload();
        } else if (dllink.startsWith("rtmp")) {
            /* 2023-11-27: This should never happen */
            throw new PluginException(LinkStatus.ERROR_FATAL, "Unsupported protocol rtmp(e)");
        } else {
            if (isSubtitle(link)) {
                /* Workaround for old downloadcore bug that can lead to incomplete files */
                br.getHeaders().put("Accept-Encoding", "identity");
            }
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
            this.handleConnectionErrors(link, br, dl.getConnection());
            dl.startDownload();
        }
    }

    private void checkGeoBlockedForSegmentStreamDownloads(final Browser br) throws PluginException {
        final String errortextGeoBlocked1 = "Error 403: GEO-blocked content or video temporarily unavailable via this streaming method. Check your orf.at plugin settings.";
        final String errortextGeoBlocked2 = "GEO-blocked";
        if (br.getHttpConnection().getResponseCode() == 403) {
            throw new PluginException(LinkStatus.ERROR_FATAL, errortextGeoBlocked1);
        } else if (StringUtils.containsIgnoreCase(br.getURL(), "geoprotection_")) {
            throw new PluginException(LinkStatus.ERROR_FATAL, errortextGeoBlocked2);
        } else if (StringUtils.containsIgnoreCase(br.getURL(), "nicht_verfuegbar_hr")) {
            /* 2023-11-27 */
            throw new PluginException(LinkStatus.ERROR_FATAL, errortextGeoBlocked2);
        }
    }

    private boolean isSubtitle(final DownloadLink link) {
        final String streamingType = link.getStringProperty("streamingType");
        if (StringUtils.equalsIgnoreCase(streamingType, "subtitle")) {
            return true;
        } else {
            return false;
        }
    }

    protected boolean looksLikeDownloadableContent(final URLConnectionAdapter con, final DownloadLink link) {
        if (super.looksLikeDownloadableContent(con)) {
            return true;
        } else if (isSubtitle(link) && isSubtitleContent(con)) {
            return true;
        } else if (isSubtitle(link) && con.isOK()) {
            /* 2023-08-15 e.g. https://api-tvthek.orf.at/assets/subtitles/0158/88/3bca2b4fb96099bfd35871d61a63ab06342eacc6.srt */
            logger.info("Subtitle is missing [correct] content-type header: " + con.getURL());
            return true;
        } else {
            return false;
        }
    }

    private static boolean isSubtitleContent(final URLConnectionAdapter con) {
        return con.getResponseCode() == 200 && (StringUtils.containsIgnoreCase(con.getContentType(), "text/xml") || StringUtils.containsIgnoreCase(con.getContentType(), "text/vtt"));
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public String getDescription() {
        return "JDownloader's ORF Plugin helps downloading videoclips from orf.at. ORF provides different video qualities.";
    }

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), Q_SUBTITLES, "Download subtitle whenever possible").setDefaultValue(false));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), Q_THUMBNAIL, "Download thumbnail whenever possible").setDefaultValue(false));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        final ConfigEntry bestonly = new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), Q_BEST, JDL.L("plugins.hoster.orf.best", "Load Best Version ONLY")).setDefaultValue(true);
        getConfig().addEntry(bestonly);
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), Q_VERYLOW, JDL.L("plugins.hoster.orf.loadverylow", "Load very low version")).setDefaultValue(true).setEnabledCondidtion(bestonly, false));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), Q_LOW, JDL.L("plugins.hoster.orf.loadlow", "Load low version")).setDefaultValue(true).setEnabledCondidtion(bestonly, false));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), Q_MEDIUM, JDL.L("plugins.hoster.orf.loadmedium", "Load medium version")).setDefaultValue(true).setEnabledCondidtion(bestonly, false));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), Q_HIGH, JDL.L("plugins.hoster.orf.loadhigh", "Load high version")).setDefaultValue(true).setEnabledCondidtion(bestonly, false));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), Q_VERYHIGH, JDL.L("plugins.hoster.orf.loadveryhigh", "Load very high version")).setDefaultValue(true).setEnabledCondidtion(bestonly, false));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), VIDEO_GAPLESS, JDL.L("plugins.hoster.orf.videogapless", "Load gapless video")).setDefaultValue(true));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), VIDEO_SEGMENTS, JDL.L("plugins.hoster.orf.videosegments", "Load video segments")).setDefaultValue(true));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), HTTP_STREAM, JDL.L("plugins.hoster.orf.loadhttp", "Load http streams ONLY")).setDefaultValue(true));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), HLS_STREAM, JDL.L("plugins.hoster.orf.loadhttp", "Load hls streams ONLY")).setDefaultValue(true));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), HDS_STREAM, JDL.L("plugins.hoster.orf.loadhttp", "Load hds streams ONLY")).setDefaultValue(true));
    }
}