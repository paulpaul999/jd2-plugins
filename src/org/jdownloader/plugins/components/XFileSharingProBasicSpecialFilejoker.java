package org.jdownloader.plugins.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountRequiredException;
import jd.plugins.AccountUnavailableException;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision: 47924 $", interfaceVersion = 3, names = {}, urls = {})
public class XFileSharingProBasicSpecialFilejoker extends XFileSharingProBasic {
    public XFileSharingProBasicSpecialFilejoker(final PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(super.getPurchasePremiumURL());
    }

    /**
     * DEV NOTES XFileSharingProBasicSpecialFilejoker Version SEE SUPER-CLASS<br />
     * mods: Special XFS derivate which supports a special API used by some hosts. <br />
     */
    @Override
    protected String getDllink(final DownloadLink link, final Account account, final Browser br, final String src) {
        /* 2019-08-21: Special for novafile.com & filejoker.net */
        String dllink = new Regex(src, "\"(https?://f?s\\d+[^/]+/[^\"]+)\"").getMatch(0);
        if (StringUtils.isEmpty(dllink)) {
            dllink = new Regex(src, "href\\s*=\\s*\"(https?://[^<>\"]+)\"\\s*class\\s*=\\s*\"btn btn[^\"]*\"").getMatch(0);
        }
        if (StringUtils.isEmpty(dllink)) {
            dllink = new Regex(src, "\"(https?://[^/]+/[a-z0-9]{50,100}/[^\"]+)\"").getMatch(0);
        }
        if (StringUtils.isEmpty(dllink)) {
            /* Fallback to template handling */
            dllink = super.getDllink(link, account, br, src);
        }
        if (account != null && !StringUtils.isEmpty(dllink)) {
            /* Set timestamp of last download on account - this might be useful later for our Free-Account handling */
            account.setProperty(PROPERTY_LASTDOWNLOAD_WEBSITE, System.currentTimeMillis());
        }
        return dllink;
    }

    public static final String getFileJokerAnnotationPatternPart() {
        return "/(?:embed-|file/)?[a-z0-9]{12}(?:/[^/]+(?:\\.html)?)?";
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + XFileSharingProBasicSpecialFilejoker.getFileJokerAnnotationPatternPart());
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public Form findFormDownload2Premium(final DownloadLink downloadLink, final Account account, final Browser br) throws Exception {
        /* 2019-08-20: Special */
        handleSecurityVerification(br);
        return super.findFormDownload2Premium(downloadLink, account, br);
    }

    @Override
    public Form findFormDownload1Free(final Browser br) throws Exception {
        /* 2019-08-20: Special */
        handleSecurityVerification(br);
        return super.findFormDownload1Free(br);
    }

    @Override
    protected void getPage(final String page) throws Exception {
        /* 2019-08-20: Special */
        super.getPage(page);
        handleSecurityVerification(br);
    }

    @Override
    protected void postPage(final String page, final String data) throws Exception {
        /* 2019-08-20: Special */
        super.postPage(page, data);
        handleSecurityVerification(br);
    }

    @Override
    protected void submitForm(final Form form) throws Exception {
        /* 2020-04-24: Special */
        super.submitForm(form);
        handleSecurityVerification(br);
    }

    protected boolean isSecurityVerificationRequired(final Browser br) {
        if (StringUtils.containsIgnoreCase(br.getURL(), "op=captcha&id=") || br.containsHTML("(?i)<h1>\\s*Security Verification\\s*</h1>")) {
            return true;
        } else {
            return false;
        }
    }

