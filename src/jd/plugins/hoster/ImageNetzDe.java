//jDownloader - Downloadmanager
//Copyright (C) 2010  JD-Team support@jdownloader.org
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
import java.util.ArrayList;
import java.util.List;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.decrypter.ImagenetzDeCrawler;

@HostPlugin(revision = "$Revision: 48287 $", interfaceVersion = 2, names = {}, urls = {})
@PluginDependencies(dependencies = { ImagenetzDeCrawler.class })
public class ImageNetzDe extends PluginForHost {
    public ImageNetzDe(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "https://www.imagenetz.de/agb.php";
    }

    private static List<String[]> getPluginDomains() {
        return ImagenetzDeCrawler.getPluginDomains();
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            /* URLs get added solely via crawler-plugin. */
            ret.add("");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
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
        return new Regex(link.getPluginPatternMatcher(), "https?://[^/]+/(.+)").getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        this.br.setFollowRedirects(true);
        br.getPage(link.getPluginPatternMatcher());
        ImagenetzDeCrawler.checkOffline(br, this.getFID(link));
        return AvailableStatus.TRUE;
    }

    public static void parseFileInfo(final Browser br, final DownloadLink link) throws PluginException {
        final String description = br.getRegex("<strong>\\s*Beschreibung:\\s*</strong>\\s*([^<>\"]+)\\s*<").getMatch(0);
        String filename = br.getRegex("class='dfname'>([^<>\"]+)<").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("data-title=\"([^<>\"]+)\"").getMatch(0);
        }
        if (filename == null && !br.containsHTML("class='dwnin'")) {
            /* E.g. https://www.imagenetz.de/contact */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filesize = br.getRegex("<small>(\\d+([\\.,0-9]+)? MB)</small>").getMatch(0);
        if (filename != null) {
            link.setName(filename.trim());
        }
        if (filesize != null) {
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        if (description != null && StringUtils.isEmpty(link.getComment())) {
            link.setComment(description);
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        final String dllink = br.getRegex("(/files[^<>\"\\']+)").getMatch(0);
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String waitSecondsStr = br.getRegex("d='dlCD'><span>(\\d+)<").getMatch(0);
        if (waitSecondsStr != null) {
            this.sleep(Integer.parseInt(waitSecondsStr) * 1000l, link);
        } else {
            /* 2020-09-09: Static pre-download-waittime */
            this.sleep(3000l, link);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, false, 1);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.setAllowFilenameFromURL(true);
        dl.startDownload();
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}