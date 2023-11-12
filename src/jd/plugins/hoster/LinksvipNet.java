//jDownloader - Downloadmanager
//Copyright (C) 2013  JD-Team support@jdownloader.org
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.MultiHosterManagement;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision: 47892 $", interfaceVersion = 3, names = { "linksvip.net" }, urls = { "" })
public class LinksvipNet extends PluginForHost {
    private static final String                            NICE_HOST                 = "linksvip.net";
    private static final String                            NICE_HOSTproperty         = NICE_HOST.replaceAll("(\\.|\\-)", "");
    /* Connection limits */
    private static final boolean                           ACCOUNT_PREMIUM_RESUME    = true;
    private static final int                               ACCOUNT_PREMIUM_MAXCHUNKS = 0;
    private static final boolean                           USE_API                   = false;
    private final String                                   website_html_loggedin     = "/login/logout\\.php";
    private static HashMap<Account, HashMap<String, Long>> hostUnavailableMap        = new HashMap<Account, HashMap<String, Long>>();
    private Account                                        currentAcc                = null;
    private DownloadLink                                   currentLink               = null;
    private static MultiHosterManagement                   mhm                       = new MultiHosterManagement("linksvip.net");

    public LinksvipNet(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://linksvip.net/premium.html");
    }

    @Override
    public String getAGBLink() {
        return "https://linksvip.net/";
    }

    private Browser prepBRWebsite(final Browser br) {
        br.setCookiesExclusive(true);
        /* 2019-06-05: They've blocked our User-Agent - do NOT use it anymore! */
        // br.getHeaders().put("User-Agent", "JDownloader");
        br.setFollowRedirects(true);
        return br;
    }

    private Browser prepBRAPI(final Browser br) {
        br.setCookiesExclusive(true);
        br.getHeaders().put("User-Agent", "JDownloader");
        br.setFollowRedirects(true);
        return br;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws PluginException {
        return AvailableStatus.UNCHECKABLE;
    }

    private void setConstants(final Account acc, final DownloadLink dl) {
        this.currentAcc = acc;
        this.currentLink = dl;
    }

    @Override
    public boolean canHandle(final DownloadLink link, final Account account) throws Exception {
        if (account == null) {
            return false;
        } else {
            mhm.runCheck(account, link);
            return true;
        }
    }

    @Override
    public void handleFree(DownloadLink downloadLink) throws Exception, PluginException {
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
    }

    @Override
    public void handlePremium(DownloadLink link, Account account) throws Exception {
        /* handle premium should never be called */
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.MULTIHOST };
    }

    @Override
    public void handleMultiHost(final DownloadLink link, final Account account) throws Exception {
        prepBRWebsite(this.br);
        setConstants(account, link);
        mhm.runCheck(currentAcc, currentLink);
        synchronized (hostUnavailableMap) {
            HashMap<String, Long> unavailableMap = hostUnavailableMap.get(account);
            if (unavailableMap != null) {
                Long lastUnavailable = unavailableMap.get(link.getHost());
                if (lastUnavailable != null && System.currentTimeMillis() < lastUnavailable) {
                    final long wait = lastUnavailable - System.currentTimeMillis();
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Host is temporarily unavailable via " + this.getHost(), wait);
                } else if (lastUnavailable != null) {
                    unavailableMap.remove(link.getHost());
                    if (unavailableMap.size() == 0) {
                        hostUnavailableMap.remove(account);
                    }
                }
            }
        }
        login(account, false);
        String dllink = getDllink(link);
        if (StringUtils.isEmpty(dllink)) {
            mhm.handleErrorGeneric(currentAcc, currentLink, "dllinknull", 2, 5 * 60 * 1000l);
        }
        handleDL(account, link, dllink);
    }

    private String getDllink(final DownloadLink link) throws IOException, PluginException {
        String dllink = checkDirectLink(link, NICE_HOSTproperty + "directlink");
        if (dllink == null) {
            if (USE_API) {
                dllink = getDllinkAPI(link);
            } else {
                dllink = getDllinkWebsite(link);
            }
        }
        return dllink;
    }

    private String getDllinkAPI(final DownloadLink link) throws IOException, PluginException {
        return null;
    }

