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
import jd.parser.html.Form;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

import org.appwork.utils.Regex;
import org.appwork.utils.formatter.SizeFormatter;

@HostPlugin(revision = "$Revision: 47487 $", interfaceVersion = 2, names = { "trainbit.com" }, urls = { "https?://(?:www\\.)?trainbit\\.com/files/(\\d+)/([^/]+)" })
public class TrainbitCom extends PluginForHost {
    public TrainbitCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://trainbit.com/";
    }

    /* Connection stuff */
    private static final boolean FREE_RESUME       = true;
    private static final int     FREE_MAXCHUNKS    = 0;
    private static final int     FREE_MAXDOWNLOADS = 20;
    private String               free_directlink   = null;

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
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        final URLConnectionAdapter con = br.openGetConnection(link.getPluginPatternMatcher());
        if (this.looksLikeDownloadableContent(con)) {
            this.free_directlink = con.getURL().toString();
            link.setVerifiedFileSize(con.getCompleteContentLength());
            link.setFinalFileName(Plugin.getFileNameFromDispositionHeader(con));
            con.disconnect();
            return AvailableStatus.TRUE;
        } else {
            br.followConnection();
        }
        final String filename = new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(1);
        String filesize = br.getRegex("span class=\"badge badge-success\\s*mb-3\">([^<>\"]+)</span>").getMatch(0);
        if (filesize == null) {
            /* 2022-02-25 */
            filesize = br.getRegex("for=\"filesize\"[^>]*>([^<]+)<").getMatch(0);
        }
        if (filename != null) {
            link.setName(Encoding.htmlDecode(filename.trim()));
        }
        if (filesize != null) {
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        /* 2021-01-06: File information can be given even if file is offline! */
        if (br.getHttpConnection().getResponseCode() == 404 || this.br.containsHTML(">\\s*Desired file is removed")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (!br.getURL().contains(this.getFID(link))) {
            /* 2021-01-06: E.g. redirect to somewhere else */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("(?i)Desired folder owner account is disabled")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        handleDownload(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void handleDownload(final DownloadLink link, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        requestFileInformation(link);
        String dllink;
        if (free_directlink != null) {
            dllink = free_directlink;
        } else {
            dllink = checkDirectLink(link, directlinkproperty);
        }
        if (dllink == null) {
            Form dlform = this.br.getFormbyProperty("id", "form1");
            if (dlform == null) {
                dlform = this.br.getForm(0);
            }
            if (dlform == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            this.br.submitForm(dlform);
            dllink = br.getRegex("\"(https?[^<>\"]*?)\" id=\"downloadlink\"").getMatch(0);
            if (dllink == null) {
                dllink = br.getRegex("href=\"(https?[^<>\"]*?)\">Download</a>").getMatch(0);
            }
            if (dllink == null) {
                dllink = br.getRegex("\"(https?://[^/]+/files/\\d+/[^<>\"]*?)\"").getMatch(0);
            }
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resumable, maxchunks);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.setFilenameFix(true);
        link.setProperty(directlinkproperty, dllink);
        dl.startDownload();
    }

    private String checkDirectLink(final DownloadLink link, final String property) {
        String dllink = link.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                con = br2.openHeadConnection(dllink);
                if (this.looksLikeDownloadableContent(con)) {
                    return dllink;
                } else {
                    throw new IOException();
                }
            } catch (final Exception e) {
                logger.log(e);
                return null;
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        }
        return null;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}