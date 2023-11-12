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

import java.io.IOException;
import java.util.HashSet;

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
import jd.plugins.components.SiteType.SiteTemplate;

import org.appwork.utils.StringUtils;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.plugins.controller.LazyPlugin;

@HostPlugin(revision = "$Revision: 47477 $", interfaceVersion = 2, names = { "fux.com", "4tube.com", "porntube.com", "pornerbros.com" }, urls = { "https?://(?:www\\.)?fux\\.com/(?:videos?|embed)/\\d+/?(?:[\\w-]+)?", "https?://(?:www\\.)?4tube\\.com/(?:embed|videos)/\\d+/?(?:[\\w-]+)?|https?://m\\.4tube\\.com/videos/\\d+/?(?:[\\w-]+)?", "https?://(?:www\\.)?(?:porntube\\.com/videos/[a-z0-9\\-]+_\\d+|embed\\.porntube\\.com/\\d+|porntube\\.com/embed/\\d+)", "https?://(?:www\\.)?(?:pornerbros\\.com/videos/[a-z0-9\\-]+_\\d+|embed\\.pornerbros\\.com/\\d+|pornerbros\\.com/embed/\\d+)" })
public class FuxCom extends PluginForHost {
    public FuxCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    /* DEV NOTES */
    /* Porn_plugin */
    /* tags: fux.com, porntube.com, 4tube.com, pornerbros.com */
    @Override
    public String getAGBLink() {
        return "http://www.fux.com/legal/tos";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    public void correctDownloadLink(final DownloadLink link) {
        final String protocol_of_mobile_URL = new Regex(link.getPluginPatternMatcher(), "^(https?://)m\\..+").getMatch(0);
        if (protocol_of_mobile_URL != null) {
            /* E.g. 4tube.com, Change mobile-website-URL --> Desktop URL */
            link.setPluginPatternMatcher(link.getPluginPatternMatcher().replaceAll(protocol_of_mobile_URL + "m.", protocol_of_mobile_URL));
        }
        final String linkid = this.getFID(link);
        if (link.getPluginPatternMatcher().matches(".+4tube\\.com/embed/\\d+")) {
            /* Special case! */
            link.setPluginPatternMatcher(String.format("https://www.4tube.com/videos/%s/dummytext", linkid));
        } else if (link.getPluginPatternMatcher().matches(".+(porntube|pornerbros)\\.com/embed/\\d+")) {
            /* Special case! */
            final String host = link.getHost();
            link.setPluginPatternMatcher(String.format("https://www.%s/videos/dummytext_%s", host, linkid));
        } else {
            link.setPluginPatternMatcher(link.getPluginPatternMatcher().replace("/embed/", "/video/"));
        }
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
        String fid = new Regex(link.getPluginPatternMatcher(), "/(?:videos?|embed)/(\\d+)").getMatch(0);
        if (fid == null) {
            /* E.g. porntube.com & pornerbros.com OLD embed linkformat */
            fid = new Regex(link.getPluginPatternMatcher(), "https?://embed\\.[^/]+/(\\d+)").getMatch(0);
        }
        if (fid == null) {
            /* E.g. pornerbros.com */
            fid = new Regex(link.getPluginPatternMatcher(), "_(\\d+)$").getMatch(0);
        }
        return fid;
    }

    private String  dllink        = null;
    private boolean server_issues = false;

    // private boolean isEmbed(final String url) {
    // return url.matches(".+(embed\\..+|/embed/).+");
    // }
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        dllink = null;
        link.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getHeaders().put("Accept-Language", "en-AU,en;q=0.8");
        br.getPage(link.getPluginPatternMatcher());
        if (br.getHttpConnection().getResponseCode() == 404 || br.getURL().matches(".+/videos?\\?error=\\d+")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final boolean mightBeOffline = br.containsHTML("This video is no longer available");
        if (!br.getURL().contains(this.getFID(link))) {
            /* 2020-07-31: On offline (?) they sometimes do random redirects to other content (?) */
            logger.info("Content might have changed");
        }
        /*
         * 2019-04-29: Always use 'Fallback filename' as it works for all supported websites and will usually give us a 'good looking'
         * filename.
         */
        String filename = br.getRegex("property\\s*=\\s*\"og:title\"\\s*content\\s*=\\s*\"(.*?)\\s*(\\|\\s+.*?)?\"").getMatch(0);
        if (filename == null) {
            filename = getFallbackFilename(link);
        }
        final String source;
        final String b64 = br.getRegex("window\\.INITIALSTATE = \\'([^\"\\']+)\\'").getMatch(0);
        if (b64 != null) {
            /* 2018-11-14: fux.com: New */
            source = Encoding.htmlDecode(Encoding.Base64Decode(b64));
        } else {
            source = br.toString();
        }
        /* 2019-04-29: fux.com */
        String mediaID = new Regex(source, "\"mediaId\":([0-9]{2,})").getMatch(0);
        if (mediaID == null) {
            /* 2019-04-29: E.g. 4tube.com and all others (?) */
            mediaID = getMediaid(this.br);
        }
        String availablequalities = br.getRegex("\\((\\d+)\\s*,\\s*\\d+\\s*,\\s*\\[([0-9,]+)\\]\\);").getMatch(0);
        if (availablequalities != null) {
            availablequalities = availablequalities.replace(",", "+");
        } else {
            /* Fallback */
            availablequalities = "1080+720+480+360+240";
        }
        if (mediaID == null || filename == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
        br.getHeaders().put("Origin", "http://www.fux.com");
        final boolean newWay = true;
        final String host = br.getHost();
        if (host.equals("fux.com")) {
            if (newWay) {
                /* 2019-04-29 */
                br.postPage("https://token.fux.com/" + mediaID + "/desktop/" + availablequalities, "");
            } else {
                /* Leave this in as it might still be usable as a fallback in the future! */
                br.postPage("https://tkn.fux.com/" + mediaID + "/desktop/" + availablequalities, "");
            }
        } else {
            br.postPage("https://token." + host + "/" + mediaID + "/desktop/" + availablequalities, "");
        }
        // seems to be listed in order highest quality to lowest. 20130513
        dllink = getDllink();
        filename = Encoding.htmlDecode(filename.trim());
        String ext = ".mp4";
        if (!StringUtils.isEmpty(dllink)) {
            dllink = Encoding.htmlDecode(dllink);
            if (dllink.contains(".m4v")) {
                ext = ".m4v";
            } else if (dllink.contains(".mp4")) {
                ext = ".mp4";
            } else {
                ext = ".flv";
            }
        }
        link.setFinalFileName(filename + ext);
        if (dllink == null) {
            if (mightBeOffline) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        // In case the link redirects to the finallink
        br.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            con = br.openHeadConnection(dllink.trim());
            if (this.looksLikeDownloadableContent(con)) {
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
            } else {
                server_issues = true;
            }
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
        return AvailableStatus.TRUE;
    }

    private String getFilenameURL(final String url) {
        String filename_url = new Regex(url, "/videos/\\d+/(.+)").getMatch(0);
        if (filename_url == null) {
            /* E.g. pornerbros.com */
            filename_url = new Regex(url, "/videos/(.+)_\\d+$").getMatch(0);
        }
        return filename_url;
    }

    private String getFallbackFilename(final DownloadLink dl) {
        final String fid = this.getFID(dl);
        String filename_url = getFilenameURL(dl.getPluginPatternMatcher());
        /*
         * Sites will usually redirect to URL which contains title so if the user adds a short URL, there is still a chance to get a
         * filename via URL!
         */
        // final String filename_url_browser = getFilenameURL(br.getURL());
        /* URL-filename may also be present in HTML */
        String filename_url_browser_html = br.getRegex("/videos?/\\d+/([A-Za-z0-9\\-_]+)").getMatch(0);
        if (filename_url_browser_html == null) {
            /* E.g. porntube.com & pornerbros.com */
            filename_url_browser_html = new Regex(br.getURL(), "/videos?/([A-Za-z0-9\\-_]+)_\\d+$").getMatch(0);
        }
        if (filename_url == null) {
            filename_url = getFilenameURL(br.getURL());
        }
        final String fallback_filename;
        if (filename_url_browser_html != null) {
            /* 2020-07-31: Prefer filename of current browser URL */
            fallback_filename = filename_url_browser_html;
        } else if (filename_url != null) {
            /* Title in current browser URL is longer than in the user-added URL --> Use that */
            fallback_filename = fid + "_" + filename_url_browser_html;
        } else {
            fallback_filename = fid;
        }
        return fallback_filename;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        if (server_issues) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        } else if (StringUtils.isEmpty(dllink)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = new jd.plugins.BrowserAdapter().openDownload(br, link, dllink, true, 0);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    final String getDllink() {
        String finallink = null;
        final HashSet<String> tested = new HashSet<String>();
        final String[] qualities = new String[] { "1080", "720", "480", "360", "240" };
        for (final String quality : qualities) {
            if (br.containsHTML("\"" + quality + "\"")) {
                final String link = br.getRegex("\"" + quality + "\":\\{\"status\":\"success\",\"token\":\"(http[^<>\"]*?)\"").getMatch(0);
                if (link != null && tested.add(link) && checkDirectLink(link) != null) {
                    finallink = link;
                    break;
                }
            }
        }
        /* Hm probably this is only needed if only one quality exists */
        if (finallink == null) {
            final String link = br.getRegex("\"token\":\"(https?://[^<>\"]*?)\"").getMatch(0);
            if (link != null && tested.add(link) && checkDirectLink(link) != null) {
                finallink = link;
            }
        }
        return finallink;
    }

    public static String getMediaid(final Browser br) throws IOException {
        return getMediaid(br, br.toString());
    }

    public static String getMediaid(final Browser br, final String source) throws IOException {
        final Regex info = new Regex(source, "\\.ready\\(function\\(\\) \\{embedPlayer\\((\\d+), \\d+, \\[(.*?)\\],");
        String mediaID = info.getMatch(0);
        if (mediaID == null) {
            mediaID = new Regex(source, "\\$\\.ajax\\(url, opts\\);[\t\n\r ]+\\}[\t\n\r ]+\\}\\)\\((\\d+),").getMatch(0);
        }
        if (mediaID == null) {
            mediaID = new Regex(source, "id=\"download\\d+p\" data\\-id=\"(\\d+)\"").getMatch(0);
        }
        if (mediaID == null) {
            // just like 4tube/porntube/fux....<script id="playerembed" src...
            final String embed = new Regex(source, "/js/player/(?:embed|web)/\\d+(?:\\.js)?").getMatch(-1);
            if (embed != null) {
                br.getPage(embed);
                mediaID = br.getRegex("\\((\\d+)\\s*,\\s*\\d+\\s*,\\s*\\[([0-9,]+)\\]\\);").getMatch(0); // $.ajax(url,opts);}})(
            }
        }
        return mediaID;
    }

    private String checkDirectLink(final String directlink) {
        if (directlink != null) {
            try {
                final Browser br2 = br.cloneBrowser();
                URLConnectionAdapter con = br2.openHeadConnection(directlink);
                if (this.looksLikeDownloadableContent(con)) {
                    return directlink;
                }
                con.disconnect();
            } catch (final Exception e) {
            }
        }
        return null;
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.UnknownPornScript6;
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