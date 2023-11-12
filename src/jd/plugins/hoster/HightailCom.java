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

import java.io.IOException;

import jd.PluginWrapper;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 47482 $", interfaceVersion = 2, names = { "hightail.com" }, urls = { "http(s)?://(?:www\\.)?yousenditdecrypted\\.com/download/[A-Za-z0-9]+" })
public class HightailCom extends PluginForHost {
    private String dllink = null;

    public HightailCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "https://www.hightail.com/aboutus/legal/terms-of-service";
    }

    @SuppressWarnings("deprecation")
    public void correctDownloadLink(DownloadLink link) {
        link.setUrlDownload(link.getDownloadURL().replace("yousenditdecrypted.com/", "hightail.com/"));
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        final String fileid = link.getStringProperty("fileid", null);
        final String spaceid = link.getStringProperty("spaceid", null);
        final String versionid = link.getStringProperty("versionid", null);
        if (fileid == null || spaceid == null || versionid == null) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else {
            if (link.getFinalFileName() == null) {
                link.setFinalFileName(link.getStringProperty("directname", null));
            }
            if (link.getVerifiedFileSize() == -1) {
                link.setVerifiedFileSize(link.getLongProperty("directsize", -1l));
            }
            br.setFollowRedirects(true);
            dllink = "https://download.spaces.hightail.com/api/v1/download/" + spaceid + "/" + fileid + "/" + versionid;
            return AvailableStatus.TRUE;
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        if (this.dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        jd.plugins.decrypter.HighTailComDecrypter.getSessionID(this.br);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error", 10 * 60 * 1000l);
        }
        dl.startDownload();
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}