//    jDownloader - Downloadmanager
//    Copyright (C) 2013  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.util.List;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.jdownloader.downloader.hds.HDSDownloader;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.plugins.components.hds.HDSContainer;
import org.jdownloader.plugins.components.hls.HlsContainer;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 48194 $", interfaceVersion = 2, names = { "tf1.fr" }, urls = { "https?://(?:www\\.)?(wat\\.tv/video/.*?|tf1\\.fr/.+/videos/[A-Za-z0-9\\-_]+)\\.html" })
public class Tf1Fr extends PluginForHost {
    public Tf1Fr(final PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.wat.tv/cgu";
    }

    @Override
    public String rewriteHost(String host) {
        if ("wat.tv".equals(getHost())) {
            if (host == null || "tf1.fr".equals(host)) {
                return "tf1.fr";
            }
        }
        return super.rewriteHost(host);
    }

    /* 2016-04-22: Changed domain from wat.tv to tf1.fr - everything else mostly stays the same */
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        String filename = null;
        setBrowserExclusive();
        br.setCustomCharset("utf-8");
        br.setFollowRedirects(true);
        br.setAllowedResponseCodes(410);
        br.getPage(link.getPluginPatternMatcher());
        if (br.getHttpConnection().getResponseCode() == 404 || br.getHttpConnection().getResponseCode() == 410) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (!this.canHandle(this.br.getURL())) {
            /* E.g. individual episode of series is not available anymore --> Redirect to series overview */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        filename = br.getRegex("<meta name=\"name\" content=\"(.*?)\"").getMatch(0);
        if (StringUtils.isEmpty(filename)) {
            filename = br.getRegex("\\'premium:([^<>\"\\']*?)\\'").getMatch(0);
            if (StringUtils.isEmpty(filename)) {
                filename = br.getRegex("<meta property=\"og:title\" content=\"(.*?)\"").getMatch(0);
                if (StringUtils.isEmpty(filename)) {
                    filename = br.getRegex("<title\\s*[^>]*>\\s*(.*?)\\s*(\\|\\s*TF1\\s*)?</title>").getMatch(0);
                }
            }
        }
        if (filename == null || filename.equals("")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (filename.endsWith(" - ")) {
            filename = filename.replaceFirst(" \\- $", "");
        }
        filename = Encoding.htmlDecode(filename.trim());
        link.setName(filename + ".mp4");
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        String video_id = br.getRegex("data-watid=\"(\\d+)\"").getMatch(0);
        if (video_id == null) {
            video_id = br.getRegex("<meta property=\"og:video(:secure_url)?\" content=\"[^\"]+(\\d{6,8})\">").getMatch(1);
            if (video_id == null) {
                video_id = br.getRegex("xtpage = \"[^;]+video\\-(\\d{6,8})\";").getMatch(0);
                if (video_id == null) {
                    video_id = br.getRegex("replay_(\\d{6,8})").getMatch(0);
                }
            }
        }
        if (video_id == null) {
            final Browser br2 = br.cloneBrowser();
            final String slug = new Regex(link.getPluginPatternMatcher(), "/videos/(.*?)\\.html").getMatch(0);
            final String programSlug = new Regex(link.getPluginPatternMatcher(), ".*/(.*?)/videos/").getMatch(0);
            br2.getPage("https://www.tf1.fr/graphql/web?id=cb31e88def68451cba035272e5d7f987cbff7d273fb6132d6d662cf684f8de53&variables={%22slug%22:%22" + slug + "%22,%22programSlug%22:%22" + programSlug + "%22}");
            video_id = br2.getRegex("\"streamId\"\\s*:\\s*\"(\\d{6,8})").getMatch(0);
        }
        final Browser br2 = br.cloneBrowser();
        br2.getPage("http://www.wat.tv/get/webhtml/" + video_id);
        String finallink = null;
        try {
            final Map<String, Object> response = restoreFromString(br2.getRequest().getHtmlCode(), TypeRef.MAP);
            finallink = response != null ? (String) response.get("hls") : null;
        } catch (final Throwable ignore) {
        }
        if (finallink == null) {
            /**
             * 2022-01-11: Usage of other endpoint required but this will only return MPD with split video/audio, see also:
             * https://svn.jdownloader.org/issues/89353 </br>
             * New endpoint: https://mediainfo.tf1.fr/mediainfocombo/<video_id>
             */
            throw new PluginException(LinkStatus.ERROR_FATAL, "Unsupported streaming type MPD with split video audio");
        } else if (finallink.startsWith("rtmp")) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "Unsupported streaming protocol");
        } else if (finallink.contains(".f4m?")) {
            // HDS
            br.getPage(finallink);
            final List<HDSContainer> all = HDSContainer.getHDSQualities(br);
            if (all == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            } else {
                final HDSContainer read = HDSContainer.read(link);
                final HDSContainer hit;
                if (read != null) {
                    hit = HDSContainer.getBestMatchingContainer(all, read);
                } else {
                    hit = HDSContainer.findBestVideoByResolution(all);
                }
                if (hit == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                } else {
                    hit.write(link);
                    final HDSDownloader dl = new HDSDownloader(link, br, hit.getFragmentURL());
                    this.dl = dl;
                    dl.setEstimatedDuration(hit.getDuration());
                    dl.startDownload();
                }
            }
        } else if (finallink.contains(".m3u8")) {
            // HLS
            checkFFmpeg(link, "Download a HLS Stream");
            /* 2021-01-21: This "trick" or whatever it was doesn't work anymore */
            // final String m3u8 = finallink.replaceAll("(&(min|max)_bitrate=\\d+)", "");
            // br.getPage(m3u8);
            br.getPage(finallink);
            if (br.getHttpConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "GEO-blocked and/or account required");
            }
            final List<HlsContainer> qualities = HlsContainer.getHlsQualities(br);
            final HlsContainer best = HlsContainer.findBestVideoByBandwidth(qualities);
            if (best == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl = new HLSDownloader(link, br, best.getDownloadurl());
            dl.startDownload();
        } else {
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, finallink, true, 0);
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                br.followConnection(true);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl.startDownload();
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
        if (link != null) {
            HDSContainer.clear(link);
        }
    }

    @Override
    public void resetPluginGlobals() {
    }
}