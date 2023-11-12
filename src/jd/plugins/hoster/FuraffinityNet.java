//jDownloader - Downloadmanager
//Copyright (C) 2017  JD-Team support@jdownloader.org
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
import java.util.Locale;

import org.appwork.utils.StringUtils;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountRequiredException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 48308 $", interfaceVersion = 3, names = { "furaffinity.net" }, urls = { "https?://(?:www\\.)?furaffinity\\.net/view/(\\d+)" })
public class FuraffinityNet extends PluginForHost {
    public FuraffinityNet(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://www.furaffinity.net/register");
        /* 2020-08-19: Try to avoid 503 errors */
        this.setStartIntervall(1000l);
    }

    /* DEV NOTES */
    // Tags:
    // other:
    /* Connection stuff */
    private static final boolean free_resume                = true;
    private static final int     free_maxchunks             = 0;
    private static final int     free_maxdownloads          = -1;
    private String               dllink                     = null;
    private boolean              accountRequired            = false;
    private boolean              enableAdultContentRequired = false;

    @Override
    public String getAGBLink() {
        return "https://www.furaffinity.net/tos";
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

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        /* Website is hosting mostly picture content but sometimes also audio snippets. */
        // link.setMimeHint(CompiledFiletypeFilter.ImageExtensions.JPG);
        if (!link.isNameSet()) {
            link.setName(this.getFID(link));
        }
        dllink = null;
        br.setFollowRedirects(true);
        br.setAllowedResponseCodes(new int[] { 503 });
        br.getPage(link.getPluginPatternMatcher());
        if (br.getHttpConnection().getResponseCode() == 503) {
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Server error 503 too many requests", 5 * 60 * 1000l);
        } else if (br.getHttpConnection().getResponseCode() == 404 || !br.getURL().contains(this.getFID(link)) || br.containsHTML("<(title|h2)>\\s*System Error")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML(">\\s*The owner of this page has elected to make it available to registered users only")) {
            /* Content is online but we can't view/download it! */
            this.accountRequired = true;
            return AvailableStatus.TRUE;
        } else if (br.containsHTML(">\\s*This submission contains Mature or Adult content")) {
            /* Content is online but we can't view/download it! */
            this.enableAdultContentRequired = true;
            return AvailableStatus.TRUE;
        }
        dllink = br.getRegex("class=\"download fullsize\"><a href=\"([^\"]+)").getMatch(0);
        if (dllink == null) {
            dllink = br.getRegex("data-fullview-src=\"([^\"]+)").getMatch(0);
        }
        if (dllink == null) {
            /* 2021-02-25 */
            dllink = br.getRegex("\"([^\"]+/download/[^\"]+)\"").getMatch(0);
        }
        String filename = dllink != null ? Plugin.getFileNameFromURL(br.getURL(dllink)) : null;
        if (filename != null) {
            link.setFinalFileName(filename);
        } else {
            /* Fallback */
            link.setName(this.getFID(link) + ".jpg");
        }
        if (!StringUtils.isEmpty(dllink) && link.getView().getBytesTotal() <= 0) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                con = br.openHeadConnection(dllink);
                handleConnectionErrors(br, con);
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
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
    protected boolean looksLikeDownloadableContent(final URLConnectionAdapter con) {
        final boolean expectsTextContent = con.getURL().toExternalForm().toLowerCase(Locale.ENGLISH).contains(".txt");
        if (expectsTextContent && con.getContentType().contains("text")) {
            return true;
        } else {
            return super.looksLikeDownloadableContent(con);
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        this.doFree(link, null);
    }

    private void doFree(final DownloadLink link, final Account account) throws Exception, PluginException {
        if (this.accountRequired) {
            throw new AccountRequiredException();
        } else if (this.enableAdultContentRequired) {
            if (account == null) {
                throw new AccountRequiredException();
            } else {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Adult content disabled in account", 2 * 60 * 60 * 1000l);
            }
        }
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
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Broken file?");
            }
        }
    }

    public boolean login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setFollowRedirects(true);
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null) {
                    logger.info("Attempting cookie login");
                    this.br.setCookies(this.getHost(), cookies);
                    if (!force) {
                        /* Do not validate cookies. */
                        return false;
                    }
                    br.getPage("https://" + this.getHost() + "/");
                    if (this.isLoggedin(br)) {
                        logger.info("Cookie login successful");
                        /* Refresh cookie timestamp */
                        account.saveCookies(this.br.getCookies(br.getHost()), "");
                        return true;
                    } else {
                        logger.info("Cookie login failed");
                    }
                }
                logger.info("Performing full login");
                br.getPage("https://www." + this.getHost() + "/login");
                final Form loginform = br.getFormbyProperty("id", "login-form");
                if (loginform == null) {
                    logger.warning("Failed to find loginform");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                /* Handle login-captcha if required */
                final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
                loginform.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                loginform.put("name", Encoding.urlEncode(account.getUser()));
                loginform.put("pass", Encoding.urlEncode(account.getPass()));
                br.submitForm(loginform);
                if (!isLoggedin(br)) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.saveCookies(this.br.getCookies(br.getHost()), "");
                return true;
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    private boolean isLoggedin(final Browser br) {
        return br.containsHTML("/logout");
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        login(account, false);
        requestFileInformation(link);
        doFree(link, account);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public boolean hasCaptcha(final DownloadLink link, final Account acc) {
        /* 2020-08-20: No captchas at all except login captcha */
        return false;
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
