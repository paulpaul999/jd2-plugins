//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
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

import java.io.IOException;

import jd.PluginWrapper;
import jd.http.URLConnectionAdapter;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

import org.appwork.utils.StringUtils;

@HostPlugin(revision = "$Revision: 47474 $", interfaceVersion = 2, names = { "adrive.com" }, urls = { "" })
public class AdriveCom extends PluginForHost {
    public AdriveCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.adrive.com/terms";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 10;
    }

    private static final String NOCHUNKS = "NOCHUNKS";
    private String              dllink   = null;

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, InterruptedException, PluginException {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        final String mainlink = link.getStringProperty("mainlink");
        if (link.getBooleanProperty("directdl", false)) {
            if (mainlink == null) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            br.getPage(mainlink);
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            this.dllink = br.getRegex("\"(https?://[^\"]+/public/view/[^<>\"]+)").getMatch(0);
            if (!StringUtils.isEmpty(dllink)) {
                URLConnectionAdapter con = null;
                try {
                    con = br.openHeadConnection(this.dllink);
                    if (con.isContentDisposition()) {
                        if (con.getCompleteContentLength() > 0) {
                            link.setVerifiedFileSize(con.getCompleteContentLength());
                        }
                        link.setFinalFileName(Plugin.getFileNameFromDispositionHeader(con));
                    }
                } finally {
                    try {
                        con.disconnect();
                    } catch (final Throwable e) {
                    }
                }
            }
        } else {
            if (mainlink == null) {
                /* This should never happen */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            dllink = link.getStringProperty("directlink");
            URLConnectionAdapter con = null;
            try {
                con = br.openGetConnection(this.dllink);
                if (!this.looksLikeDownloadableContent(con)) {
                    br.followConnection();
                    final String adsPage = br.getRegex("window\\.top\\.location=\"(http[^\"]+)\"").getMatch(0);
                    if (adsPage != null) {
                        /* This will set a special cookie which then allows us to download */
                        br.getPage(adsPage);
                        con = br.openHeadConnection(this.dllink);
                    }
                }
                if (this.looksLikeDownloadableContent(con)) {
                    if (con.getCompleteContentLength() > 0) {
                        link.setVerifiedFileSize(con.getCompleteContentLength());
                    }
                    link.setFinalFileName(Plugin.getFileNameFromDispositionHeader(con));
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
            br.getPage(mainlink);
            String goToLink = br.getRegex("<b>Please go to <a href=\"(/.*?)\"").getMatch(0);
            if (goToLink != null) {
                br.getPage(goToLink);
            }
            if (br.containsHTML("The file you are trying to access is no longer available publicly\\.|The public file you are trying to download is associated with a non\\-valid ADrive") || br.getURL().equals("https://www.adrive.com/login")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            // filename and size are set already by decrypter! resetting to some stored property serves what point?
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        int maxChunks = -10;
        if (link.getBooleanProperty(AdriveCom.NOCHUNKS, false)) {
            maxChunks = 1;
        }
        final boolean resume = true;
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resume, maxChunks);
        final URLConnectionAdapter con = dl.getConnection();
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            if (br.containsHTML("File overlimit")) {
                con.disconnect();
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Too many connections", 10 * 60 * 1000l);
            }
        }
        if (con.getResponseCode() != 200 && con.getResponseCode() != 206) {
            con.disconnect();
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 10 * 60 * 1000l);
        }
        try {
            if (!this.dl.startDownload()) {
                try {
                    if (dl.externalDownloadStop()) {
                        return;
                    }
                } catch (final Throwable e) {
                }
                /* unknown error, we disable multiple chunks */
                if (link.getBooleanProperty(AdriveCom.NOCHUNKS, false) == false) {
                    link.setProperty(AdriveCom.NOCHUNKS, Boolean.valueOf(true));
                    throw new PluginException(LinkStatus.ERROR_RETRY);
                }
            }
        } catch (final PluginException e) {
            // New V2 errorhandling
            /* unknown error, we disable multiple chunks */
            if (e.getLinkStatus() != LinkStatus.ERROR_RETRY && link.getBooleanProperty(AdriveCom.NOCHUNKS, false) == false) {
                link.setProperty(AdriveCom.NOCHUNKS, Boolean.valueOf(true));
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
            throw e;
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }

    @Override
    public void resetPluginGlobals() {
    }
}