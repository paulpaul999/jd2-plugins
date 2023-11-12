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
import jd.plugins.components.PluginJSonUtils;

import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.controller.LazyPlugin;

@HostPlugin(revision = "$Revision: 47474 $", interfaceVersion = 2, names = { "ancensored.com" }, urls = { "https?://(?:www\\.)?ancensored\\.com/clip/([A-Za-z0-9\\-]+)/([A-Za-z0-9\\-]+)/([a-f0-9]{24})" })
public class AncensoredCom extends PluginForHost {
    public AncensoredCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    /* DEV NOTES */
    // Tags: porn plugin
    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    /* Connection stuff */
    private static final boolean free_resume       = true;
    private static final int     free_maxchunks    = 0;
    private static final int     free_maxdownloads = -1;
    private String               dllink            = null;

    @Override
    public String getAGBLink() {
        return "http://ancensored.com/terms-of-use";
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
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(2);
    }

    private String getUrlTitleCleaned(final DownloadLink link) {
        final Regex urlinfo = new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks());
        final String urlTitle = urlinfo.getMatch(0);
        final String username = urlinfo.getMatch(1);
        if (urlTitle != null && username != null) {
            return username.replace("-", " ").trim() + " - " + urlTitle.replace("-", " ").trim();
        } else {
            return null;
        }
    }

    @Override
    public String getMirrorID(final DownloadLink link) {
        String fid = null;
        if (link != null && StringUtils.equals(getHost(), link.getHost()) && (fid = getFID(link)) != null) {
            return getHost() + "://" + fid;
        } else {
            return super.getMirrorID(link);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        link.setFinalFileName(getUrlTitleCleaned(link) + ".mp4");
        dllink = null;
        br.setFollowRedirects(true);
        br.getPage(link.getPluginPatternMatcher());
        if (!br.getURL().contains("/clip/") || br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        dllink = br.getRegex("\"([a-z0-9\\-_]+\\.php\\?file=[^<>\"]*?)\"").getMatch(0);
        if (dllink == null) {
            // this can't actually be downloaded.. but can be used for filename extension
            dllink = br.getRegex("<source type=(\"|'|)\\S+\\1\\s+src=(\"|'|)(.*?)\\2").getMatch(2);
        }
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dllink = Encoding.htmlDecode(dllink);
        {// final dllink...
            final String hash = br.getRegex("data:\\s*\\{hash:\\s*'([a-f0-9]+)'\\}").getMatch(0);
            if (hash != null) {
                final Browser br2 = br.cloneBrowser();
                br2.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
                br2.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br2.getHeaders().put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                br2.postPage("/video/get-link", "hash=" + Encoding.urlEncode(hash));
                final String src = PluginJSonUtils.getJsonValue(br2, "src");
                if (src != null) {
                    dllink = src;
                }
            }
        }
        br.getHeaders().put("Accept", "*/*");
        br.getHeaders().put("Accept-Encoding", "identity;q=1, *;q=0");
        final Browser br2 = br.cloneBrowser();
        // In case the link redirects to the finallink
        br2.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            con = br2.openHeadConnection(this.dllink);
            if (this.looksLikeDownloadableContent(con)) {
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
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
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, free_resume, free_maxchunks);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
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
