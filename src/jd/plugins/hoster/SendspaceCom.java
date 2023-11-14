//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.captcha.v2.challenge.recaptcha.v1.Recaptcha;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.Property;
import jd.config.SubConfiguration;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.JDHash;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision: 48459 $", interfaceVersion = 2, names = { "sendspace.com" }, urls = { "https?://(\\w+\\.)?sendspace\\.com/(file|pro/dl)/[0-9a-zA-Z]+" })
public class SendspaceCom extends PluginForHost {
    public SendspaceCom(PluginWrapper wrapper) {
        super(wrapper);
        enablePremium("https://www.sendspace.com/joinpro_pay.html");
        setConfigElements();
        setStartIntervall(5000l);
    }

    @Override
    public Browser createNewBrowserInstance() {
        final Browser br = super.createNewBrowserInstance();
        br.getHeaders().put("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:16.0) Gecko/20100101 Firefox/16.0");
        br.setFollowRedirects(true);
        return br;
    }

    private final static String SSL_CONNECTION    = "SSL_CONNECTION";
    private final String        JDOWNLOADERAPIKEY = "T1U5ODVNT1FDTQ==";
    private static final String API_BASE          = "https://api.sendspace.com/rest/";
    // private static final String JDUSERNAME = "cHNwem9ja2Vyc2NlbmVqZA==";
    private String              CURRENTERRORCODE;
    private String              SESSIONTOKEN;
    private String              SESSIONKEY;
    private static Object       ctrlLock          = new Object();
    private static AtomicLong   ctrlLast          = new AtomicLong(0);

    // TODO: Add handling for password protected files for handle premium,
    // actually it only works for handle free
    /**
     * For premium we use their API: http://www.sendspace.com/dev_method.html
     */
    /**
     * Usage: create a token, log in and then use the functions via method "apiRequest(String url, String data)
     */
    @Override
    public String getAGBLink() {
        return "https://www.sendspace.com/terms.html";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return 1;
    }

