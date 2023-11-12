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
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 47485 $", interfaceVersion = 3, names = {}, urls = {})
public class UploadhavenCom extends PluginForHost {
    public UploadhavenCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "uploadhaven.com" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        return buildAnnotationUrls(getPluginDomains());
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/download/([a-f0-9]{32})");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public String getAGBLink() {
        return "https://uploadhaven.com/contact";
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
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    /* Connection stuff */
    private static final boolean FREE_RESUME       = true;
    private static final int     FREE_MAXCHUNKS    = 1;
    private static final int     FREE_MAXDOWNLOADS = 1;

    // private static final boolean ACCOUNT_FREE_RESUME = true;
    // private static final int ACCOUNT_FREE_MAXCHUNKS = 0;
    // private static final int ACCOUNT_FREE_MAXDOWNLOADS = 20;
    // private static final boolean ACCOUNT_PREMIUM_RESUME = true;
    // private static final int ACCOUNT_PREMIUM_MAXCHUNKS = 0;
    // private static final int ACCOUNT_PREMIUM_MAXDOWNLOADS = 20;
    //
    // /* don't touch the following! */
    // private static AtomicInteger maxPrem = new AtomicInteger(1);
    private Browser prepBR(final Browser br) {
        return br;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        prepBR(this.br);
        if (!link.isNameSet()) {
            link.setName(this.getFID(link));
        }
        br.setFollowRedirects(true);
        /**
         * 2021-01-19: Some URLs are restricted to one particular referer-website (mainpage does the job)
         */
        if (!StringUtils.isEmpty(link.getDownloadPassword())) {
            /* Prefer "Download password" --> User defined Referer value */
            br.getHeaders().put("Referer", link.getDownloadPassword());
        } else if (link.getReferrerUrl() != null) {
            br.getHeaders().put("Referer", link.getReferrerUrl());
        } else if (link.getContainerUrl() != null && !this.canHandle(link.getContainerUrl())) {
            br.getHeaders().put("Referer", link.getContainerUrl());
        }
        br.getPage(link.getPluginPatternMatcher());
        if (this.isRefererProtected(br)) {
            /* We can't obtain more file information in this state! */
            if (StringUtils.isEmpty(link.getComment())) {
                link.setComment("This link is referer-protected. Enter the correct referer into the 'Download password' field to be able to download this item.");
            }
            return AvailableStatus.TRUE;
        } else if (br.getHttpConnection().getResponseCode() == 404 || !br.getURL().contains(this.getFID(link))) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filename = br.getRegex("(?i)File\\s*:\\s*([^<>\"]+)<br>").getMatch(0);
        if (StringUtils.isEmpty(filename)) {
            filename = br.getRegex("(?i)>\\s*Download file \\- ([^<>\"]+)\\s*?<").getMatch(0);
        }
        String filesize = br.getRegex("(?i)Size\\s*:\\s+(.*?) +\\s+").getMatch(0);
        if (!StringUtils.isEmpty(filename)) {
            filename = Encoding.htmlDecode(filename).trim();
            link.setName(filename);
        }
        if (filesize != null) {
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        handleDownload(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void handleDownload(final DownloadLink link, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        String dllink = checkDirectLink(link, directlinkproperty);
        if (dllink == null) {
            requestFileInformation(link);
            if (this.isRefererProtected(br)) {
                /**
                 * 2021-01-19: WTF seems like using different referers multiple times in a row, they will always allow the 3rd string as
                 * valid Referer no matter what is used as a string?! E.g. "dfrghe".
                 */
                boolean success = false;
                String userReferer = null;
                for (int i = 0; i <= 2; i++) {
                    br.clearAll();
                    prepBR(this.br);
                    try {
                        userReferer = getUserInput("Enter referer?", link);
                    } catch (final PluginException abortException) {
                        throw new PluginException(LinkStatus.ERROR_FATAL, "Enter referer as password to be able to download this item");
                    }
                    try {
                        new URL(userReferer);
                    } catch (final MalformedURLException e) {
                        logger.info("Entered string is not a valid URL!");
                        continue;
                    }
                    link.setDownloadPassword(userReferer);
                    requestFileInformation(link);
                    if (!this.isRefererProtected(br)) {
                        success = true;
                        break;
                    }
                }
                if (!success) {
                    link.setDownloadPassword(null);
                    throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong referer entered");
                }
                logger.info("Valid referer == " + userReferer);
                /* Is already set in the above loop! */
                // link.setDownloadPassword(userReferer);
                if (StringUtils.isEmpty(link.getComment())) {
                    link.setComment("DownloadPassword == Referer");
                }
            }
            Form dlform = br.getFormbyProperty("id", "form-join");
            if (dlform == null) {
                dlform = br.getFormbyProperty("id", "form-download");
            }
            if (dlform == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            int wait = 5;
            String waitStr = br.getRegex("var\\s*?seconds\\s*?=\\s*?(\\d+)\\s*;").getMatch(0);
            if (waitStr == null) {
                waitStr = br.getRegex("class\\s*=\\s*\"download-timer-seconds.*?\"\\s*>\\s*(\\d+)").getMatch(0);
            }
            if (waitStr != null) {
                wait = Integer.parseInt(waitStr);
            }
            this.sleep((wait + 3) * 1001l, link);
            br.submitForm(dlform);
            dllink = br.getRegex("downloadFile\"\\)\\.attr\\(\"src\",\\s*?\"(https?[^\">]+)").getMatch(0);
            if (StringUtils.isEmpty(dllink)) {
                dllink = br.getRegex("(https?://[A-Za-z0-9\\-]+\\.uploadhaven\\.com/[^\"]+key=[^\"]+)").getMatch(0);
            }
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resumable, maxchunks);
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
        link.setProperty(directlinkproperty, dl.getConnection().getURL().toString());
        dl.startDownload();
    }

    /**
     * @return: true: Special Referer is required to access this URL
     */
    private boolean isRefererProtected(final Browser br) {
        if (br.containsHTML(">\\s*Hotlink protection active")) {
            return true;
        } else {
            return false;
        }
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
                link.removeProperty(property);
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