    private String getDllinkWebsite(final DownloadLink link) throws IOException, PluginException {
        br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
        br.postPage("https://" + this.getHost() + "/GetLinkFs", "pass=undefined&hash=undefined&captcha=&link=" + Encoding.urlEncode(link.getDefaultPlugin().buildExternalDownloadURL(link, this)));
        final String dllink = PluginJSonUtils.getJsonValue(this.br, "linkvip");
        return dllink;
    }

    private void handleDL(final Account account, final DownloadLink link, final String dllink) throws Exception {
        link.setProperty(NICE_HOSTproperty + "directlink", dllink);
        try {
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, ACCOUNT_PREMIUM_RESUME, ACCOUNT_PREMIUM_MAXCHUNKS);
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                br.followConnection(true);
                mhm.handleErrorGeneric(currentAcc, currentLink, "unknowndlerror", 2, 5 * 60 * 1000l);
            }
            this.dl.startDownload();
        } catch (final Exception e) {
            link.setProperty(NICE_HOSTproperty + "directlink", Property.NULL);
            throw e;
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
                    if (con.getCompleteContentLength() > 0) {
                        link.setVerifiedFileSize(con.getCompleteContentLength());
                    }
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
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        setConstants(account, null);
        prepBRWebsite(this.br);
        final AccountInfo ai;
        if (USE_API) {
            ai = fetchAccountInfoAPI(account);
        } else {
            ai = fetchAccountInfoWebsite(account);
        }
        return ai;
    }

    public AccountInfo fetchAccountInfoWebsite(final Account account) throws Exception {
        /*
         * 2017-11-29: Lifetime premium not (yet) supported via website mode! But by the time we might need the website version again, they
         * might have stopped premium lifetime sales already as that has never been a good idea for any (M)OCH.
         */
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        br.getPage("https://" + this.getHost() + "/");
        final boolean isPremium = br.containsHTML("class=\"badge\"[^>]+>Premium</span>");
        ArrayList<String> supportedHosts = new ArrayList<String>();
        if (isPremium) {
            account.setType(AccountType.PREMIUM);
            final String expire = br.getRegex("Hạn dùng <span [^>]*?>(\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2} (?:AM|PM))</span>").getMatch(0);
            if (expire != null) {
                /* Only set expiredate if we find it */
                ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "MM/dd/yyyy hh:mm a", Locale.US), br);
            }
            br.getPage("/host-support.html");
            final String[] hostlist = br.getRegex("domain=([^<>\"]+)\"").getColumn(0);
            if (hostlist != null) {
                supportedHosts = new ArrayList<String>(Arrays.asList(hostlist));
            }
            ai.setUnlimitedTraffic();
        } else {
            account.setType(AccountType.FREE);
            ai.setTrafficLeft(0);
        }
        ai.setMultiHostSupport(this, supportedHosts);
        return ai;
    }

    public AccountInfo fetchAccountInfoAPI(final Account account) throws Exception {
        return null;
    }

    private void login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            /* Load cookies */
            br.setCookiesExclusive(true);
            prepBRWebsite(this.br);
            loginWebsite(account, force);
        }
    }

    private void loginWebsite(final Account account, final boolean force) throws Exception {
        try {
            final Cookies cookies = account.loadCookies("");
            if (cookies != null) {
                this.br.setCookies(this.getHost(), cookies);
                if (!force) {
                    /* Do not check cookies */
                    return;
                }
                /*
                 * Even though login is forced first check if our cookies are still valid --> If not, force login!
                 */
                br.getPage("https://" + this.getHost() + "/");
                if (br.containsHTML(website_html_loggedin)) {
                    account.saveCookies(this.br.getCookies(this.getHost()), "");
                    return;
                }
                /* Clear cookies to prevent unknown errors as we'll perform a full login below now. */
                this.br = prepBRWebsite(new Browser());
            }
            br.getPage("https://" + this.getHost() + "/");
            br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
            br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            br.getHeaders().put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            br.postPage("/login/", "auto_login=checked&u=" + Encoding.urlEncode(currentAcc.getUser()) + "&p=" + Encoding.urlEncode(currentAcc.getPass()));
            final String status = PluginJSonUtils.getJson(br, "status");
            if ("1".equals(status)) {
                /* Login should be okay and we should get the cookies now! */
                br.getPage("/login/logined.php");
            }
            if (!br.containsHTML(website_html_loggedin)) {
                throw new AccountInvalidException();
            }
            account.saveCookies(this.br.getCookies(this.getHost()), "");
        } catch (final PluginException e) {
            account.clearCookies("");
            throw e;
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}