    private String getContentURL(final DownloadLink link) {
        final String urlpath = new Regex(link.getPluginPatternMatcher(), "(?i)https?://[^/]+/(.+)").getMatch(0);
        return "https://www." + this.getHost() + "/" + urlpath;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, false);
    }

    public AvailableStatus requestFileInformation(final DownloadLink link, final boolean isDownload) throws Exception {
        this.setBrowserExclusive();
        // createSessToken();
        // final Account aa =
        // AccountController.getInstance().getValidAccount(this);
        // if (aa != null) {
        // apiLogin(aa.getUser(), JDHash.getMD5(SESSIONTOKEN +
        // JDHash.getMD5(aa.getPass()).toLowerCase()));
        // } else {
        // apiLogin(Encoding.Base64Decode(JDUSERNAME),
        // JDHash.getMD5(SESSIONTOKEN +
        // JDHash.getMD5("JD Account Password").toLowerCase()));
        // }
        // apiRequest("http://api.sendspace.com/rest/?method=files.getinfo",
        // "&session_key=" + SESSIONKEY + "&file_id=" +
        // getFid(downloadLink));
        // if (br.containsHTML("<error code=\"9\"")) throw new
        // PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        // final String filename =
        // br.getRegex("name=\"([^<>\"]*?)\"").getMatch(0);
        // final String filesize =
        // br.getRegex("file_size=\"(\\d+)\"").getMatch(0);
        // if (filename != null && filename != null) {
        // downloadLink.setFinalFileName(filename);
        // downloadLink.setDownloadSize(Long.parseLong(filesize));
        // return AvailableStatus.TRUE;
        // } else {
        // return handleOldAvailableStatus(downloadLink);
        // }
        return handleOldAvailableStatus(link, isDownload);
    }

    private AvailableStatus handleOldAvailableStatus(final DownloadLink link, final boolean isDownload) throws Exception {
        // one thread at a time, with delay!
        synchronized (ctrlLock) {
            try {
                if (ctrlLast.get() != 0) {
                    final long t = System.currentTimeMillis() - ctrlLast.get();
                    final long w = 2500;
                    if (t <= w) {
                        Thread.sleep(w - t);
                    }
                }
                String contenturl = this.getContentURL(link);
                if (contenturl.contains("/pro/dl/")) {
                    URLConnectionAdapter con = null;
                    try {
                        con = br.openGetConnection(contenturl);
                        if (!this.looksLikeDownloadableContent(con)) {
                            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                        }
                        final String filenameFromHeader = getFileNameFromHeader(con);
                        if (filenameFromHeader != null) {
                            link.setName(Encoding.htmlDecode(filenameFromHeader));
                        }
                        if (con.getCompleteContentLength() > 0) {
                            link.setVerifiedFileSize(con.getCompleteContentLength());
                        }
                        return AvailableStatus.TRUE;
                    } finally {
                        try {
                            con.disconnect();
                        } catch (Throwable e) {
                        }
                    }
                } else {
                    getPage(this.br, contenturl);
                    final Form securityform = getSecurityForm();
                    if (br.containsHTML("The page you are looking for is  not available\\. It has either been moved")) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    } else if (br.containsHTML("the file you requested is not available")) {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                    if (securityform != null) {
                        final boolean userOverrideCaptchaBehavior = this.getPluginConfig().getBooleanProperty("ALLOW_CAPTCHA_DURING_LINKCHECK", false);
                        if (!isDownload && !userOverrideCaptchaBehavior) {
                            logger.info("Cannot check URL because of anti-DDoS captcha");
                            return AvailableStatus.UNCHECKABLE;
                        } else {
                            /* Handle anti bot captcha */
                            logger.info("Handling security Form");
                            final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
                            securityform.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                            br.submitForm(securityform);
                            if (getSecurityForm() != null) {
                                /* This should never happen. */
                                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Failed to pass anti bot captcha");
                            }
                        }
                    }
                    String[] infos = br.getRegex("<b>Name:</b>(.*?)<br><b>Size:</b>(.*?)<br>").getRow(0);/* old */
                    if (infos == null) {
                        infos = br.getRegex("Download: <strong>(.*?)<.*?strong> \\((.*?)\\)<").getRow(0);/* new1 */
                    }
                    if (infos == null) {
                        infos = br.getRegex("Download <b>(.*?)<.*?File Size: (.*?)<").getRow(0);/* new2 */
                    }
                    if (infos != null) {
                        /* old format */
                        link.setName(Encoding.htmlDecode(infos[0]).trim());
                        link.setDownloadSize(SizeFormatter.getSize(infos[1].trim().replaceAll(",", "\\.")));
                        return AvailableStatus.TRUE;
                    } else {
                        String filename = br.getRegex("<title>Download ([^<>/\"]*?) from Sendspace\\.com \\- send big files the easy way</title>").getMatch(0);
                        if (filename == null) {
                            filename = br.getRegex("<h2 class=\"bgray\"><b>(.*?)</b></h2>").getMatch(0);
                            if (filename == null) {
                                filename = br.getRegex("title=\"download (.*?)\">Click here to start").getMatch(0);
                            }
                        }
                        String filesize = br.getRegex("<b>File Size:</b> (.*?)</div>").getMatch(0);
                        if (filename != null) {
                            link.setName(Encoding.htmlDecode(filename).trim());
                            if (filesize != null) {
                                link.setDownloadSize(SizeFormatter.getSize(filesize.trim().replaceAll(",", ".")));
                            }
                            return AvailableStatus.TRUE;
                        }
                    }
                    if (br.containsHTML("No htmlCode read")) {
                        /* 2020-03-11: TODO: Check if this is still required */
                        // No html content??? maybe server problem
                        // seems like a firewall block.
                        Thread.sleep(90000);
                        return requestFileInformation(link);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                }
            } finally {
                ctrlLast.set(System.currentTimeMillis());
            }
        }
    }

    private Form getSecurityForm() {
        if (br.containsHTML(">\\s*Please complete the form below")) {
            final Form[] forms = br.getForms();
            for (final Form thisform : forms) {
                if (thisform.containsHTML("class=\"g-recaptcha\"")) {
                    return thisform;
                }
            }
        }
        return null;
    }

    private void handleErrors(boolean plugindefect) throws PluginException {
        String error = br.getRegex("<div class=\"errorbox-bad\".*?>(.*?)</div>").getMatch(0);
        if (error == null) {
            error = br.getRegex("<div class=\"errorbox-bad\".*?>.*?>(.*?)</>").getMatch(0);
        }
        if (error == null && !plugindefect) {
            return;
        }
        if (error == null) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.sendspacecom.errors.servererror", "Unknown server error"), 5 * 60 * 1000l);
        }
        logger.severe("Error: " + error);
        if (error.contains("You cannot download more than one file at a time")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "A download is still in progress", 10 * 60 * 1000l);
        }
        if (error.contains("You may now download the file")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, error, 30 * 1000l);
        }
        if (error.contains("full capacity")) {
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.sendspacecom.errors.serverfull", "Free service capacity full"), 5 * 60 * 1000l);
        }
        if (error.contains("this connection has reached the")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, 60 * 60 * 1000);
        }
        if (error.contains("reached daily download") || error.contains("reached your daily download")) {
            int wait = 60;
            String untilh = br.getRegex("again in (\\d+)h:(\\d+)m").getMatch(0);
            String untilm = br.getRegex("again in (\\d+)h:(\\d+)m").getMatch(1);
            if (untilh != null) {
                wait = Integer.parseInt(untilh) * 60;
            }
            if (untilm != null && untilh != null) {
                wait = wait + Integer.parseInt(untilm);
            }
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "You have reached your daily download limit", wait * 60 * 1000l);
        }
        if (br.containsHTML("(>The file is not currently available|Our support staff have been notified and we hope to resolve the problem shortly)")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.sendspacecom.errors.temporaryunavailable", "This file is not available at the moment!"));
        }
        if (plugindefect) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    @Override
    public void handleFree(DownloadLink link) throws Exception {
        final String contenturl = this.getContentURL(link);
        if (contenturl.contains("/pro/dl/")) {
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, contenturl, true, 0);
            final URLConnectionAdapter con = dl.getConnection();
            if (!this.looksLikeDownloadableContent(con)) {
                br.followConnection(true);
                handleErrors(true);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        } else {
            // Re-use old directlinks to avoid captchas, especially good after
            // reconnects
            String linkurl = checkDirectLink(link, "savedlink");
            if (linkurl == null) {
                requestFileInformation(link, true);
                if (br.containsHTML("You have reached your daily download limit")) {
                    int minutes = 0, hours = 0;
                    final Regex waitregex = br.getRegex("again in\\s*(\\d+)h.*?(\\d+)m");
                    final String tmphrsStr = waitregex.getMatch(0);
                    if (tmphrsStr != null) {
                        hours = Integer.parseInt(tmphrsStr);
                    }
                    final String tmpminStr = waitregex.getMatch(1);
                    if (tmpminStr != null) {
                        minutes = Integer.parseInt(tmpminStr);
                    }
                    int waittime = ((3600 * hours) + (60 * minutes) + 1) * 1000;
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, waittime);
                }
                // Password protected links handling
                String passCode = null;
                if (br.containsHTML("name=\"filepassword\"")) {
                    logger.info("This link seems to be püassword protected...");
                    for (int i = 0; i < 2; i++) {
                        Form pwform = br.getFormbyKey("filepassword");
                        if (pwform == null) {
                            pwform = br.getForm(0);
                        }
                        if (pwform == null) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                        }
                        if (link.getDownloadPassword() == null) {
                            passCode = getUserInput("Password?", link);
                        } else {
                            /* gespeicherten PassCode holen */
                            passCode = link.getDownloadPassword();
                        }
                        pwform.put("filepassword", passCode);
                        br.submitForm(pwform);
                        if (br.containsHTML("(name=\"filepassword\"|Incorrect Password)")) {
                            continue;
                        }
                        break;
                    }
                    if (br.containsHTML("(name=\"filepassword\"|Incorrect Password)")) {
                        throw new PluginException(LinkStatus.ERROR_FATAL, "Wrong Password");
                    }
                }
                /* handle captcha */
                if (br.containsHTML(regexRecaptcha)) {
                    final Recaptcha rc = new Recaptcha(br, this);
                    rc.parse();
                    rc.load();
                    String id = br.getRegex("\\?k=([A-Za-z0-9%_\\+\\- ]+)\"").getMatch(0);
                    rc.setId(id);
                    final int repeat = 5;
                    for (int i = 0; i <= repeat; i++) {
                        File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                        String c = getCaptchaCode("recaptcha", cf, link);
                        rc.setCode(c);
                        if (br.containsHTML(regexRecaptcha)) {
                            if (i + 1 >= repeat) {
                                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                            } else {
                                rc.reload();
                                continue;
                            }
                        } else {
                            break;
                        }
                    }
                }
                handleErrors(false);
                /* Link holen */
                linkurl = br.getRegex("<a id=\"download_button\" href=\"(https?://.*?)\"").getMatch(0);
                if (linkurl == null) {
                    linkurl = br.getRegex("\"(https?://fs\\d+n\\d+\\.sendspace\\.com/dl/[a-z0-9]+/[a-z0-9]+/[a-z0-9]+/.*?)\"").getMatch(0);
                }
                if (linkurl == null) {
                    if (br.containsHTML("has reached the 300MB hourly download")) {
                        throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 60 * 60 * 1000l);
                    }
                    logger.warning("linkurl equals null");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                if (passCode != null) {
                    link.setDownloadPassword(passCode);
                }
            }
            /* Datei herunterladen */
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, linkurl, true, 1);
            final URLConnectionAdapter con = dl.getConnection();
            if (!this.looksLikeDownloadableContent(con)) {
                br.followConnection(true);
                handleErrors(true);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            } else if (con.getResponseCode() == 416) {
                // HTTP/1.1 416 Requested Range Not Satisfiable
                con.disconnect();
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 30 * 1000l);
            } else if (con.getResponseCode() != 200 && con.getResponseCode() != 206) {
                con.disconnect();
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 10 * 60 * 1000l);
            }
            link.setProperty("savedlink", linkurl);
        }
        dl.startDownload();
    }

    private final String regexRecaptcha = "api\\.recaptcha\\.net|google\\.com/recaptcha/api/";

    private String checkDirectLink(DownloadLink downloadLink, String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            try {
                Browser br2 = br.cloneBrowser();
                URLConnectionAdapter con = br2.openGetConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
                con.disconnect();
            } catch (Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            }
        }
        return dllink;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        /* 2020-03-11: Do NOT use website check anymore as it can cause captchas --> Rely 100% on API */
        // requestFileInformation(link, true);
        login(account);
        final String contenturl = this.getContentURL(link);
        try {
            apiRequest(API_BASE + "?method=download.getinfo", "&session_key=" + SESSIONKEY + "&file_id=" + Encoding.urlEncode(contenturl));
        } catch (final Exception e) {
            logger.info("Unexpected error while trying to download, maybe old sessionkey, logging in again...");
            login(account);
            apiRequest(API_BASE + "?method=download.getinfo", "&session_key=" + SESSIONKEY + "&file_id=" + Encoding.urlEncode(contenturl));
        }
        String linkurl = br.getRegex("url=\"(https?[^<>\"]*?)\"").getMatch(0);
        if (linkurl == null) {
            logger.warning("Final downloadlink couldn't be found!");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, linkurl, true, 1);
        if (dl.getConnection().getContentType().contains("html")) {
            logger.warning("Received html code, stopping...");
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error");
        }
        dl.startDownload();
    }

    @Override
    public void init() {
        Browser.setRequestIntervalLimitGlobal(this.getHost(), 750);
    }

    public void login(final Account account) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                boolean loggedIN = false;
                if (cookies != null) {
                    this.br.setCookies(this.getHost(), cookies);
                    SESSIONTOKEN = account.getStringProperty("sessiontoken", null);
                    SESSIONKEY = account.getStringProperty("sessionkey", null);
                    loggedIN = this.sessionOk();
                }
                if (!loggedIN) {
                    createSessToken();
                    apiLogin(account.getUser(), account.getPass());
                    account.setProperty("sessiontoken", SESSIONTOKEN);
                    account.setProperty("sessionkey", SESSIONKEY);
                }
                account.saveCookies(this.br.getCookies(this.getHost()), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                    account.setProperty("sessiontoken", Property.NULL);
                    account.setProperty("sessionkey", Property.NULL);
                }
                throw e;
            }
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        this.setBrowserExclusive();
        try {
            login(account);
        } catch (final PluginException e) {
            throw e;
        }
        /* If loggedin via older token and not via full login, we need to call this to get accountinfo. */
        apiLogin(account.getUser(), account.getPass());
        String accounttype = get("membership_type");
        if (StringUtils.isEmpty(accounttype) || "Lite".equals(accounttype)) {
            /* Users can't really do anything with free accounts. */
            account.setType(AccountType.FREE);
            account.setConcurrentUsePossible(false);
            ai.setTrafficLeft(0);
        } else {
            account.setType(AccountType.PREMIUM);
            account.setConcurrentUsePossible(true);
            final String expires = get("membership_ends");
            if (expires != null) {
                ai.setValidUntil(System.currentTimeMillis() + Long.parseLong(expires));
            } else {
                apiFailure("membership_ends");
                return ai;
            }
            final String left = get("bandwidth_left");
            if (left != null) {
                ai.setTrafficLeft(Long.parseLong(left));
            } else {
                apiFailure("bandwidth_left");
                return ai;
            }
        }
        String spaceUsed = get("diskspace_used");
        if (spaceUsed != null) {
            if (spaceUsed.equals("")) {
                ai.setUsedSpace(0);
            } else {
                ai.setUsedSpace(Long.parseLong(spaceUsed));
            }
        }
        if (StringUtils.isEmpty(accounttype)) {
            accounttype = "Unknown";
        }
        ai.setStatus("Account type: " + accounttype);
        return ai;
    }

    private String get(final String parameter) {
        return br.getRegex("<" + parameter + ">([^<>\"]*?)</" + parameter + ">").getMatch(0);
    }

    private String getErrorcode() {
        return br.getRegex("<error code=\"(\\d+)\"").getMatch(0);
    }

    private void apiFailure(final String parameter) {
        logger.warning("API failure: " + parameter + " is null");
    }

    private void createSessToken() throws Exception {
        apiRequest("https://api.sendspace.com/rest/", "?method=auth.createtoken&api_key=" + Encoding.Base64Decode(JDOWNLOADERAPIKEY) + "&api_version=1.0&response_format=xml&app_version=0.1");
        SESSIONTOKEN = get("token");
        if (SESSIONTOKEN == null) {
            logger.warning("sessiontoken could not be found!");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    /** https://www.sendspace.com/dev_method.html?method=auth.checksession */
    private boolean sessionOk() {
        try {
            apiRequest(API_BASE, "?method=auth.checksession&session_key=" + SESSIONKEY);
            if ("ok".equals(get("session"))) {
                return true;
            } else {
                return false;
            }
        } catch (final Exception e) {
            logger.log(e);
            return false;
        }
    }

    private void apiRequest(final String parameter, final String data) throws Exception {
        getPage(this.br, parameter + data);
        handleAPIErrors();
    }

    private void apiLogin(final String username, final String password) throws Exception {
        apiRequest(API_BASE, "?method=auth.login&token=" + SESSIONTOKEN + "&user_name=" + username + "&tokened_password=" + JDHash.getMD5(SESSIONTOKEN + JDHash.getMD5(password).toLowerCase()));
        SESSIONKEY = get("session_key");
        if (SESSIONKEY == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    private void handleAPIErrors() throws PluginException {
        CURRENTERRORCODE = getErrorcode();
        if (CURRENTERRORCODE != null) {
            final int error = Integer.parseInt(CURRENTERRORCODE);
            switch (error) {
            case 5:
                logger.warning("API_ERROR_BAD_API_VERSION");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            case 6:
                logger.warning("API_ERROR_SESSION_BAD");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            case 7:
                logger.warning("Session not authenticated");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            case 8:
                logger.warning("API_ERROR_AUTHENTICATION_FAILURE");
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
            case 9:
                logger.info("API_ERROR_FILE_NOT_FOUND");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            case 11:
                logger.warning("API_ERROR_PERMISSION_DENIED");
            case 12:
                logger.warning("API_ERROR_DOWNLOAD_TEMP_ERROR");
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 30 * 60 * 1000l);
            case 18:
                logger.warning("Unknown API key");
            case 19:
                logger.warning("API_ERROR_PRO_EXPIRED");
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
            case 22:
                logger.info("API_ERROR_BAD_PASSWORD");
                throw new PluginException(LinkStatus.ERROR_FATAL, "Link is password protected");
            case 23:
                logger.info("API_ERROR_BANDWIDTH_LIMIT");
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
            case 25:
                logger.warning("API_ERROR_OUTDATED_VERSION");
            case 26:
                logger.warning("API_ERROR_INVALID_FILE_URL");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            case 30:
                logger.warning("API ERROR 30: Too many sessions open for account (re-login required, temp. disabling account)");
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            default:
                logger.warning("Unknown API errorcode: " + CURRENTERRORCODE);
                logger.warning("HTML code: " + br.toString());
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    private void getPage(final Browser br, String page) throws Exception {
        page = fixLinkSSL(page);
        br.getPage(page);
    }

    private static String fixLinkSSL(String link) {
        if (checkSsl()) {
            link = link.replace("http://", "https://");
        } else {
            // link = link.replace("https://", "http://"); // https is enforced
        }
        return link;
    }

    private static boolean checkSsl() {
        return SubConfiguration.getConfig("sendspace.com").getBooleanProperty(SSL_CONNECTION, false);
    }

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), SSL_CONNECTION, "Use Secure Communication over SSL (HTTPS://)").setDefaultValue(false));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, this.getPluginConfig(), "ALLOW_CAPTCHA_DURING_LINKCHECK", "Allow captchas during linkcheck?").setDefaultValue(false));
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        if (acc != null && AccountType.PREMIUM.equals(acc.getType())) {
            return false;
        } else {
            return true;
        }
    }
}