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

import java.util.List;
import java.util.Map;

import org.appwork.storage.TypeRef;

import jd.PluginWrapper;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision: 48481 $", interfaceVersion = 3, names = { "wistia.com" }, urls = { "https?://(?:www\\.)?ksmedia\\-gmbh\\.wistia\\.com/medias/[A-Za-z0-9]+|https?://fast\\.wistia\\.net/embed/iframe/[a-z0-9]+|https:?//fast\\.wistia\\.com/embed/medias/[A-Za-z0-9]+(.jsonp?)?" })
public class WistiaCom extends PluginForHost {
    public WistiaCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    /* DEV NOTES */
    // Tags:
    // protocol: no https
    // other:
    /* Extension which will be used if no correct extension is found */
    private static final String  default_Extension = ".mp4";
    /* Connection stuff */
    private static final boolean free_resume       = true;
    private static final int     free_maxchunks    = 0;
    private static final int     free_maxdownloads = -1;
    private String               dllink            = null;
    private boolean              server_issues     = false;

    @Override
    public String getAGBLink() {
        return "https://wistia.com/terms";
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
        return new Regex(link.getPluginPatternMatcher(), "(?:iframe|medias)/([A-Za-z0-9]+)").getMatch(0);
    }

    @SuppressWarnings({ "unchecked" })
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        dllink = null;
        server_issues = false;
        final String fid = getFID(link);
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(String.format("https://fast.wistia.com/embed/medias/%s.jsonp", fid));
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String json = this.br.getRegex("(\\{.+}\\s*);\\s*").getMatch(0);
        if (json == null) {
            /* Hm possibly offline */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if ("true".equals(PluginJSonUtils.getJsonValue(this.br, "error"))) {
            /* Offline */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Map<String, Object> entries = restoreFromString(json, TypeRef.MAP);
        Map<String, Object> media = (Map<String, Object>) entries.get("media");
        final List<Object> ressourcelist = (List<Object>) media.get("assets");
        String filename = (String) media.get("name");
        final String description = (String) media.get("seoDescription");
        if (filename == null) {
            filename = fid;
        }
        /* Find highest quality */
        long sizemax = 0;
        String ext = null;
        for (final Object videoo : ressourcelist) {
            media = (Map<String, Object>) videoo;
            final String type = (String) media.get("type");
            if (type.contains("hls")) {
                /* 2017-10-05: Skip hls for now as we do not need it */
                continue;
            }
            final String dllink_temp = media.get("url").toString();
            final long sizetemp = ((Number) media.get("size")).longValue();
            if (sizetemp > sizemax && dllink_temp != null) {
                ext = (String) media.get("ext");
                dllink = dllink_temp;
                sizemax = sizetemp;
            }
        }
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String dllink_id = new Regex(dllink, "([A-Za-z0-9]+)\\.bin$").getMatch(0);
        if (dllink_id == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // dllink = "https://embedwistia-a.akamaihd.net/deliveries/" + dllink_id + "/file.mp4";
        filename = Encoding.htmlDecode(filename);
        filename = filename.trim();
        if (ext != null && !filename.endsWith(ext)) {
            filename += ".mp4";
        }
        if (description != null && description.length() > 0 && link.getComment() == null) {
            link.setComment(description);
        }
        link.setDownloadSize(sizemax);
        link.setFinalFileName(filename);
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
