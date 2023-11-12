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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.plugins.components.antiDDoSForHost;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.decrypter.MyzukaClubCrawler;

@HostPlugin(revision = "$Revision: 48261 $", interfaceVersion = 2, names = {}, urls = {})
@PluginDependencies(dependencies = { MyzukaClubCrawler.class })
public class MyzukaClub extends antiDDoSForHost {
    public MyzukaClub(PluginWrapper wrapper) {
        super(wrapper);
        /* 2020-03-04: Try to avoid IP block: https://board.jdownloader.org/showthread.php?t=80894 */
        this.setStartIntervall(10 * 1000l);
    }

    private static List<String[]> getPluginDomains() {
        return MyzukaClubCrawler.getPluginDomains();
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/Song/(\\d+)");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.AUDIO_STREAMING };
    }

    @Override
    public String getAGBLink() {
        return "http://myzcloud.me/Contacts";
    }

    private String getContentURL(final DownloadLink link) {
        final String oldHost = Browser.getHost(link.getPluginPatternMatcher(), false);
        return link.getPluginPatternMatcher().replaceFirst(Pattern.quote(oldHost), this.getHost());
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
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    protected boolean useRUA() {
        /* 2020-02-26: Try to prevent IP bans. */
        return true;
    }

    /** 2020-02-27: This service is blocking all but turkish IPs! Turkish Proxy/VPN required or every request will return 404! */
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        final String extDefault = ".mp3";
        if (!link.isNameSet()) {
            link.setName(this.getFID(link) + extDefault);
        }
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.setAllowedResponseCodes(new int[] { 500 });
        getPage(getContentURL(link));
        if (br.getHttpConnection().getResponseCode() == 500 || br.getHttpConnection().getResponseCode() == 400) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("Трек удален по просьбе правообладателя")) {
            /* Abused */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.getHttpConnection().getResponseCode() == 403) {
            /* Downloadlimit reached */
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Server error 403 (limit reached?)", 2 * 60 * 60 * 1000l);
        }
        String filename = br.getRegex("<h1>([^<>\"]*?)</h1>").getMatch(0);
        final String filesize = br.getRegex("(\\d{1,2},\\d{1,2})\\s*Мб").getMatch(0);
        if (filename != null) {
            link.setFinalFileName(Encoding.htmlDecode(filename).trim() + extDefault);
        }
        if (filesize != null) {
            link.setDownloadSize(SizeFormatter.getSize(filesize + "MB"));
        }
        return AvailableStatus.TRUE;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        String dllink = br.getRegex("\"(/Song/dl/[^<>\"]*?)\"").getMatch(0);
        if (dllink == null) {
            dllink = br.getRegex("\"(/Song/Download/[^<>\"]*?)\"").getMatch(0);
        }
        if (dllink == null) {
            dllink = br.getRegex("\"(/Song/Play/[^<>\"]*?)\"").getMatch(0);
            if (dllink == null) {
                logger.info("Could not find downloadurl, trying to get streamurl");
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.getPage("/Song/GetPlayFileUrl/" + new Regex(link.getDownloadURL(), this.getSupportedLinks()).getMatch(0));
                if (br.getHttpConnection().getResponseCode() == 403) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403 - file not downloadable?", 3 * 60 * 60 * 1000l);
                }
                dllink = br.getRegex("\"(https?://[^<>\"]*?)\"").getMatch(0);
                if (dllink != null) {
                    logger.info("Found streamurl");
                    dllink = Encoding.unicodeDecode(dllink);
                } else {
                    logger.warning("Failed to find streamurl");
                }
            }
        }
        if (dllink != null) {
            if (Encoding.isHtmlEntityCoded(dllink)) {
                dllink = Encoding.htmlDecode(dllink);
            }
            br.setFollowRedirects(false);
            br.getPage(dllink);
            dllink = br.getRedirectLocation();
        }
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /* 2020-02-27: Not required anymore */
        // dllink = dllink + "?t=" + System.currentTimeMillis();
        br.setFollowRedirects(true);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 1);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            if (dl.getConnection().getContentType().contains("gif")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error", 15 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403 (limit reached?)", 5 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 5 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 500) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 500", 30 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        } else if (StringUtils.containsIgnoreCase(dl.getConnection().getContentType(), "gif")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error", 15 * 60 * 1000l);
        }
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
    public void resetDownloadlink(final DownloadLink link) {
    }
}