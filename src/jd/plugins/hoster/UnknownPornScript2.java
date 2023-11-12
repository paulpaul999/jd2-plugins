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
import jd.plugins.components.SiteType.SiteTemplate;

@HostPlugin(revision = "$Revision: 48109 $", interfaceVersion = 3, names = { "pornmaki.com", "amalandtube.com", "recordedcams.com", "trannyx.com", "nakedtube.com", "shockingmovies.com", "fapbox.com", "preggoporn.tv", "rookiepornvideos.com", "chickswithdicks.video", "amateurgaymovies.com", "watchtwinks.com", "sextube.desi", "chopsticktube.com", "tubechica.com", "bisexualmantube.com", "gaybearflix.com", "realthaisluts.com", "realteenmovies.com", "ridemycocktube.com", "habibiporn.com", "xlactating.com", "tubeenema.com", "straponfuckvideos.com", "3dtube.xxx", "fishnetfucking.com", "milf.dk", "sluttywifelovers.com", "cumloadedgirls.com", "skeetporntube.com", "acuptube.com", "chubbycut.com", "nopixeljaps.com", "pinaysmut.com", "sexyfeet.tv", "dirtypantyporn.com", "xxxuniformporn.com", "smartgirlstube.com", "facialcumtube.com", "freeyogaporn.com", "domsubtube.com", "bdsmpornflix.com",
        "machinefucked.me", "toyspornmovies.com", "tastypussytube.com", "mylesbianporn.com", "inkedgirlsporn.com", "freedptube.com", "tinydicktube.com", "gropingtube.com", "naughtyhighschoolporn.com", "realityxxxtube.com", "onlyhairyporn.com" }, urls = { "https?://(?:www\\.)?pornmaki\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?amalandtube\\.com/free/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?recordedcams\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?trannyx\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?nakedtube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?shockingmovies\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?fapbox\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?preggoporn\\.tv/video/[A-Za-z0-9\\-]+\\-\\d+\\.html",
                "https?://(?:www\\.)?rookiepornvideos\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?chickswithdicks\\.video/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?amateurgaymovies\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?watchtwinks\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?sextube\\.desi/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?chopsticktube\\.com/video/\\d+/[A-Za-z0-9\\-]+\\.html", "https?://(?:www\\.)?tubechica\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?bisexualmantube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?gaybearflix\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?realthaisluts\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?realteenmovies\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html",
                "https?://(?:www\\.)?ridemycocktube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?habibiporn\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?xlactating\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?tubeenema\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?straponfuckvideos\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?3dtube\\.xxx/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?fishnetfucking\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?milf\\.dk/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?sluttywifelovers\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?cumloadedgirls\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?skeetporntube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html",
                "https?://(?:www\\.)?acuptube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?chubbycut\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?nopixeljaps\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?pinaysmut\\.com/videos?/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?sexyfeet\\.tv/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?dirtypantyporn\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?xxxuniformporn\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?smartgirlstube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?facialcumtube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?freeyogaporn\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?domsubtube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html",
                "https?://(?:www\\.)?bdsmpornflix\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?machinefucked\\.me/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?toyspornmovies\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?tastypussytube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?mylesbianporn\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?inkedgirlsporn\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?freedptube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?tinydicktube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?gropingtube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?naughtyhighschoolporn\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html", "https?://(?:www\\.)?realityxxxtube\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html",
                "https?://(?:www\\.)?onlyhairyporn\\.com/video/[A-Za-z0-9\\-]+\\-\\d+\\.html" })
public class UnknownPornScript2 extends PluginForHost {
    public UnknownPornScript2(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    /* DEV NOTES */
    /* Porn_plugin */
    /* V0.1 */
    // other:
    /* Extension which will be used if no correct extension is found */
    private static final String  type_1            = "^(?i)https?://(?:www\\.)?[^/]+/(?:videos?|free)/[A-Za-z0-9\\-]+\\-\\d+\\.html$";
    /* E.g. chopsticktube.com */
    private static final String  type_2            = "^(?i)https?://(?:www\\.)?[^/]+/video/\\d+/[A-Za-z0-9\\-]+\\-\\d+\\.html$";
    /* Connection stuff */
    private static final boolean free_resume       = true;
    private static final int     free_maxchunks    = 0;
    private static final int     free_maxdownloads = -1;
    private String               dllink            = null;

    @Override
    public String getAGBLink() {
        return "http://pornmaki.com/static/terms";
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        return requestFileInformation(link, false);
    }

    private AvailableStatus requestFileInformation(final DownloadLink link, final boolean isDownload) throws IOException, PluginException {
        dllink = null;
        final String titleFromURL;
        if (link.getPluginPatternMatcher().matches(type_1)) {
            titleFromURL = new Regex(link.getPluginPatternMatcher(), "/(?:videos?|free)/([a-z0-9\\-]+)\\-\\d+\\.html").getMatch(0).replace("-", " ");
        } else {
            titleFromURL = new Regex(link.getPluginPatternMatcher(), "/video/\\d+/([a-z0-9\\-]+)\\.html").getMatch(0).replace("-", " ");
        }
        final String extDefault = ".mp4";
        if (!link.isNameSet()) {
            link.setName(titleFromURL + extDefault);
        }
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(link.getPluginPatternMatcher());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String title = this.br.getRegex("<h2 class=\"page\\-title\">([^<>\"]*?)</h2>").getMatch(0);
        if (title == null) {
            title = titleFromURL;
        }
        final String html_clip = this.br.getRegex("urls\\.push\\(\\{(.*?)\\}\\);").getMatch(0);
        if (html_clip != null) {
            /* TODO: Check if some sites can have multiple formats/qualities --> Always find the highest bitrate! */
            dllink = new Regex(html_clip, "file:\"(http[^<>\"]*?)\"").getMatch(0);
        } else {
            dllink = br.getRegex("file:\"(http[^<>\"]*?)\"").getMatch(0);
        }
        if (title != null) {
            title = Encoding.htmlDecode(title);
            title = title.trim();
            final String ext = dllink != null ? (getFileNameExtensionFromString(dllink, extDefault)) : extDefault;
            link.setFinalFileName(this.correctOrApplyFileNameExtension(title, ext));
        }
        if (!StringUtils.isEmpty(dllink) && !isDownload) {
            URLConnectionAdapter con = null;
            try {
                con = br.openHeadConnection(this.dllink);
                handleConnectionErrors(br, con);
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
                final String ext = Plugin.getExtensionFromMimeTypeStatic(con.getContentType());
                if (ext != null) {
                    link.setFinalFileName(this.correctOrApplyFileNameExtension(title, "." + ext));
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
        if (StringUtils.isEmpty(dllink)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, free_resume, free_maxchunks);
        handleConnectionErrors(br, dl.getConnection());
        dl.startDownload();
    }

    private void handleConnectionErrors(final Browser br, final URLConnectionAdapter con) throws PluginException, IOException {
        if (!this.looksLikeDownloadableContent(con)) {
            br.followConnection(true);
            if (con.getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (con.getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Video broken?");
            }
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return free_maxdownloads;
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.UnknownPornScript2;
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
