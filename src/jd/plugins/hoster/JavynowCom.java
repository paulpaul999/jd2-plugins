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

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.appwork.utils.StringUtils;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.plugins.controller.LazyPlugin;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 45841 $", interfaceVersion = 3, names = { "javynow.com" }, urls = { "https?://(?:www\\.)?javynow\\.com/(video(?:\\.php\\?id=|/)[A-Za-z0-9]+.*|player/\\d+/?)" })
public class JavynowCom extends PluginForHost {
    public JavynowCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    /* DEV NOTES */
    // Tags:
    // protocol: no https
    // other:
    /* Connection stuff */
    private static final int    free_maxdownloads = -1;
    private String              dllink            = null;
    private boolean             server_issues     = false;
    private static final String TYPE_EMBED        = "https?://[^/]+/player/(\\d+)/?";
    private static final String TYPE_NORMAL       = "https?://[^/]+/video(?:\\.php\\?id=|/)([A-Za-z0-9]+).*";

    @Override
    public String getAGBLink() {
        return "https://javynow.com/tos.php";
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String linkid = getFID(link);
        if (linkid != null) {
            return this.getHost() + "://" + linkid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        if (link == null || link.getPluginPatternMatcher() == null) {
            return null;
        } else if (link.getPluginPatternMatcher().matches(TYPE_EMBED)) {
            return new Regex(link.getPluginPatternMatcher(), TYPE_EMBED).getMatch(0);
        } else {
            return new Regex(link.getPluginPatternMatcher(), TYPE_NORMAL).getMatch(0);
        }
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        if (!link.isNameSet()) {
            link.setName(this.getFID(link) + ".mp4");
        }
        br.getPage("https://javynow.com/video/" + this.getFID(link) + "/");
        dllink = null;
        server_issues = false;
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(link.getPluginPatternMatcher());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("id=\"deleted\"")) {
            /* 2020-11-19: E.g. <div id="deleted">This video has been deleted.</div> */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String title = br.getRegex("<title>([^<>\"]+) JavyNow</title>").getMatch(0);
        if (!StringUtils.isEmpty(title) && !"no title".equalsIgnoreCase(title)) {
            title = Encoding.htmlDecode(title).trim();
            link.setFinalFileName(title + ".mp4");
        }
        final String cryptedScripts[] = br.getRegex("eval\\s*\\((function\\(p,a,c,k,e,d\\).*?\\{\\}\\))\\)").getColumn(0);
        if (cryptedScripts.length != 0) {
            for (String javascript : cryptedScripts) {
                final ScriptEngineManager manager = JavaScriptEngineFactory.getScriptEngineManager(null);
                final ScriptEngine engine = manager.getEngineByName("javascript");
                String result = null;
                try {
                    engine.eval("var res = " + javascript);
                    result = (String) engine.get("res");
                    dllink = new Regex(result, "<source src=(?:\"|\\')(https?://[^<>\"\\']*?)(?:\"|\\')[^>]*?type=(?:\"|\\')application/x-mpegURL(?:\"|\\')").getMatch(0);
                    if (dllink != null) {
                        break;
                    }
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        if (server_issues) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        } else if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        br.setFollowRedirects(true);
        checkFFmpeg(link, "Download a HLS Stream");
        dl = new HLSDownloader(link, br, dllink);
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
