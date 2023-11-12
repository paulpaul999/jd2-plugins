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

import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 47722 $", interfaceVersion = 3, names = { "eroprofile.com" }, urls = { "https?://(?:www\\.)?eroprofile\\.com/m/(?:videos|photos)/view/([A-Za-z0-9\\-_]+)" })
public class EroProfileCom extends PluginForHost {
    public EroProfileCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium();
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    /* DEV NOTES */
    /* Porn_plugin */
    private String dllink = null;

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
    public void setBrowser(Browser br) {
        this.br = br;
        br.setCookie(this.getHost(), "lang", "en");
    }

    @Override
    public String getAGBLink() {
        return "http://www.eroprofile.com/p/help/termsOfUse";
    }

    private static final String VIDEOLINK   = "(?i)https?://(www\\.)?eroprofile\\.com/m/videos/view/[A-Za-z0-9\\-_]+";
    private static Object       LOCK        = new Object();
    private static final String MAINPAGE    = "https://eroprofile.com";
    /*
     * <h1 class="capMultiLine">Access denied</h1> --> This can mean multiple things:
     * "Access denied - This video can only be viewed by the owner.",
     * "Access denied - This video can only be viewed by friends of the owner.", "Access denied - This video can only be viewed by members."
     */
    public static final String  NOACCESS    = "(>\\s*You do not have the required privileges to view this page|>\\s*No access\\s*<|>\\s*Access denied)";
    private static final String PREMIUMONLY = "The video could not be processed";

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        link.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
        br.setFollowRedirects(true);
        br.setReadTimeout(3 * 60 * 1000);
        br.getPage(link.getDownloadURL());
        if (br.containsHTML(NOACCESS)) {
            return AvailableStatus.TRUE;
        }
        final String fid = this.getFID(link);
        if (link.getDownloadURL().matches(VIDEOLINK)) {
            if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("(>\\s*Video not found|>\\s*The video could not be found|<title>\\s*EroProfile</title>)")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.containsHTML(">\\s*Video processing failed")) {
                /* <h1 class="capMultiLine">Video processing failed</h1> */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            String filename = getFilename(br);
            if (filename == null) {
                /* Fallback */
                filename = fid;
            }
            if (br.containsHTML(PREMIUMONLY)) {
                link.setName(filename + ".m4v");
                link.getLinkStatus().setStatusText("This file is only available to premium members");
                return AvailableStatus.TRUE;
            }
            dllink = br.getRegex("file\\s*:\\s*\\'(https?://[^<>\"]*?)\\'").getMatch(0);
            if (dllink == null) {
                dllink = br.getRegex("<source\\s*src\\s*=\\s*(?:'|\")([^<>\"\\']*?)/?(?:'|\")").getMatch(0);
                if (dllink == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            dllink = Encoding.htmlDecode(dllink);
            final String ext = getFileNameExtensionFromString(dllink, ".m4v");
            link.setFinalFileName(filename + ext);
        } else {
            if (br.containsHTML("(>\\s*Photo not found|>\\s*The photo could not be found|<title>\\s*EroProfile\\s*</title>)")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            String filename = getFilename(br);
            if (filename == null) {
                /* Fallback */
                filename = fid;
            }
            dllink = br.getRegex("<\\s*div\\s+class=\"viewPhotoContainer\">\\s*<\\s*a\\s+href=\"((?:https?:)?//[^<>\"]*?)\"").getMatch(0);
            if (dllink == null) {
                dllink = br.getRegex("class\\s*=\\s*\"photoPlayer\"\\s*src\\s*=\\s*\"((?:https?:)?//[^<>\"]*?)\"").getMatch(0);
                if (dllink == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            dllink = Encoding.htmlDecode(dllink);
            final String ext = getFileNameExtensionFromString(dllink, ".jpg");
            link.setFinalFileName(filename + ext);
        }
        final Browser br2 = br.cloneBrowser();
        // In case the link redirects to the finallink
        br2.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            con = br2.openHeadConnection(dllink);
            if (looksLikeDownloadableContent(con)) {
                if (con.getCompleteContentLength() > 0) {
                    link.setDownloadSize(con.getCompleteContentLength());
                }
            } else {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            return AvailableStatus.TRUE;
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
    }

    @Override
    public void handleFree(DownloadLink downloadLink) throws Exception {
        requestFileInformation(downloadLink);
        if (br.containsHTML(NOACCESS)) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        } else if (br.containsHTML(PREMIUMONLY)) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        }
        doFree(downloadLink);
    }

    public void doFree(DownloadLink downloadLink) throws Exception {
        // Resume & chunks works but server will only send 99% of the data if used
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, false, 1);
        if (!looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    public void login(final Browser br, final Account account, final boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null && !force) {
                    br.setCookies(account.getHoster(), cookies);
                    return;
                }
                br.getHeaders().put("Accept-Language", "en-us,en;q=0.5");
                br.setCookie("https://eroprofile.com/", "lang", "en");
                br.setFollowRedirects(false);
                br.getHeaders().put("X_REQUESTED_WITH", "XMLHttpRequest");
                br.postPage("https://www." + account.getHoster() + "/ajax_v1.php", "p=profile&a=login&username=" + Encoding.urlEncode(account.getUser()) + "&password=" + Encoding.urlEncode(account.getPass()));
                if (!isLoggedin()) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.saveCookies(br.getCookies(br.getHost()), "");
            } catch (final PluginException e) {
                account.clearCookies("");
                throw e;
            }
        }
    }

    private boolean isLoggedin() {
        return br.getCookie(MAINPAGE, "memberID", Cookies.NOTDELETEDPATTERN) != null;
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        login(br, account, true);
        ai.setUnlimitedTraffic();
        ai.setStatus("Free Account");
        account.setType(AccountType.FREE);
        return ai;
    }

    @Override
    public void handlePremium(DownloadLink link, Account account) throws Exception {
        requestFileInformation(link);
        login(br, account, false);
        br.setFollowRedirects(false);
        requestFileInformation(link);
        doFree(link);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    public static String getFilename(Browser br) throws PluginException {
        String filename = br.getRegex("<tr><th>Title:</th><td>([^<>\"]*?)</td></tr>").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("<title>(?:EroProfile\\s*-\\s*)?([^<>\"]*?)(?:\\s*-\\s*EroProfile\\s*)?</title>").getMatch(0);
        }
        if (filename != null) {
            filename = Encoding.htmlDecode(filename.trim());
        }
        return filename;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
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
