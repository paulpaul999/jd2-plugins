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

import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 48082 $", interfaceVersion = 2, names = { "efukt.com" }, urls = { "https?://(?:www\\.)?efukt\\.com/(\\d+[A-Za-z0-9_\\-]+\\.html|out\\.php\\?id=\\d+|view\\.gif\\.php\\?id=\\d+)" })
public class EfuktCom extends PluginForHost {
    public EfuktCom(PluginWrapper wrapper) {
        super(wrapper);
    }

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
        return "http://efukt.com/tos/";
    }

    private final String PATTERN_1 = "https?://(?:www\\.)?efukt\\.com/(\\d+)([A-Za-z0-9_\\-]+)\\.html";
    private final String PATTERN_2 = "https?://(?:www\\.)?efukt\\.com/view\\.gif\\.php\\?id=(\\d+)";

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
        String fid = new Regex(link.getPluginPatternMatcher(), PATTERN_1).getMatch(0);
        if (fid == null) {
            fid = new Regex(link.getPluginPatternMatcher(), PATTERN_2).getMatch(0);
        }
        return fid;
    }

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        final String expectedExt;
        if (link.getPluginPatternMatcher().contains("gif.php")) {
            expectedExt = ".gif";
        } else {
            expectedExt = ".mp4";
        }
        if (!link.isNameSet()) {
            final String fid = this.getFID(link);
            if (link.getPluginPatternMatcher().contains("gif.php")) {
                link.setName(fid + expectedExt);
            } else {
                link.setName(fid + expectedExt);
            }
        }
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(link.getDownloadURL());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (!this.canHandle(br.getURL())) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String title = br.getRegex("<h1\\s*class=\"title\">(.*?)</h1").getMatch(0);
        if (title == null) {
            title = br.getRegex("id=\"movie_title\" style=\"[^<>\"]+\">([^<>]*?)</div>").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex("property=\"og:title\" content=\"([^<>\"]*?)").getMatch(0);
        }
        if (link.getPluginPatternMatcher().contains("view.gif.php")) {
            this.dllink = br.getRegex("<a href=\"(https?://[^\"]+\\.gif)\"[^>]*class=\"image_anchor anchored_item\"").getMatch(0);
        } else {
            dllink = br.getRegex("source\\s*src=\"(https?[^<>\"]*?)\"\\s*type=\"video").getMatch(0);
            if (dllink == null) {
                dllink = br.getRegex("(?:file|url):[\t\n\r ]*?(?:\"|\\')(https?[^<>\"]*?)(?:\"|\\')").getMatch(0);
            }
        }
        if (title != null) {
            title = Encoding.htmlDecode(title);
            title = title.trim();
            link.setName(title + expectedExt);
        }
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                con = br.openGetConnection(dllink);
                handleConnectionErrors(br, con);
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
                if (title != null) {
                    final String ext = Plugin.getExtensionFromMimeTypeStatic(con.getContentType());
                    if (ext != null) {
                        link.setFinalFileName(title + "." + ext);
                    }
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
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, free_resume, free_maxchunks);
        handleConnectionErrors(br, dl.getConnection());
        dl.startDownload();
    }

    private void handleConnectionErrors(final Browser br, final URLConnectionAdapter con) throws Exception {
        if (!this.looksLikeDownloadableContent(con)) {
            br.followConnection(true);
            if (con.getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (con.getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        /* 2022-12-19: Looks like etag can vary so we'll double-check for Content-Length header. */
        final String etag = con.getRequest().getResponseHeader("etag");
        // 2022-12-19: Content-Length: 73003
        if (StringUtils.equalsIgnoreCase(etag, "\"637be5da-11d2b\"") || StringUtils.equalsIgnoreCase(etag, "\"63a05f27-11d2b\"")) {
            con.disconnect();
            /* Dummy video containing text "Video removed" */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (con.getCompleteContentLength() == 73003) {
            con.disconnect();
            /* Dummy video containing text "Video removed" */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
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
