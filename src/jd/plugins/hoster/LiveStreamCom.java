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

import java.util.List;
import java.util.Map;

import org.appwork.utils.StringUtils;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.downloader.hls.M3U8Playlist;
import org.jdownloader.plugins.components.hls.HlsContainer;
import org.jdownloader.plugins.controller.LazyPlugin;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 48064 $", interfaceVersion = 2, names = { "livestream.com" }, urls = { "https?://(?:www\\.)?livestream\\.com/[^<>\"]+/videos/(\\d+)" })
public class LiveStreamCom extends PluginForHost {
    @SuppressWarnings("deprecation")
    public LiveStreamCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.VIDEO_STREAMING };
    }

    /* DEV NOTES */
    // Tags:
    // protocol: https + http available
    // other:
    /* Example main: http://livestream.com/cnet/LG/videos/85498136 */
    /*
     * http:
     * http://pdvod.new.livestream.com/events/00000000003cfce1/893bc539-0412-415a-b0be-438b5f81b086_678.mp4?start=614&end=3454&__gda__=
     * 1430264265_cd1870f0b469d93ce3955d4bd400e203
     */
    /*
     * akamai HD streaming: http://api.new.livestream.com/accounts/687825/events/3996897/videos/85498136.secure.smil -->
     * https://livestreamvod-f.akamaihd.net/events/00000000003cfce1/893bc539-0412-415a-b0be-438b5f81b086_2320.mp4
     */
    /* hls: http://api.new.livestream.com/accounts/687825/events/3996897/videos/85498136.m3u8 */
    /*
     * Thumbnail url (also containing important info):
     * http://img.new.livestream.com/events/00000000003cfce1/893bc539-0412-415a-b0be-438b5f81b086_1320.jpg
     */
    /* Connection stuff */
    private static final boolean free_resume       = true;
    private static final int     free_maxchunks    = 0;
    private static final int     free_maxdownloads = -1;
    private String               progressive_url   = null;
    private String               m3u8_url          = null;

    @Override
    public String getAGBLink() {
        return "http://livestream.com/terms";
    }

    private Map<String, Object> getEntry(long entryID, List<Map<String, Object>> entries) {
        for (final Map<String, Object> entry : entries) {
            final String type = (String) entry.get("type");
            if (type == null || !type.equals("video")) {
                continue;
            }
            final Map<String, Object> data = (Map<String, Object>) entry.get("data");
            final long id = JavaScriptEngineFactory.toLong(data.get("id"), -1);
            if (id == entryID) {
                return data;
            }
        }
        return null;
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://" + fid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        final String fid = this.getFID(link);
        if (!link.isNameSet()) {
            link.setName(fid + ".mp4");
        }
        String filename = null;
        progressive_url = null;
        m3u8_url = null;
        final long entryId = Long.parseLong(fid);
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(link.getDownloadURL());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String json = br.getRegex("window\\.config[\t\n\r ]*?=[\t\n\r ]*?(\\{.+?);</script>").getMatch(0);
        if (json == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        Map<String, Object> entries = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(json);
        entries = (Map<String, Object>) entries.get("event");
        entries = (Map<String, Object>) entries.get("feed");
        Map<String, Object> entry = getEntry(entryId, (List<Map<String, Object>>) entries.get("data"));
        if (entry == null) {
            final String eventsID = new Regex(link.getPluginPatternMatcher(), "/events/(\\d+)/").getMatch(0);
            final String accountID = br.getRegex("/accounts/(\\d+)/events/" + eventsID).getMatch(0);
            if (eventsID != null) {
                final Browser brc = br.cloneBrowser();
                brc.getPage("https://api.new.livestream.com/accounts/" + accountID + "/events/" + eventsID + "/feed.json?id=" + entryId + "&type=video&newer=1&older=1");
                entries = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(brc.toString());
                entry = getEntry(entryId, (List<Map<String, Object>>) entries.get("data"));
            }
        }
        if (entry == null) {
            final String title = br.getRegex("og:title\"\\s*content\\s*=\\s*\"(.*?)\"").getMatch(0);
            if (title != null && !link.isNameSet()) {
                link.setName(title + ".mp4");
            }
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else {
            filename = (String) entry.get("caption");
            progressive_url = (String) entry.get("secure_progressive_url");
            if (StringUtils.isEmpty(progressive_url) || StringUtils.contains(progressive_url, "unsupported.mp4")) {
                progressive_url = null;
                m3u8_url = (String) entry.get("secure_m3u8_url");
                if (StringUtils.isEmpty(m3u8_url)) {
                    m3u8_url = null;
                }
            }
        }
        if (filename == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else if (StringUtils.isAllEmpty(progressive_url, m3u8_url)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        filename = entryId + "_" + filename;
        final String ext = ".mp4";
        if (!filename.endsWith(ext)) {
            filename += ext;
        }
        link.setFinalFileName(filename);
        if (progressive_url != null) {
            progressive_url = Encoding.htmlDecode(progressive_url);
            final Browser br2 = br.cloneBrowser();
            // In case the link redirects to the finallink
            br2.setFollowRedirects(true);
            URLConnectionAdapter con = null;
            try {
                con = br2.openHeadConnection(progressive_url);
                if (this.looksLikeDownloadableContent(con)) {
                    if (con.getCompleteContentLength() > 0) {
                        link.setDownloadSize(con.getCompleteContentLength());
                    }
                } else {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        } else if (m3u8_url != null) {
            final Browser m3u8 = br.cloneBrowser();
            m3u8.getPage(m3u8_url);
            final HlsContainer hlsbest = HlsContainer.findBestVideoByBandwidth(HlsContainer.getHlsQualities(m3u8));
            if (hlsbest != null) {
                final List<M3U8Playlist> playLists = M3U8Playlist.loadM3U8(hlsbest.getStreamURL(), m3u8);
                long estimatedSize = -1;
                for (M3U8Playlist playList : playLists) {
                    if (hlsbest.getBandwidth() > 0) {
                        playList.setAverageBandwidth(hlsbest.getBandwidth());
                        estimatedSize += playList.getEstimatedSize();
                    }
                }
                if (estimatedSize > 0) {
                    link.setDownloadSize(estimatedSize);
                }
                return AvailableStatus.TRUE;
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        } else {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        if (StringUtils.isNotEmpty(progressive_url)) {
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, progressive_url, free_resume, free_maxchunks);
            if (!looksLikeDownloadableContent(dl.getConnection())) {
                br.followConnection(true);
                if (dl.getConnection().getResponseCode() == 403) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
                } else if (dl.getConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
        } else if (StringUtils.isNotEmpty(m3u8_url)) {
            checkFFmpeg(link, "Download a HLS Stream");
            final List<HlsContainer> qualities = HlsContainer.getHlsQualities(br, m3u8_url);
            final HlsContainer best = HlsContainer.findBestVideoByBandwidth(qualities);
            if (best == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl = new HLSDownloader(link, br, best.getStreamURL());
        } else {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return free_maxdownloads;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetPluginGlobals() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}
