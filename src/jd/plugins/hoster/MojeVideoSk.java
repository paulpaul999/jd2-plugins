//    jDownloader - Downloadmanager
//    Copyright (C) 2010  JD-Team support@jdownloader.org
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

import org.jdownloader.controlling.filter.CompiledFiletypeFilter;

import jd.PluginWrapper;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

/**
 * @author typek_pb
 */
@HostPlugin(revision = "$Revision: 43164 $", interfaceVersion = 2, names = { "mojevideo.sk" }, urls = { "https?://[\\w\\.]*?mojevideo\\.sk/video/[a-z0-9]+/[_a-z]+\\.html" })
public class MojeVideoSk extends PluginForHost {
    private String dlink = null;

    public MojeVideoSk(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.mojevideo.sk/onas";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void handleFree(DownloadLink link) throws Exception {
        requestFileInformation(link);
        if (dlink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dlink, true, 0);
        if (dl.getConnection().getContentType().contains("html")) {
            dl.getConnection().disconnect();
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        dl.startDownload();
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        /* Website is hosting video content only! */
        link.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
        br.setFollowRedirects(true);
        br.getPage(link.getDownloadURL());
        String filename = br.getRegex("<title>(.*?)</title>").getMatch(0);
        if (null == filename || filename.trim().length() == 0) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        StringBuilder linkSB = new StringBuilder("https://cache01.mojevideo.sk/securevideos69/");
        String dlinkPart = br.getRegex("rvid=(\\d+)").getMatch(0);
        if (null == dlinkPart || dlinkPart.trim().length() == 0) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* If a video is available in multiple qualities, there can be multiple hashes. */
        final String hash = br.getRegex("vHash=\\['([^\\']+)'").getMatch(0);
        final String expires = br.getRegex("vEx='(\\d+)'").getMatch(0);
        if (hash == null || expires == null) {
            /* 2020-11-05: New */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        linkSB.append(dlinkPart);
        linkSB.append(".mp4");
        linkSB.append("?md5=" + hash);
        linkSB.append("&expires=" + expires);
        dlink = linkSB.toString();
        if (dlink == null || dlink.trim().length() == 0) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        filename = filename.trim();
        link.setFinalFileName(filename + ".mp4");
        br.setFollowRedirects(true);
        try {
            if (this.looksLikeDownloadableContent(br.openHeadConnection(dlink))) {
                link.setDownloadSize(br.getHttpConnection().getLongContentLength());
                br.getHttpConnection().disconnect();
                return AvailableStatus.TRUE;
            }
        } finally {
            if (br.getHttpConnection() != null) {
                br.getHttpConnection().disconnect();
            }
        }
        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
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