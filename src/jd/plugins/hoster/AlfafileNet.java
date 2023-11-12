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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.config.Property;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountUnavailableException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision: 48136 $", interfaceVersion = 3, names = {}, urls = {})
public class AlfafileNet extends PluginForHost {
    public AlfafileNet(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("http://alfafile.net/premium");
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "alfafile.net" });
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/file/([A-Za-z0-9]+)");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public String getAGBLink() {
        return "http://alfafile.net/terms";
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

    /* Connection stuff */
    private static final boolean FREE_RESUME                  = false;
    private static final int     FREE_MAXCHUNKS               = 1;
    private static final int     FREE_MAXDOWNLOADS            = 1;
    private static final boolean ACCOUNT_FREE_RESUME          = false;
    private static final int     ACCOUNT_FREE_MAXCHUNKS       = 1;
    private static final int     ACCOUNT_FREE_MAXDOWNLOADS    = 1;
    private static final boolean ACCOUNT_PREMIUM_RESUME       = true;
    private static final int     ACCOUNT_PREMIUM_MAXCHUNKS    = -5;
    private static final int     ACCOUNT_PREMIUM_MAXDOWNLOADS = 20;
    /* don't touch the following! */
    private boolean              isDirecturl                  = false;
    /*
     * TODO: Use API for linkchecking whenever an account is added to JD. This will ensure that the plugin will always work, at least for
     * premium users. Status 2015-08-03: Filecheck API does not seem to work --> Disabled it - reported API issues to jiaz.
     */
    private static final boolean prefer_api_linkcheck         = false;

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        isDirecturl = false;
        this.setBrowserExclusive();
        prepBR(br);
        String filename = null;
        String filesize = null;
        String md5 = null;
        boolean api_works = false;
        Account aa = AccountController.getInstance().getValidAccount(this.getHost());
        String api_token = null;
        if (aa != null) {
            api_token = getLoginToken(aa);
        }
        if (api_token != null && prefer_api_linkcheck) {
            this.br.getPage(API_BASE + "/file/info?file_id=" + getFileID(link) + "&token=" + api_token);
            final String status = PluginJSonUtils.getJsonValue(br, "status");
            if (!"401".equals(status)) {
                api_works = true;
            }
        }
        if (api_works) {
            final String status = PluginJSonUtils.getJsonValue(br, "status");
            if (!"200".equals(status)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            filename = PluginJSonUtils.getJsonValue(br, "name");
            filesize = PluginJSonUtils.getJsonValue(br, "size");
            md5 = PluginJSonUtils.getJsonValue(br, "hash");
        } else {
            URLConnectionAdapter con = null;
            try {
                con = br.openGetConnection(link.getDownloadURL());
                if (this.looksLikeDownloadableContent(con)) {
                    logger.info("This url is a directurl");
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                    link.setFinalFileName(getFileNameFromHeader(con));
                    isDirecturl = true;
                    return AvailableStatus.TRUE;
                } else {
                    br.followConnection();
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
            if (this.br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            filename = br.getRegex("id=\"st_file_name\" title=\"([^<>\"]*?)\"").getMatch(0);
            filesize = br.getRegex("<span class=\"size\">([^<>\"]*?)</span>").getMatch(0);
        }
        if (filename == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setFinalFileName(Encoding.htmlDecode(filename).trim());
        if (StringUtils.isNotEmpty(filesize)) {
            link.setDownloadSize(SizeFormatter.getSize(filesize.contains(".") && filesize.contains(",") ? filesize.replace(",", "") : filesize));
        }
        if (md5 != null) {
            /* TODO: Check if their API actually returns valid md5 hashes */
            link.setMD5Hash(md5);
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        doFree(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void doFree(final DownloadLink link, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        if (checkShowFreeDialog(getHost())) {
            showFreeDialog(getHost());
        }
        String dllink = checkDirectLink(link, directlinkproperty);
        if (dllink == null) {
            if (isDirecturl) {
                dllink = link.getDownloadURL();
            } else {
                final String fid = getFileID(link);
                br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.getPage("/download/start_timer/" + fid);
                final String reconnect_wait = br.getRegex("Try again in (\\d+) minutes").getMatch(0);
                if (br.containsHTML(">This file can be downloaded by premium users only|>You can download files up to")) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
                } else if (reconnect_wait != null) {
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, Long.parseLong(reconnect_wait) * 60 * 1001l);
                } else if (br.containsHTML("You can't download not more than \\d+ file at a time")) {
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Too many max sim dls", 20 * 60 * 1000l);
                } else if (br.containsHTML("You have reached your daily downloads limit. Please try again later\\.")) {
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "You have reached your daily download limit.", 3 * 60 * 60 * 1000l);
                }
                int wait = 45;
                String wait_str = br.getRegex(">(\\d+) <span>s<").getMatch(0);
                if (wait_str != null) {
                    wait = Integer.parseInt(wait_str);
                }
                String redirect_url = PluginJSonUtils.getJsonValue(br, "redirect_url");
                if (redirect_url == null) {
                    redirect_url = "/file/" + fid + "/captcha";
                }
                this.sleep(wait * 1001l, link);
                br.getPage(redirect_url);
                if (br.getHttpConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 5 * 60 * 1000l);
                }
                this.br.setFollowRedirects(true);
                boolean success = false;
                for (int i = 0; i <= 3; i++) {
                    if (CaptchaHelperHostPluginRecaptchaV2.containsRecaptchaV2Class(br)) {
                        logger.info("Detected captcha method \"reCaptchaV2\" for this host");
                        Form dlForm = br.getFormBySubmitvalue("send");
                        final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, this.br).getToken();
                        dlForm.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                        br.submitForm(dlForm);
                        logger.info("Submitted DLForm");
                        if (CaptchaHelperHostPluginRecaptchaV2.containsRecaptchaV2Class(br)) {
                            continue;
                        }
                        success = true;
                        break;
                    } else {
                        final SolveMedia sm = new SolveMedia(br);
                        sm.setSecure(true);
                        File cf = null;
                        try {
                            cf = sm.downloadCaptcha(getLocalCaptchaFile());
                        } catch (final Exception e) {
                            if (SolveMedia.FAIL_CAUSE_CKEY_MISSING.equals(e.getMessage())) {
                                throw new PluginException(LinkStatus.ERROR_FATAL, "Host side solvemedia.com captcha error - please contact the " + this.getHost() + " support");
                            }
                            throw e;
                        }
                        final String code = getCaptchaCode("solvemedia", cf, link);
                        final String chid = sm.getChallenge(code);
                        this.br.postPage(this.br.getURL(), "send=Send&adcopy_response=" + Encoding.urlEncode(code) + "&adcopy_challenge=" + Encoding.urlEncode(chid));
                        if (br.containsHTML("solvemedia\\.com/papi/")) {
                            continue;
                        }
                        success = true;
                        break;
                    }
                }
                if (!success) {
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                }
                dllink = br.getRegex("href=\"(https://[^<>\"]*?)\" class=\"big_button\"><span>Download</span>").getMatch(0);
                if (dllink == null) {
                    dllink = br.getRegex("\"(https?://[a-z0-9\\-]+\\.alfafile\\.net/dl/[^<>\"]*?)\"").getMatch(0);
                }
                if (dllink == null) {
                    /* 2020-04-14 */
                    dllink = br.getRegex("\"(https?://[a-z0-9\\-]+\\.alfafile\\.[a-z]+/download/[^<>\"]*?)\"").getMatch(0);
                }
                if (dllink == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
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
        link.setProperty(directlinkproperty, dllink);
        dl.startDownload();
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

    private void prepBR(final Browser br) {
        br.setCookie(this.getHost(), "lang", "en");
        br.setFollowRedirects(true);
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    /** https://alfafile.net/api/doc */
    private static final String API_BASE = "https://alfafile.net/api/v1";

    private void login(final Account account, final boolean validateLoginToken) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                prepBR(br);
                br.setFollowRedirects(true);
                final Cookies cookies = account.loadCookies("");
                String token = getLoginToken(account);
                if (cookies != null && token != null) {
                    /* We do not really need the cookies but we need the timstamp! */
                    br.setCookies(this.getHost(), cookies);
                    if (!validateLoginToken) {
                        logger.info("Trust token without check");
                        return;
                    }
                    logger.info("Checking token");
                    br.postPage(API_BASE + "/user/info", "token=" + Encoding.urlEncode(token));
                    if (this.isLoggedIN(br)) {
                        logger.info("Token login successful");
                        return;
                    } else {
                        logger.info("Token login failed");
                    }
                }
                logger.info("Performing full login");
                /*
                 * Using the same API as rapidgator.net (alfafile uses "/v1" in baseURL, rapidgator uses "v2" but responses are the same.)
                 */
                br.getPage(API_BASE + "/user/login?login=" + Encoding.urlEncode(account.getUser()) + "&password=" + Encoding.urlEncode(account.getPass()));
                token = PluginJSonUtils.getJsonValue(br, "token");
                if (token == null || !isLoggedIN(br)) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.saveCookies(br.getCookies(br.getURL()), "");
                account.setProperty("token", token);
            } catch (final PluginException e) {
                account.setProperty("cookies", Property.NULL);
                throw e;
            }
        }
    }

    private boolean isLoggedIN(final Browser br) {
        if ("200".equals(PluginJSonUtils.getJsonValue(br, "status"))) {
            return true;
        } else {
            return false;
        }
    }

    @SuppressWarnings({ "unchecked" })
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        final Map<String, Object> entries = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
        final long traffic_total = JavaScriptEngineFactory.toLong(JavaScriptEngineFactory.walkJson(entries, "response/user/traffic/total"), -1);
        final long traffic_left = JavaScriptEngineFactory.toLong(JavaScriptEngineFactory.walkJson(entries, "response/user/traffic/left"), -1);
        final String ispremium = PluginJSonUtils.getJsonValue(br, "is_premium");
        if ("true".equals(ispremium)) {
            final String expire = PluginJSonUtils.getJsonValue(br, "premium_end_time");
            ai.setValidUntil(Long.parseLong(expire) * 1000);
            account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
            account.setType(AccountType.PREMIUM);
            account.setConcurrentUsePossible(true);
            ai.setStatus("Premium account");
        } else {
            account.setType(AccountType.FREE);
            /* free accounts can still have captcha */
            account.setMaxSimultanDownloads(ACCOUNT_FREE_MAXDOWNLOADS);
            account.setConcurrentUsePossible(false);
            ai.setStatus("Registered (free) user");
        }
        ai.setTrafficLeft(traffic_left);
        ai.setTrafficMax(traffic_total);
        return ai;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        login(account, false);
        br.setFollowRedirects(false);
        if (account.getType() == AccountType.FREE) {
            /*
             * No API --> We're actually not downloading via free account but it doesnt matter as there are no known free account advantages
             * compared to unregistered mode.
             */
            br.getPage(link.getDownloadURL());
            doFree(link, ACCOUNT_FREE_RESUME, ACCOUNT_FREE_MAXCHUNKS, "account_free_directlink");
        } else {
            String dllink = this.checkDirectLink(link, "premium_directlink");
            if (dllink == null) {
                final String fid = getFileID(link);
                this.br.getPage(API_BASE + "/file/download?file_id=" + fid + "&token=" + getLoginToken(account));
                handleErrorsGeneral(account);
                dllink = PluginJSonUtils.getJsonValue(br, "download_url");
                if (dllink == null) {
                    logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, ACCOUNT_PREMIUM_RESUME, ACCOUNT_PREMIUM_MAXCHUNKS);
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
            link.setProperty("premium_directlink", dllink);
            dl.startDownload();
        }
    }

    private void handleErrorsGeneral(final Account account) throws PluginException {
        final String errorcode = PluginJSonUtils.getJsonValue(br, "status");
        String errormessage = PluginJSonUtils.getJsonValue(br, "details");
        if ("409".equals(errorcode) && StringUtils.containsIgnoreCase(errormessage, "File temporarily unavailable")) {
            /*
             * {"response":null,"status":409,"details":"Conflict. File temporarily unavailable."}
             */
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, errormessage, 30 * 60 * 1000l);
        }
        if (errorcode != null) {
            if (errorcode.equals("401")) {
                /* This can sometimes happen in premium mode */
                /* {"response":null,"status":401,"details":"Unauthorized. Token doesn't exist"} */
                if (account == null) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, errormessage, 5 * 60 * 1000l);
                } else {
                    throw new AccountUnavailableException(errormessage, 5 * 60 * 1000l);
                }
            } else if (errorcode.equals("404")) {
                /*
                 * E.g. detailed errormessages: "details":"File with file_id: '1234567' doesn't exist"
                 */
                if (errormessage == null) {
                    errormessage = "File does not exist according to API";
                }
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (errorcode.equals("409")) {
                /*
                 * E.g. detailed errormessages:
                 *
                 * Conflict. Delay between downloads must be not less than 60 minutes. Try again in 51 minutes.
                 *
                 * Conflict. DOWNLOAD::ERROR::You can't download not more than 1 file at a time in free mode.
                 */
                String minutes_regexed = null;
                int minutes = 60;
                if (errormessage != null) {
                    minutes_regexed = new Regex(errormessage, "again in (\\d+) minutes?").getMatch(0);
                    if (minutes_regexed != null) {
                        minutes = Integer.parseInt(minutes_regexed);
                    }
                }
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, minutes * 60 * 1001l);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private String getFileID(final DownloadLink dl) {
        return new Regex(dl.getDownloadURL(), "([A-Za-z0-9]+)$").getMatch(0);
    }

    private String getLoginToken(final Account acc) {
        return acc.getStringProperty("token", null);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return ACCOUNT_PREMIUM_MAXDOWNLOADS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}