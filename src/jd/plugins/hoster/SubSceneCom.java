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

import jd.PluginWrapper;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 48304 $", interfaceVersion = 2, names = { "subscene.com" }, urls = { "https?://(\\w+\\.)?subscene\\.com/(subtitles/[a-z0-9\\-_]+/[a-z0-9\\-_]+/\\d+|[a-z0-9]+/[a-z0-9\\-]+/subtitle\\-\\d+\\.aspx)" })
public class SubSceneCom extends PluginForHost {
    public SubSceneCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "https://subscene.com/";
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(link.getPluginPatternMatcher());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("(>An error occurred while processing your request|>Server Error|>Page Not Found<)")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if ((br.containsHTML("<li class=\"deleted\">")) && (!br.containsHTML("mac"))) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Regex urlinfo = new Regex(link.getPluginPatternMatcher(), "(?i)subtitles/([a-z0-9\\-_]+)/([a-z0-9\\-_]+)/(\\d+)");
        final String language = urlinfo.getMatch(1);
        final String subtitleid = urlinfo.getMatch(2);
        String title = br.getRegex("<strong>Release info[^<>\"]+</strong>([^\"]*?)</li>").getMatch(0);
        if (title == null) {
            title = br.getRegex("<span itemprop=\"name\">([^<>\"]*?)</span>").getMatch(0);
        }
        if (title == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String rlses[] = title.split("\r\n\t\t\t\t\t\t\t<div>");
        if (rlses != null && rlses.length != 0) {
            for (String release : rlses) {
                release = release.trim();
                if (!release.equals("")) {
                    title = release;
                    break;
                }
            }
        }
        title = title.replace("\r", "");
        title = title.replace("\t", "");
        title = title.replace("\n", "");
        title = title.replace("<div>", "").replace("</div>", "");
        title = Encoding.htmlDecode(title).trim();
        if (language != null) {
            title += "_" + language;
        }
        if (subtitleid != null) {
            title += "_" + subtitleid;
        }
        link.setName(title + ".zip");
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        String dllink = br.getRegex("\"(/subtitle/download\\?mac=[^<>\"]*?)\"").getMatch(0);
        if (dllink == null) {
            dllink = br.getRegex("class=\"download\">\\s*<a href=\"(/subtitles?/[^<>\"]*?)\"").getMatch(0);
        }
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // Resume and chunks disabled, not needed for such small files
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, false, 1);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.setFilenameFix(true);
        dl.startDownload();
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
}