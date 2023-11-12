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

import java.util.Locale;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.plugins.components.antiDDoSForHost;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.http.requests.FormData;
import jd.http.requests.PostFormDataRequest;
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
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision: 48194 $", interfaceVersion = 2, names = { "zbigz.com" }, urls = { "https?://(?:www\\.)?zbigz\\.com/file/[a-z0-9]+/\\d+|https?://api\\.zbigz\\.com/v1/storage/get/[a-f0-9]+" })
public class ZbigzCom extends antiDDoSForHost {
    public ZbigzCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://zbigz.com/page-premium-overview");
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.COOKIE_LOGIN_OPTIONAL };
    }

    @Override
    public String getAGBLink() {
        return "https://zbigz.com/page-therms-of-use";
    }

    private String dllink = null;

    public boolean isProxyRotationEnabledForLinkChecker() {
        return false;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        final Account aa = AccountController.getInstance().getValidAccount(this.getHost());
        if (aa == null) {
            link.getLinkStatus().setStatusText("Status can only be checked with account enabled");
            return AvailableStatus.UNCHECKABLE;
        }
        login(aa, false);
        br.setFollowRedirects(false);
        /* 2020-03-16: false */
        final boolean enable_antiddos_workaround = false;
        if (enable_antiddos_workaround) {
            br.getPage(link.getPluginPatternMatcher());
        } else {
            super.getPage(link.getPluginPatternMatcher());
        }
        /* 2020-03-16: E.g. {"error":404,"error_msg":"Torrent not found"} */
        final String error = PluginJSonUtils.getJson(br, "error");
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("Page not found") || "404".equals(error)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        dllink = br.getRedirectLocation();
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        URLConnectionAdapter con = null;
        try {
            con = br.openGetConnection(dllink);
            if (this.looksLikeDownloadableContent(con)) {
                link.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(con)).trim());
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
            } else {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
    }

    private final String WEBSITE_API_BASE = "https://api.zbigz.com/v1";

    private void login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                br.setFollowRedirects(true);
                final Cookies cookies = account.loadCookies("");
                /* 2021-06-16: Added cookie login as alternative login because their website only allows 1 active session at a time. */
                final Cookies userCookies = account.loadUserCookies();
                if (userCookies != null) {
                    /* Always verify user cookies */
                    if (this.checkCookieLogin(account, userCookies)) {
                        /*
                         * User could have entered anything in the username field -> Make sure that the username of this account is unique!
                         */
                        final String mail = PluginJSonUtils.getJson(br, "email");
                        if (!StringUtils.isEmpty(mail)) {
                            account.setUser(mail);
                        }
                        return;
                    } else {
                        if (account.hasEverBeenValid()) {
                            throw new AccountInvalidException(_GUI.T.accountdialog_check_cookies_expired());
                        } else {
                            throw new AccountInvalidException(_GUI.T.accountdialog_check_cookies_invalid());
                        }
                    }
                } else if (cookies != null) {
                    if (!force) {
                        /* Trust cookies without check */
                        br.setCookies(cookies);
                        return;
                    } else if (this.checkCookieLogin(account, cookies)) {
                        /* Success */
                        return;
                    } else {
                        /* Full login required */
                        br.clearCookies(br.getHost());
                    }
                }
                logger.info("Performing full login");
                getPage("https://" + this.getHost() + "/login");
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.getHeaders().put("Accept", "application/json, application/xml, text/plain, text/html, *.*");
                postPage(WEBSITE_API_BASE + "/account/info", "undefined=undefined");
                /* Important header!! */
                br.getHeaders().put("Origin", "https://" + this.getHost());
                final PostFormDataRequest authReq = br.createPostFormDataRequest(WEBSITE_API_BASE + "/account/auth/token");
                authReq.addFormData(new FormData("undefined", "undefined"));
                super.sendRequest(authReq);
                final String auth_token_name = PluginJSonUtils.getJson(br, "auth_token_name");
                final String auth_token_value = PluginJSonUtils.getJson(br, "auth_token_value");
                if (StringUtils.isEmpty(auth_token_name) || StringUtils.isEmpty(auth_token_value)) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final PostFormDataRequest loginReq = br.createPostFormDataRequest("/v1/account/sign-in");
                /* 2019-11-04: Seems like login captcha is always required */
                final DownloadLink dlinkbefore = this.getDownloadLink();
                try {
                    final DownloadLink dl_dummy;
                    if (dlinkbefore != null) {
                        dl_dummy = dlinkbefore;
                    } else {
                        dl_dummy = new DownloadLink(this, "Account", this.getHost(), "https://" + account.getHoster(), true);
                        this.setDownloadLink(dl_dummy);
                    }
                    /* 2019-11-04: Hardcoded reCaptchaV2 key */
                    final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br, "6Lei4loUAAAAACp9km05L8agghrMMNNSYo5Mfmhj").getToken();
                    loginReq.addFormData(new FormData("recaptcha", Encoding.urlEncode(recaptchaV2Response)));
                } finally {
                    this.setDownloadLink(dlinkbefore);
                }
                loginReq.addFormData(new FormData("login", account.getUser()));
                loginReq.addFormData(new FormData("email", account.getUser()));
                loginReq.addFormData(new FormData("password", account.getPass()));
                loginReq.addFormData(new FormData("csrf_name", auth_token_name));
                loginReq.addFormData(new FormData("csrf_value", auth_token_value));
                super.sendRequest(loginReq);
                /* 2019-11-04: This will also be set as cookie with key "session". */
                final String sessiontoken = PluginJSonUtils.getJson(br, "session");
                if (StringUtils.isEmpty(sessiontoken)) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.saveCookies(this.br.getCookies(this.getHost()), "");
            } catch (final PluginException e) {
                account.clearCookies("");
                throw e;
            }
        }
    }

    private boolean checkCookieLogin(final Account account, final Cookies cookies) throws Exception {
        logger.info("Attempting cookie login");
        this.br.setCookies(this.getHost(), cookies);
        final PostFormDataRequest accountInfoReq = br.createPostFormDataRequest(WEBSITE_API_BASE + "/account/info");
        accountInfoReq.addFormData(new FormData("undefined", "undefined"));
        // br.clearCookies("zbigz.com");
        super.sendRequest(accountInfoReq);
        final String email = PluginJSonUtils.getJson(br, "email");
        if (!StringUtils.isEmpty(email)) {
            logger.info("Cookie login successful");
            account.saveCookies(this.br.getCookies(this.getHost()), "");
            return true;
        } else {
            logger.info("Cookie login failed");
            return false;
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        /* Browser: https://zbigz.com/account */
        if (br.getURL() == null || !br.getURL().contains("/account/info")) {
            final PostFormDataRequest accountInfoReq = br.createPostFormDataRequest(WEBSITE_API_BASE + "/account/info");
            accountInfoReq.addFormData(new FormData("undefined", "undefined"));
            super.sendRequest(accountInfoReq);
        }
        final Map<String, Object> entries = restoreFromString(br.toString(), TypeRef.MAP);
        final String premium_valid_date = (String) entries.get("premium_valid_date");
        final String premium_days = PluginJSonUtils.getJson(br, "premium_days");
        if (!StringUtils.isEmpty(premium_valid_date) || "true".equalsIgnoreCase(premium_days)) {
            account.setType(AccountType.PREMIUM);
            ai.setUnlimitedTraffic();
            if (!StringUtils.isEmpty(premium_valid_date)) {
                ai.setValidUntil(TimeFormatter.getMilliSeconds(premium_valid_date, "dd MMM yyyy", Locale.ENGLISH), this.br);
            }
        } else {
            account.setType(AccountType.FREE);
            /*
             * 2020-03-16: Free accounts can also be used to download files they are simply limited by the number of files they can add per
             * day and downloadspeed.
             */
            ai.setUnlimitedTraffic();
        }
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, -5);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        this.dl.startDownload();
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}