    /* 2019-08-20: Special */
    protected void handleSecurityVerification(final Browser br) throws Exception {
        if (isSecurityVerificationRequired(br)) {
            /*
             * 2019-01-23: Special - this may also happen in premium mode! This will only happen when accessing downloadurl. It gets e.g.
             * triggered when accessing a lot of different downloadurls in a small timeframe.
             */
            /* Tags: XFS_IP_CHECK /ip_check/ */
            final Form securityVerification = getSecurityVerificationForm(br);
            if (securityVerification != null && securityVerification.containsHTML("data-sitekey")) {
                logger.info("Handling securityVerification");
                if (containsHCaptcha(getCorrectBR(br))) {
                    handleHCaptcha(getDownloadLink(), br, securityVerification);
                } else if (containsRecaptchaV2Class(getCorrectBR(br))) {
                    handleRecaptchaV2(getDownloadLink(), br, securityVerification);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final boolean redirectBehavior = br.isFollowingRedirects();
                br.setFollowRedirects(true);
                try {
                    submitForm(br, securityVerification);
                } finally {
                    br.setFollowRedirects(redirectBehavior);
                }
                if (this.isSecurityVerificationRequired(br)) {
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                }
            } else {
                logger.warning("Failed to process security verification - subsequent handling may fail");
            }
        }
    }

    protected Form getSecurityVerificationForm(final Browser br) {
        Form form = br.getFormbyProperty("name", "F1");
        if (form == null) {
            form = br.getFormbyProperty("id", "f1");
        }
        return form;
    }

    protected boolean useRandomUserAgentWebsite() {
        return false;
    }

    /* *************************** SPECIAL API STUFF STARTS HERE *************************** */
    private static final String PROPERTY_SESSIONID                              = "cookie_zeus_cloud_sessionid";
    private static final String PROPERTY_EMAIL                                  = "cookie_email";
    private static final String PROPERTY_USERNAME                               = "cookie_username";
    private static final String PROPERTY_LAST_API_LOGIN_FAILURE_IN_WEBSITE_MODE = "timestamp_last_api_login_failure_in_website_mode";
    private static final String PROPERTY_LASTDOWNLOAD_API                       = "lastdownload_timestamp_api";
    private static final String PROPERTY_LASTDOWNLOAD_WEBSITE                   = "lastdownload_timestamp_website";
    private static final String PROPERTY_COOKIES_API                            = "PROPERTY_COOKIES_API";
    public static final String  PROPERTY_SETTING_USE_API                        = "USE_API_2020_05_20";
    public static final String  PROPERTY_API_FAILURE_TOGGLE_WEBSITE_FALLBACK    = "PROPERTY_API_FAILURE_TOGGLE_WEBSITE_FALLBACK";
    public static final boolean default_PROPERTY_SETTING_USE_API                = true;

    /**
     * Turns on/off special API for (Free-)Account Login & Download. Keep this activated whenever possible as it will solve a lot of
     * issues/complicated handling which is required for website login and download! </br>
     * Sidenote: API Cookies will work fine for the website too so if enabled- and later disabled, login-captchas should still be avoided!
     */
    protected boolean useAPIZeusCloudManager(final Account account) {
        if (account != null) {
            final Cookies userCookies = account.loadUserCookies();
            if (userCookies != null) {
                /* User entered cookies as password -> We can't use the API! */
                return false;
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    protected boolean internal_useAPIZeusCloudManager(final DownloadLink link, final Account account) {
        return (link == null || !URL_TYPE.FILE.equals(getURLType(link))) && useAPIZeusCloudManager(account) && !isAPITempDisabled(account);
    }

    /**
     * API login may avoid the need of login captchas. If enabled, ZeusCloudManagerAPI login will be tried even if API is disabled and
     * resulting cookies will be used in website mode. Only enable this if tested! </br>
     * default = false
     */
    protected boolean tryAPILoginInWebsiteMode(final Account account) {
        return false;
    }

    /**
     * If enabled [and tryAPILoginInWebsiteMode enabled], API can be used to login- and obtain account information even if API is disabled
     * and downloads will be executed via website. </br>
     * If disabled [and tryAPILoginInWebsiteMode enabled], API can be used to login in website mode but account information will be obtained
     * from website.
     */
    protected boolean tryAPILoginInWebsiteMode_get_account_info_from_api(final Account account) {
        return true;
    }

    /** Override this depending on host */
    protected String getRelativeAPIBaseAPIZeusCloudManager() {
        return null;
    }

    /** Override this depending on host (2019-08-21: Some use "login", some use "email" [login via email:password or username:password]) */
    protected String getRelativeAPILoginParamsFormatAPIZeusCloudManager() {
        return null;
    }

    /** If enabled, random User-Agent will be used in API mode! */
    protected boolean useRandomUserAgentAPI() {
        return false;
    }

    /** 2019-08-20: API will also work fine with different User-Agent values. */
    private final Browser prepAPIZeusCloudManager(final Browser br) {
        if (!this.useRandomUserAgentAPI()) {
            br.getHeaders().put("User-Agent", "okhttp/3.8.0");
        }
        /* 2019-09-07: Seems like sometimes they're blocking User-Agents and they will then return error 418 */
        br.setAllowedResponseCodes(new int[] { 418 });
        return br;
    }

    @Override
    protected boolean useRUA() {
        if (internal_useAPIZeusCloudManager(null, null)) {
            /* For API mode */
            return useRandomUserAgentAPI();
        } else {
            /* For website mode */
            return useRandomUserAgentWebsite();
        }
    }

    /**
     * @return true = verified cookies/session </br>
     *         false = did not verify cookies/session
     */
    private final boolean loginAPIZeusCloudManager(final Browser apibr, final Account account, final boolean validateSession) throws Exception {
        synchronized (account) {
            prepAPIZeusCloudManager(apibr);
            boolean validatedSession = false;
            String sessionid = getAPIZeusCloudManagerSession(account);
            try {
                if (!StringUtils.isEmpty(sessionid)) {
                    final long timeStamp = account.getCookiesTimeStamp("");
                    final long age = System.currentTimeMillis() - timeStamp;
                    if (age <= (60 * 60 * 1000l) && !validateSession) {
                        /* We trust these cookies as they're not that old --> Do not check them */
                        logger.info("Trust login-sessionid without checking as it should still be fresh: age=" + TimeFormatter.formatMilliSeconds(age, 0));
                        return false;
                    } else {
                        logger.info("Verify login-sessionid: age=" + TimeFormatter.formatMilliSeconds(age, 0));
                    }
                    /* First check if old session is still valid */
                    getPage(apibr, this.getMainPage() + getRelativeAPIBaseAPIZeusCloudManager() + "?op=my_account&session=" + sessionid);
                    final String error = PluginJSonUtils.getJson(apibr, "error");
                    /* Check for e.g. "{"error":"invalid session"}" */
                    /*
                     * 2019-08-28: Errors may happen at this stage but we only want to perform a full login if we're absolutely sure that
                     * our current sessionID is invalid!
                     */
                    if (!StringUtils.equalsIgnoreCase(error, "invalid session") && apibr.getHttpConnection().getResponseCode() == 200) {
                        validatedSession = true;
                        this.checkErrorsAPIZeusCloudManager(apibr, null, account);
                    }
                }
                if (!validatedSession) {
                    getPage(apibr, this.getMainPage() + getRelativeAPIBaseAPIZeusCloudManager() + String.format(getRelativeAPILoginParamsFormatAPIZeusCloudManager(), Encoding.urlEncode(account.getUser()), Encoding.urlEncode(account.getPass())));
                    sessionid = PluginJSonUtils.getJson(apibr, "session");
                    this.checkErrorsAPIZeusCloudManager(apibr, null, account);
                    if (StringUtils.isEmpty(sessionid)) {
                        /* All errors should be handled by checkErrorsAPIZeusCloudManager already so this might happen but is unusual. */
                        logger.info("Login failed for unknown reasons");
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        validatedSession = true;
                    }
                }
                /* reduce refresh to avoid false serverside error *hack activity* */
                account.setRefreshTimeout(2 * 60 * 60 * 1000l);
                return validatedSession;
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    this.dumpAPISessionInfo(account);
                }
                throw e;
            } finally {
                /*
                 * 2019-10-08: Errors may occur during login e.g. IP ban but as long as validatedCookies = true we know that our
                 * cookies/sessionID is valid and we can save it for eventual later usage via website.
                 */
                if (validatedSession) {
                    logger.info("convertSpecialAPICookiesToWebsiteCookiesAndSaveThem(" + sessionid + "):" + convertSpecialAPICookiesToWebsiteCookiesAndSaveThem(account, sessionid));
                }
            }
        }
    }

    /** This is different from the official XFS "API-Mod" API!! */
    private final AccountInfo fetchAccountInfoAPIZeusCloudManager(final Browser br, final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        /* Only access this page if it has not been accessed before! */
        if (br.getURL() == null || !br.getURL().contains(getRelativeAPIBaseAPIZeusCloudManager() + "?op=my_account")) {
            loginAPIZeusCloudManager(br, account, true);
        }
        final String sessionid = getAPIZeusCloudManagerSession(account);
        if (br.getURL() == null || !br.getURL().contains(getRelativeAPIBaseAPIZeusCloudManager() + "?op=my_account&session=")) {
            getPage(br, this.getMainPage() + getRelativeAPIBaseAPIZeusCloudManager() + "?op=my_account&session=" + sessionid);
        }
        final Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
        /** 2019-08-20: Better compare expire-date against their serverside time if possible! */
        final String server_timeStr = (String) entries.get("server_time");
        long expire_milliseconds_precise_to_the_second = 0;
        final String email = (String) entries.get("usr_email");
        final String username = (String) entries.get("usr_login");
        final long currentTime;
        if (server_timeStr != null && server_timeStr.matches("\\d{4}\\-\\d{2}\\-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
            currentTime = TimeFormatter.getMilliSeconds(server_timeStr, "yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        } else {
            currentTime = System.currentTimeMillis();
        }
        Long trafficleft = JavaScriptEngineFactory.toLong(entries.get("traffic_left"), Long.MIN_VALUE);
        if (trafficleft.longValue() == Long.MIN_VALUE) {
            trafficleft = null;
        }
        final String expireStr = (String) entries.get("usr_premium_expire");
        if (expireStr != null && expireStr.matches("\\d{4}\\-\\d{2}\\-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
            expire_milliseconds_precise_to_the_second = TimeFormatter.getMilliSeconds(expireStr, "yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        }
        boolean isPremium = false;
        if (expire_milliseconds_precise_to_the_second <= currentTime) {
            if (expire_milliseconds_precise_to_the_second > 0) {
                /*
                 * 2019-07-31: Most likely this logger will always get triggered because they will usually set the register date of new free
                 * accounts into "premium_expire".
                 */
                logger.info("Premium expired --> Free account");
            }
            /* Expired premium or no expire date given --> It is usually a Free Account */
            setAccountLimitsByType(account, AccountType.FREE);
        } else {
            isPremium = true;
            /* Expire date is in the future --> It is a premium account */
            ai.setValidUntil(expire_milliseconds_precise_to_the_second);
            setAccountLimitsByType(account, AccountType.PREMIUM);
        }
        if (trafficleft != null) {
            ai.setTrafficLeft(trafficleft * 1024 * 1024);
        }
        if (!StringUtils.isEmpty(email)) {
            account.setProperty(PROPERTY_EMAIL, email);
        }
        if (!StringUtils.isEmpty(username)) {
            account.setProperty(PROPERTY_USERNAME, username);
        }
        logger.info("convertSpecialAPICookiesToWebsiteCookiesAndSaveThem(" + sessionid + "):" + convertSpecialAPICookiesToWebsiteCookiesAndSaveThem(account, sessionid));
        return ai;
    }

    private final void handlePremiumAPIZeusCloudManager(final Browser br, final DownloadLink link, final Account account) throws Exception {
        loginAPIZeusCloudManager(br, account, false);
        final String directlinkproperty = getDownloadModeDirectlinkProperty(account);
        String dllink = checkDirectLinkAPIZeusCloudManager(link, directlinkproperty);
        if (StringUtils.isEmpty(dllink)) {
            final String sessionid = this.getAPIZeusCloudManagerSession(account);
            this.getPage(br, this.getMainPage() + getRelativeAPIBaseAPIZeusCloudManager() + "?op=download1&session=" + sessionid + "&file_code=" + this.getFUIDFromURL(link));
            /*
             * 2019-08-21: Example response (free account download):
             * {"download_id":"xxxxxxxx","file_size":"200000000","file_name":"test.dat","file_code":"xxxxxxxxxxxx","countdown":"90"}
             */
            checkErrorsAPIZeusCloudManager(this.br, link, account);
            final String download_id = PluginJSonUtils.getJson(br, "download_id");
            final String waittimeSecondsStr = PluginJSonUtils.getJson(br, "countdown");
            if (StringUtils.isEmpty(download_id)) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "download_id is missing");
            }
            if (!StringUtils.isEmpty(waittimeSecondsStr) && waittimeSecondsStr.matches("\\d+")) {
                /* E.g. free account download will usually have a waittime of 90 seconds before download can be started. */
                final int waittimeSeconds = Integer.parseInt(waittimeSecondsStr);
                this.sleep(waittimeSeconds * 1001l, link);
            }
            this.getPage(br, this.getMainPage() + getRelativeAPIBaseAPIZeusCloudManager() + "?op=download2&session=" + sessionid + "&file_code=" + this.getFUIDFromURL(link) + "&download_id=" + download_id);
            /*
             * 2019-08-21: Example response (free account download):
             * {"file_size":"200000000","file_name":"test.dat","file_code":"xxxxxxxxxxxx",
             * "message":"Wait 5 hours 12 minutes 46 seconds to download for free."}
             */
            checkErrorsAPIZeusCloudManager(this.br, link, account);
            dllink = PluginJSonUtils.getJson(br, "direct_link");
            if (!StringUtils.isEmpty(dllink)) {
                /* Set timestamp of last download on account - this might be useful later for our Free-Account handling */
                account.setProperty(PROPERTY_LASTDOWNLOAD_API, System.currentTimeMillis());
            }
        }
        this.handleDownload(link, account, dllink, null);
    }

    protected final String checkDirectLinkAPIZeusCloudManager(final DownloadLink link, final String property) {
        /* 2019-08-28: Special */
        final String directurl = link.getStringProperty(property, null);
        if (StringUtils.isEmpty(directurl) || !directurl.startsWith("http")) {
            return null;
        } else {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                con = openAntiDDoSRequestConnection(br2, br2.createHeadRequest(directurl));
                /* For video streams we often don't get a Content-Disposition header. */
                // final boolean isFile = con.isContentDisposition() || StringUtils.containsIgnoreCase(con.getContentType(), "video") ||
                // StringUtils.containsIgnoreCase(con.getContentType(), "audio") || StringUtils.containsIgnoreCase(con.getContentType(),
                // "application");
                /*
                 * 2019-08-28: contentDisposition is always there plus invalid URLs might have: Content-Type: application/octet-stream -->
                 * 'application' as Content-Type is no longer an indication that we can expect a file!
                 */
                final boolean isDownload = con.isContentDisposition() || StringUtils.containsIgnoreCase(con.getContentType(), "video") || StringUtils.containsIgnoreCase(con.getContentType(), "audio");
                if (con.getResponseCode() == 503) {
                    /* Ok */
                    /*
                     * Too many connections but that does not mean that our directlink is invalid. Accept it and if it still returns 503 on
                     * download-attempt this error will get displayed to the user - such directlinks should work again once there are less
                     * active connections to the host!
                     */
                    logger.info("directurl lead to 503 | too many connections");
                    return directurl;
                } else if (!con.getContentType().contains("text") && con.getLongContentLength() >= 0 && con.isOK() && isDownload) {
                    if (/* StringUtils.equalsIgnoreCase(con.getContentType(), "application/octet-stream") && */con.getLongContentLength() < 100) {
                        throw new Exception("very likely no file but an error message!length=" + con.getLongContentLength());
                    } else {
                        return directurl;
                    }
                } else {
                    /* Failure */
                }
            } catch (final Exception e) {
                /* Failure */
                logger.log(e);
            } finally {
                if (con != null) {
                    try {
                        con.disconnect();
                    } catch (final Throwable e) {
                    }
                }
            }
            return null;
        }
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        this.resolveShortURL(this.br.cloneBrowser(), link, account);
        if (internal_useAPIZeusCloudManager(link, account)) {
            // api doesn't yet support /file/xy urls?! -> error: "no file"
            handlePremiumAPIZeusCloudManager(this.br, link, account);
        } else {
            super.handlePremium(link, account);
        }
    }

    /**
     * Converts API values to normal website cookies so we can easily switch to website mode or login via API and then use website later OR
     * download via website right away.
     */
    protected boolean convertSpecialAPICookiesToWebsiteCookiesAndSaveThem(final Account account, final String sessionid) {
        if (account != null && StringUtils.isNotEmpty(sessionid)) {
            synchronized (account) {
                final Cookies cookies = new Cookies();
                if (br != null) {
                    cookies.add(br.getCookies(account.getHoster()));
                }
                cookies.remove("xfss");
                cookies.add(new Cookie(account.getHoster(), "xfss", sessionid));
                final String email = account.getStringProperty(PROPERTY_EMAIL, null);
                final String username = account.getStringProperty(PROPERTY_USERNAME, null);
                if (!StringUtils.isEmpty(email)) {
                    /* 2019-09-12: E.g. required for filejoker.net */
                    cookies.remove("email");
                    cookies.add(new Cookie(account.getHoster(), "email", email));
                }
                if (!StringUtils.isEmpty(username)) {
                    /* 2019-09-12: E.g. required for novafile.com */
                    cookies.remove("login");
                    cookies.add(new Cookie(account.getHoster(), "login", username));
                }
                /*
                 * 2019-09-12: E.g.filejoker.net website needs xfss and email cookies, novafile.com needs xfss and login cookies. Both
                 * websites will also work when xfss, email AND login cookies are present all together!
                 */
                account.saveCookies(cookies, "");
                account.saveCookies(cookies, PROPERTY_COOKIES_API);
                account.setProperty(PROPERTY_SESSIONID, sessionid);
                return true;
            }
        } else {
            return false;
        }
    }

    /**
     * Sets a timestamp so we can e.g. disable API downloads for 60 minutes hardcoded, use website and try again via API afterwards.
     *
     * @throws PluginException
     */
    protected void tempDisableAPI(final Account account, final String failure_reason) throws PluginException {
        account.setProperty(PROPERTY_API_FAILURE_TOGGLE_WEBSITE_FALLBACK, System.currentTimeMillis());
        throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Wait for retry via website because: " + failure_reason, 1 * 60 * 1000l);
    }

    protected boolean isAPITempDisabled(final Account account) {
        if (account == null) {
            return false;
        }
        final long api_failure_timestamp = account.getLongProperty(PROPERTY_API_FAILURE_TOGGLE_WEBSITE_FALLBACK, -1);
        if (api_failure_timestamp == -1) {
            return false;
        }
        final long api_disabled_until = api_failure_timestamp + 60 * 60 * 1000;
        if (System.currentTimeMillis() < api_disabled_until) {
            final long disabled_remaining_time = api_disabled_until - System.currentTimeMillis();
            logger.info("API is temporarily disabled for another: " + TimeFormatter.formatMilliSeconds(disabled_remaining_time, 0));
            return true;
        }
        return false;
    }

    @Override
    protected AccountInfo fetchAccountInfoWebsite(final Account account) throws Exception {
        if (internal_useAPIZeusCloudManager(null, account)) {
            return fetchAccountInfoAPIZeusCloudManager(this.br, account);
        } else {
            final long timestamp_last_api_login_failure_in_website_mode = account.getLongProperty(PROPERTY_LAST_API_LOGIN_FAILURE_IN_WEBSITE_MODE, 0);
            boolean try_api_login_in_website_mode = tryAPILoginInWebsiteMode(account);
            if (try_api_login_in_website_mode) {
                final long api_login_retry_limit = 24 * 60 * 60 * 1000l;
                final long timestamp_api_login_retry_allowed = timestamp_last_api_login_failure_in_website_mode + api_login_retry_limit;
                if (System.currentTimeMillis() < timestamp_api_login_retry_allowed) {
                    final long time_until_new_api_login_in_website_mode_allowed = timestamp_api_login_retry_allowed - System.currentTimeMillis();
                    logger.info("try_api_login is not allowed because last API login attempt failed - retry allowed in: " + TimeFormatter.formatMilliSeconds(time_until_new_api_login_in_website_mode_allowed, 0));
                    try_api_login_in_website_mode = false;
                }
            }
            if (try_api_login_in_website_mode) {
                logger.info("Trying API fetchAccountInfoAPIZeusCloudManager in website as an attempt to avoid login captchas and get more precise account information");
                try {
                    /*
                     * Use a new Browser instance as we do not want to continue via API afterwards thus we do not want to have API
                     * headers/cookies especially as in API mode, different user-agent could be used!
                     */
                    final Browser apiBR = new Browser();
                    /* Do not only call login as we need the email/username cookie which we only get when obtaining AccountInfo! */
                    // loginAPIZeusCloudManager(apiBR, account, force);
                    AccountInfo ai = null;
                    try {
                        if (tryAPILoginInWebsiteMode_get_account_info_from_api(account)) {
                            logger.info("API in website mode is allowed to login and fetchAccountInfo");
                            ai = fetchAccountInfoAPIZeusCloudManager(apiBR, account);
                            logger.info("API login successful --> Verifying cookies via website because if we're unlucky they are not valid for website mode");
                        } else {
                            logger.info("API in website mode is only allowed to login - AccountInfo will be obtained from website");
                            loginAPIZeusCloudManager(br, account, true);
                            /*
                             * Now get AccountInfo anyways because if we don't we will not get the users' mail/username --> We have no
                             * chance to use API cookies for website login to e.g. avoid login captchas.
                             */
                            logger.info("Requesting AccountInfo from API anyways but only to set correct website cookies --> API AccountInfo will not be set!");
                            fetchAccountInfoAPIZeusCloudManager(apiBR, account);
                        }
                        /*
                         * Set cookies converted from API handling --> Website-cookies to verify them. Only trust API login if we are sure
                         * that API login cookies are valid in website mode!!
                         */
                        br.setCookies(getMainPage(), account.loadCookies(""));
                    } catch (final Throwable e) {
                        logger.log(e);
                        logger.info("API handling in website handling failed");
                    }
                    this.getPage(this.getMainPage());
                    if (!this.isLoggedin(this.br)) {
                        logger.info("We are NOT loggedIN according to website --> Either wrong logindata or some other kind of issue");
                        /* Throw exception which will set property to avoid API login next time. */
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        /* All okay, return account information obtained via API although we are in website mode! */
                        logger.info("Successfully logged in via API and used API cookies for website");
                        if (ai != null) {
                            logger.info("Returning AccountInfo from API");
                            return ai;
                        } else {
                            logger.info("NOT returning AccountInfo from API --> Continue via website handling from now on");
                        }
                    }
                } catch (final PluginException e) {
                    if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                        logger.warning("API login failed --> Falling back to website");
                        account.setProperty(PROPERTY_LAST_API_LOGIN_FAILURE_IN_WEBSITE_MODE, System.currentTimeMillis());
                        /* Next do fetchAccountInfoWebsite */
                    } else {
                        logger.info("Error happened during API login in website mode");
                        throw e;
                    }
                }
            }
            return super.fetchAccountInfoWebsite(account);
        }
    }

    @Override
    public boolean loginWebsite(final DownloadLink downloadLink, final Account account, final boolean force) throws Exception {
        if (internal_useAPIZeusCloudManager(downloadLink, account)) {
            return loginAPIZeusCloudManager(this.br, account, force);
        } else {
            return super.loginWebsite(downloadLink, account, force);
        }
    }

    @Override
    public String getLoginURL() {
        /*
         * 2019-09-12: filejoker.net will redirect to /login on /login.html request but novafile.com will redirect to mainpage thus template
         * loginWebsite handling will fail. Both use /login so this will work for both!
         */
        return getMainPage() + "/login";
    }

    private final void checkErrorsAPIZeusCloudManager(final Browser apibr, final DownloadLink link, final Account account) throws NumberFormatException, PluginException {
        final String error = PluginJSonUtils.getJson(apibr, "error");
        final String message = PluginJSonUtils.getJson(apibr, "message");
        /* 2019-08-21: Special: Waittime errormessage can be in "error" or in "message". */
        final String waittimeRegex = ".*(You have reached the download(\\-| )limit|You have to wait|Wait .*? to download for free|You will not be able to download for).*";
        String wait = !StringUtils.isEmpty(error) ? new Regex(error, waittimeRegex).getMatch(-1) : null;
        if (StringUtils.isEmpty(wait) && !StringUtils.isEmpty(message)) {
            wait = new Regex(message, waittimeRegex).getMatch(-1);
        }
        if (wait != null) {
            /*
             * 2019-08-21: Example free account download:
             * {"error":"You've tried to download from 2 different IPs in the last 3 hours. You will not be able to download for 3 hours." }
             */
            // String wait = new Regex(message, "(You will not be able to download for.+)").getMatch(0);
            // if (wait == null) {
            // wait = message;
            // }
            final String tmphrs = new Regex(wait, "\\s+(\\d+)\\s+hours?").getMatch(0);
            final String tmpmin = new Regex(wait, "\\s+(\\d+)\\s+minutes?").getMatch(0);
            final String tmpsec = new Regex(wait, "\\s+(\\d+)\\s+seconds?").getMatch(0);
            final String tmpdays = new Regex(wait, "\\s+(\\d+)\\s+days?").getMatch(0);
            int waittime;
            if (tmphrs == null && tmpmin == null && tmpsec == null && tmpdays == null) {
                logger.info("Waittime RegExes seem to be broken - using default waittime");
                waittime = 60 * 60 * 1000;
            } else {
                int minutes = 0, seconds = 0, hours = 0, days = 0;
                if (tmphrs != null) {
                    hours = Integer.parseInt(tmphrs);
                }
                if (tmpmin != null) {
                    minutes = Integer.parseInt(tmpmin);
                }
                if (tmpsec != null) {
                    seconds = Integer.parseInt(tmpsec);
                }
                if (tmpdays != null) {
                    days = Integer.parseInt(tmpdays);
                }
                waittime = ((days * 24 * 3600) + (3600 * hours) + (60 * minutes) + seconds + 1) * 1000;
            }
            logger.info("Detected reconnect waittime (milliseconds): " + waittime);
            /* Not enough wait time to reconnect -> Wait short and retry */
            if (waittime < 180000) {
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Wait until new downloads can be started", waittime);
            } else if (account != null) {
                throw new AccountUnavailableException("Download limit reached", waittime);
            } else {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, waittime);
            }
        }
        if (!StringUtils.isEmpty(error)) {
            if (error.equalsIgnoreCase("Login failed")) {
                /* This should only happen on login attempt via email/username & password */
                /* Previous session should not exist when this error happens - dump it anyways if it does! */
                this.dumpAPISessionInfo(account);
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
            } else if (error.equalsIgnoreCase("invalid session")) {
                invalidateAPIZeusCloudManagerSession(account);
            } else if (error.equalsIgnoreCase("no file")) {
                /* 2019-08-20: E.g. Request download1 or download2 of offline file via API */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (error.equalsIgnoreCase("Download session expired")) {
                /* 2019-08-20: E.g. Request download2 with old/invalid 'download_id' value via API. This should usually never happen! */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, error, 1 * 60 * 1000l);
            } else if (error.equalsIgnoreCase("Skipped countdown")) {
                /* This should never happen */
                throw new PluginException(LinkStatus.ERROR_FATAL);
            } else if (error.equalsIgnoreCase("hack activity detected")) {
                /*
                 * 2019-08-23: filejoker.net - basically an IP-block - login is blocked until user tries with new IP. It should work again
                 * then! This can be triggered by messing with their website, frequently logging-in (creating new login-sessions) and trying
                 * again with new Cloudflare-cookies frequently.
                 */
                throw new AccountUnavailableException("Try again later: API error '" + error + "'", 15 * 60 * 1000l);
            } else if (error.equalsIgnoreCase("API downloads disabled for you")) {
                /*
                 * 2020-01-16: novafile.com (premium, download1)
                 */
                tempDisableAPI(account, error);
            } else if (error.equalsIgnoreCase("API download disabled for your account")) {
                /*
                 * 2020-01-16: filejoker.com (premium, download1)
                 */
                tempDisableAPI(account, error);
            } else if (error.equalsIgnoreCase("session error")) {
                /*
                 * 2020-01-16: novafile.com (free, download2)
                 */
                tempDisableAPI(account, error);
            } else {
                /* This should not happen. If it does, improve errorhandling! */
                logger.warning("Unknown API error:" + error);
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "Unknown API error: " + error, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            }
        } else if (!StringUtils.isEmpty(message)) {
            if (message.contains("This file can only be downloaded by Premium")) {
                /*
                 * 2019-08-21: novafile.com: E.g. {"file_size":"500000000","file_name":"test.dat","file_code":"xxxxxxxxxxxx", "message":
                 * "<strong>This file can only be downloaded by Premium Members 400 MB.<br>Become a <a href='#tariffs'>Premium Member</a> and download any files instantly at maximum speed!</strong>"
                 * }
                 */
                throw new AccountRequiredException();
            } else if (message.equalsIgnoreCase("Premium Only file")) {
                /* 2020-05-19: novafile.com */
                /* {"file_size":"500000000","file_name":"test.dat","file_code":"xxxxxxxxxxxx","message":"Premium Only file"} */
                throw new AccountRequiredException();
            }
            logger.warning("Possibly unhandled API errormessage: " + message);
        }
        checkResponseCodeErrors(apibr.getHttpConnection());
    }

    private final String getAPIZeusCloudManagerSession(final Account account) {
        synchronized (account) {
            final String api_sessionid = account.getStringProperty(PROPERTY_SESSIONID, null);
            /* 2019-09-12: Indeed website xfss cookie will also work as API sessionid but let's not use that for now! */
            // if (api_sessionid == null) {
            // final Cookies cookies = account.loadCookies("");
            // if (cookies != null) {
            // api_sessionid = cookies.get("xfss").getValue();
            // }
            // }
            return api_sessionid;
        }
    }

    private final void invalidateAPIZeusCloudManagerSession(final Account account) throws PluginException {
        dumpAPISessionInfo(account);
        throw new AccountUnavailableException("Invalid sessionid", 5 * 60 * 1000l);
    }

    protected void dumpAPISessionInfo(final Account account) {
        synchronized (account) {
            /*
             * Check if API cookies and website cookies are the same --> If so, delete both so that they will not be checked again in
             * website mode! Cookies from website may slightly differ (they will e.g. contain language cookie) which is why we compare
             * simply via sessionid ("xfss") cookie!
             */
            final Cookies cookies_website = account.loadCookies("");
            final Cookies cookies_api = account.loadCookies(PROPERTY_COOKIES_API);
            final Cookie sessionid_website = cookies_website != null ? cookies_website.get("xfss", Cookies.NOTDELETEDPATTERN) : null;
            final Cookie sessionid_api = cookies_api != null ? cookies_api.get("xfss", Cookies.NOTDELETEDPATTERN) : null;
            if (sessionid_website != null && sessionid_api != null && StringUtils.equals(sessionid_website.getValue(), sessionid_api.getValue())) {
                /* Delete website cookies only if sessionid == API sessionid */
                logger.info("clearNormalCookies:sessionid=" + sessionid_website.getValue());
                account.clearCookies("");
            }
            logger.info("clearAPICookies");
            /* Delete API cookies */
            account.clearCookies(PROPERTY_COOKIES_API);
            /* Delete API session */
            account.removeProperty(PROPERTY_SESSIONID);
        }
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
        /* 2019-08-23: Debugtest */
        // link.removeProperty("freelink2");
        // link.removeProperty("premlink");
        // link.removeProperty("freelink");
    }
}