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

import java.awt.Color;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JLabel;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.swing.MigPanel;
import org.appwork.swing.components.ExtPasswordField;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.gui.InputChangedCallbackInterface;
import org.jdownloader.plugins.accounts.AccountBuilderInterface;
import org.jdownloader.plugins.components.antiDDoSForHost;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.gui.swing.components.linkbutton.JLink;
import jd.http.Browser;
import jd.http.Cookies;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountRequiredException;
import jd.plugins.AccountUnavailableException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision: 48194 $", interfaceVersion = 3, names = { "nexusmods.com" }, urls = { "https?://(?:www\\.)?nexusmods\\.com+/Core/Libs/Common/Widgets/DownloadPopUp\\?id=(\\d+).+|nxm://([^/]+)/mods/(\\d+)/files/(\\d+)\\?key=([a-zA-Z0-9_/\\+\\=\\-%]+)\\&expires=(\\d+)\\&user_id=\\d+" })
public class NexusmodsCom extends antiDDoSForHost {
    public NexusmodsCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://users.nexusmods.com/register/memberships");
    }

    @Override
    public String getAGBLink() {
        return "https://help.nexusmods.com/article/18-terms-of-service";
    }

    /* Connection stuff */
    private final int          FREE_MAXDOWNLOADS            = -1;
    private static final int   ACCOUNT_PREMIUM_MAXDOWNLOADS = -1;
    /* API documentation: https://app.swaggerhub.com/apis-docs/NexusMods/nexus-mods_public_api_params_in_form_data/1.0 */
    public static final String API_BASE                     = "https://api.nexusmods.com/v1";
    public static final String PROPERTY_game_domain_name    = "game_domain_name";
    public static final String PROPERTY_mod_id              = "mod_id";
    private String             dllink;

    @Override
    public String getLinkID(final DownloadLink link) {
        final String file_id = getFileID(link);
        if (file_id != null) {
            final String linkid = this.getHost() + "://" + file_id;
            if (this.isSpecialNexusModmanagerDownloadURL(link)) {
                /*
                 * Free (account) users can add URLs which can expire. Allow them to add them again with new download_key parameters to make
                 * this easier.
                 */
                String dlKey = null;
                try {
                    dlKey = UrlQuery.parse(link.getPluginPatternMatcher()).get("key");
                } catch (final MalformedURLException ignore) {
                }
                return linkid + "_" + dlKey;
            } else {
                return linkid;
            }
        } else {
            return super.getLinkID(link);
        }
    }

    /* URLs for their official downloadmanager --> We support them too --> Also see linkIsAPICompatible */
    private boolean isSpecialNexusModmanagerDownloadURL(final DownloadLink link) {
        return link.getPluginPatternMatcher() != null && link.getPluginPatternMatcher().matches("nxm://.+");
    }

    /** Returns the file_id (this comment is here as the URL contains multiple IDs) */
    private String getFileID(final DownloadLink link) {
        if (isSpecialNexusModmanagerDownloadURL(link)) {
            return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(3);
        } else {
            return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
        }
    }

    public static Browser prepBrAPI(final Browser br, final Account account) throws PluginException {
        prepBRGeneral(br);
        br.getHeaders().put("User-Agent", "JDownloader");
        final String apikey = getApikey(account);
        if (apikey == null) {
            /* This should never happen */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        br.getHeaders().put("apikey", apikey);
        return br;
    }

    public static boolean isOfflineWebsite(final Browser br) {
        return br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("(?i)No files have been uploaded yet|>File not found<|>Not found<|/noimage-1.png");
    }

    @Override
    public void correctDownloadLink(final DownloadLink link) throws Exception {
        if (this.isSpecialNexusModmanagerDownloadURL(link)) {
            final String gameDomainName = new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(1);
            final String modID = new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(2);
            link.setProperty(PROPERTY_game_domain_name, gameDomainName);
            link.setProperty(PROPERTY_mod_id, modID);
            /* Create a meaningful ContentURL in case the URL expires and the user wants to refresh it later. */
            link.setContentUrl("https://www." + this.getHost() + "/" + gameDomainName + "/mods/" + modID + "?tab=files&file_id=" + this.getFileID(link) + "&nmm=1");
        } else if (StringUtils.contains(link.getPluginPatternMatcher(), "nmm=1")) {
            link.setPluginPatternMatcher(link.getPluginPatternMatcher().replace("nmm=1", "nmm=0"));
        }
    }

    /** Account is either required because the files are 'too large' or also for 'adult files', or for 'no reason at all'. */
    public boolean isLoginRequired(final Browser br) {
        if (br.containsHTML("(?i)<h1>\\s*Error\\s*</h1>") && br.containsHTML("(?i)<h2>\\s*Adult-only content\\s*</h2>")) {
            // adult only content.
            return true;
        } else if (br.containsHTML("(?i)You need to be a member and logged in to download files larger")) {
            // large files
            return true;
        } else if (br.containsHTML("(?i)>\\s*Please login or signup to download this file\\s*<")) {
            return true;
        } else {
            return false;
        }
    }

    /** URLs added <= rev. 41547 are missing properties which are required to do download & linkcheck via API. */
    private boolean linkIsAPICompatible(final DownloadLink link) {
        final String game_domain_name = link.getStringProperty(PROPERTY_game_domain_name);
        final String mod_id = link.getStringProperty(PROPERTY_mod_id);
        if (game_domain_name != null && mod_id != null) {
            return true;
        } else {
            return false;
        }
    }

    private static Browser prepBRGeneral(final Browser br) {
        br.setFollowRedirects(true);
        return br;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        if (!link.isNameSet()) {
            link.setName(this.getFileID(link) + ".zip");
        }
        final Account acc = AccountController.getInstance().getValidAccount(this.getHost());
        final String apikey = getApikey(acc);
        if (acc != null && apikey != null && linkIsAPICompatible(link)) {
            return requestFileInformationAPI(link, acc);
        } else {
            return requestFileInformationWebsite(link);
        }
    }

    private AvailableStatus requestFileInformationWebsite(final DownloadLink link) throws Exception {
        prepBRGeneral(br);
        if (isSpecialNexusModmanagerDownloadURL(link)) {
            /* Cannot check here but let's assume the status by expire param */
            final long expireTimstamp = Long.parseLong(UrlQuery.parse(link.getPluginPatternMatcher()).get("expires")) * 1000;
            if (expireTimstamp < System.currentTimeMillis()) {
                return AvailableStatus.UNCHECKABLE;
            } else {
                final long validFor = expireTimstamp - System.currentTimeMillis();
                logger.info("nxm:// URL shall be valid for another: " + TimeFormatter.formatMilliSeconds(validFor, 1));
                return AvailableStatus.TRUE;
            }
        } else {
            getPage(link.getPluginPatternMatcher());
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            String loadBox = br.getRegex("loadBox\\('(https?://.*?)'").getMatch(0);
            if (loadBox != null) {
                getPage(loadBox);
                loadBox = br.getRegex("loadBox\\('(https?://.*?skipdonate)'").getMatch(0);
                if (loadBox != null) {
                    getPage(loadBox);
                }
            }
            dllink = br.getRegex("window\\.location\\.href\\s*=\\s*\"(http[^<>\"]+)\";").getMatch(0);
            if (dllink == null) {
                dllink = br.getRegex("\"(https?://filedelivery\\.nexusmods\\.com/[^<>\"]+)\"").getMatch(0);
                if (dllink == null) {
                    dllink = br.getRegex("\"(https?://(?:www\\.)?nexusmods\\.com/[^<>\"]*Libs/Common/Managers/Downloads\\?Download[^<>\"]+)\"").getMatch(0);
                    if (dllink == null) {
                        dllink = br.getRegex("id\\s*=\\s*\"dl_link\"\\s*value\\s*=\\s*\"(https?://[^<>\"]*?)\"").getMatch(0);
                        if (dllink == null) {
                            dllink = br.getRegex("data-link\\s*=\\s*\"(https?://(?:premium-files|fs-[a-z0-9]+)\\.(?:nexusmods|nexus-cdn)\\.com/[^<>\"]*?)\"").getMatch(0);
                        }
                    }
                }
            }
            String filename = br.getRegex("filedelivery\\.nexusmods\\.com/\\d+/([^<>\"]+)\\?fid=").getMatch(0);
            if (filename == null) {
                filename = br.getRegex("data-link\\s*=\\s*\"https?://[^<>\"]+/files/\\d+/([^<>\"/]+)\\?").getMatch(0);
                if (filename == null) {
                    filename = br.getRegex("data-link\\s*=\\s*\"https?://[^<>\"]+/\\d+/\\d+/([^<>\"/]+)\\?").getMatch(0);
                    if (filename == null && dllink != null) {
                        filename = getFileNameFromURL(new URL(dllink));
                    }
                }
            }
            if (filename != null) {
                filename = Encoding.htmlDecode(filename).trim();
                link.setName(filename);
            }
            return AvailableStatus.TRUE;
        }
    }

    private AvailableStatus requestFileInformationAPI(final DownloadLink link, final Account account) throws Exception {
        prepBrAPI(br, account);
        final String game_domain_name = link.getStringProperty(PROPERTY_game_domain_name);
        final String mod_id = link.getStringProperty(PROPERTY_mod_id);
        final String file_id = this.getFileID(link);
        if (file_id == null || mod_id == null || game_domain_name == null) {
            /* This should never happen */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        getPage(API_BASE + String.format("/games/%s/mods/%s/files/%s", game_domain_name, mod_id, file_id));
        handleErrorsAPI(br);
        final Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(br.getRequest().getHtmlCode());
        return setFileInformationAPI(link, entries, game_domain_name, mod_id, file_id);
    }

    public static AvailableStatus setFileInformationAPI(final DownloadLink link, final Map<String, Object> entries, final String game_domain_name, final String mod_id, final String file_id) throws Exception {
        String filename = (String) entries.get("file_name");
        final Number size_in_bytes = (Number) entries.get("size_in_bytes");
        if (!StringUtils.isEmpty(filename)) {
            link.setFinalFileName(filename);
        } else {
            /* Fallback */
            filename = game_domain_name + "_" + mod_id + "_" + file_id;
            link.setName(filename);
        }
        if (size_in_bytes != null) {
            link.setVerifiedFileSize(size_in_bytes.longValue());
        }
        final String description = (String) entries.get("description");
        if (!StringUtils.isEmpty(description) && StringUtils.isEmpty(link.getComment())) {
            link.setComment(description);
        }
        /* TODO: Add free account (error-) handling */
        // loginRequired = isLoginRequired(br);
        /* For some files we can find a sha256 hash through this sketchy hash. Example: TODO: Add example */
        final String external_virus_scan_url = (String) entries.get("external_virus_scan_url");
        if (external_virus_scan_url != null) {
            final String sha256_hash = new Regex(external_virus_scan_url, "(?i)virustotal\\.com/(?:gui/)?file/([a-f0-9]+)/.*").getMatch(0);
            if (sha256_hash != null) {
                link.setSha256Hash(sha256_hash);
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        handleDownload(link, null);
    }

    private void handleDownload(final DownloadLink link, final Account account) throws Exception, PluginException {
        if (StringUtils.isEmpty(dllink)) {
            if (this.isLoginRequired(br)) {
                if (account == null) {
                    throw new AccountRequiredException();
                } else {
                    /*
                     * 2019-01-23: Added errorhandling but this should never happen because if an account exists we should be able to
                     * download!
                     */
                    throw new AccountUnavailableException("Session expired?", 5 * 60 * 1000l);
                }
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = new jd.plugins.BrowserAdapter().openDownload(br, link, dllink, this.isResumeable(link, account), getMaxChunks(account));
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            if (this.isSpecialNexusModmanagerDownloadURL(link)) {
                /* Most likely that downloadurl is expired so user has to delete- and re-add it! */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "URL expired?");
            } else {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error");
            }
        }
        if (link.getFinalFileName() == null) {
            /* Workaround for rare case if e.g. account was not available when a (nxm://) URL has been added initially. */
            if (dl.getConnection().isContentDisposition()) {
                dl.setFilenameFix(true);
            } else {
                final String filename = new Regex(dl.getConnection().getURL().toString(), "/([^/]+)\\?").getMatch(0);
                if (filename != null) {
                    link.setFinalFileName(Encoding.htmlDecode(filename).trim());
                }
            }
        }
        dl.startDownload();
    }

    public int getMaxChunks(final Account account) {
        return 0;
    }

    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        return true;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Deprecated
    public void loginWebsite(final Account account) throws Exception {
        synchronized (account) {
            try {
                if (isAPIOnlyMode()) {
                    /* This should never happen */
                    throw new AccountInvalidException("Login with username + password is not supported!");
                }
                prepBRGeneral(br);
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                boolean loggedIN = false;
                if (cookies != null) {
                    br.setCookies(account.getHoster(), cookies);
                    getPage("https://www." + account.getHoster());
                    if (!isLoggedinCookies()) {
                        logger.info("Existing login invalid: Full login required!");
                        br.clearCookies(getHost());
                    } else {
                        loggedIN = true;
                    }
                }
                if (!loggedIN) {
                    getPage("https://users." + this.getHost() + "/auth/sign_in");
                    final Form loginform = br.getFormbyKey("user%5Blogin%5D");
                    if (loginform == null) {
                        logger.warning("Failed to find loginform");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    String reCaptchaKey = br.getRegex("grecaptcha\\.execute\\('([^<>\"\\']+)'").getMatch(0);
                    if (reCaptchaKey == null) {
                        /* 2020-01-08: Fallback */
                        reCaptchaKey = "6Lf4vsIUAAAAAN6TyJATjxQbMAcKjBZ3rOc0ijrp";
                    }
                    loginform.put("user%5Blogin%5D", Encoding.urlEncode(account.getUser()));
                    loginform.put("user%5Bpassword%5D", Encoding.urlEncode(account.getPass()));
                    final DownloadLink original = this.getDownloadLink();
                    if (original == null) {
                        this.setDownloadLink(new DownloadLink(this, "Account", getHost(), "http://" + br.getRequest().getURL().getHost(), true));
                    }
                    try {
                        /* 2019-11-20: Invisible reCaptchaV2 is always required */
                        final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br, reCaptchaKey).getToken();
                        if (recaptchaV2Response == null) {
                            throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                        }
                        loginform.put("g-recaptcha-response%5Blogin%5D", Encoding.urlEncode(recaptchaV2Response));
                    } finally {
                        if (original == null) {
                            this.setDownloadLink(null);
                        }
                    }
                    this.submitForm(loginform);
                    if (!isLoggedinCookies()) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "Login failed.\r\nIf you own a premium account you should disable website login in Settings --> Plugin Settings --> nexusmods.com\r\nBe sure to delete your account and try again after changing this setting!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.saveCookies(br.getCookies(account.getHoster()), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    private boolean isLoggedinCookies() {
        return br.getCookie(br.getURL(), "pass_hash", Cookies.NOTDELETEDPATTERN) != null && br.getCookie(br.getURL(), "member_id", Cookies.NOTDELETEDPATTERN) != null && br.getCookie(br.getURL(), "sid", Cookies.NOTDELETEDPATTERN) != null;
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        String apikey = getApikey(account);
        if (apikey != null) {
            return fetchAccountInfoAPI(account);
        }
        /* Old code! Website mode doesn't work anymore! */
        final AccountInfo ai = new AccountInfo();
        loginWebsite(account);
        getPage("/users/myaccount?tab=api%20access");
        /* Try to find apikey - prefer API */
        /* TODO: Maybe generate apikey is it is not yet available */
        /* 2019-11-19: Turned this off as it is nothing that we should do. */
        // Form requestApiForm = null;
        // final Form[] forms = br.getForms();
        // for (final Form tmpForm : forms) {
        // final InputField actionField = tmpForm.getInputFieldByName("action");
        // final InputField application_slugField = tmpForm.getInputFieldByName("application_slug");
        // if (actionField != null && actionField.getValue().equals("request-key") && application_slugField == null) {
        // logger.info("Found 'request apikey' Form");
        // requestApiForm = tmpForm;
        // break;
        // }
        // }
        // if (requestApiForm != null) {
        // logger.info("Requesting apikey for the first time ...");
        // this.submitForm(requestApiForm);
        // }
        apikey = br.getRegex("id=\"personal_key\"[^>]*>([^<>\"]+)<").getMatch(0);
        if (apikey != null) {
            /* TODO: Consider removing original logindata once we found an apikey for safety reasons! */
            logger.info("Found apikey");
            saveApikey(account, apikey);
            return fetchAccountInfoAPI(account);
        } else {
            logger.info("Failed to find apikey - continuing via website");
            getPage("/users/myaccount");
            if (StringUtils.equalsIgnoreCase(br.getRegex("\"premium-desc\">\\s*(.*?)\\s*<").getMatch(0), "Inactive")) {
                account.setType(AccountType.FREE);
                account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
                account.setConcurrentUsePossible(false);
            } else {
                account.setType(AccountType.PREMIUM);
                account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
                account.setConcurrentUsePossible(true);
            }
            return ai;
        }
    }

    private AccountInfo fetchAccountInfoAPI(final Account account) throws Exception {
        if (isAPIOnlyMode()) {
            account.setPass(correctPassword(account.getPass()));
        }
        if (!isAPIKey(getApikey(account))) {
            throw new AccountInvalidException("Invalid API key format");
        }
        prepBrAPI(br, account);
        getPage(API_BASE + "/users/validate.json");
        handleErrorsAPI(br);
        final Map<String, Object> user = restoreFromString(br.toString(), TypeRef.MAP);
        final String email = (String) user.get("email");
        if (!StringUtils.isEmpty(email)) {
            /* User can enter whatever he wants into username field in JDownloader but we want unique usernames. */
            account.setUser(email);
        }
        final AccountInfo ai = new AccountInfo();
        if ((Boolean) user.get("is_premium") == Boolean.TRUE) {
            account.setType(AccountType.PREMIUM);
            account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
            account.setConcurrentUsePossible(true);
            ai.setUnlimitedTraffic();
            ai.setStatus("Premium user");
        } else if ((Boolean) user.get("is_supporter") == Boolean.TRUE) {
            account.setType(AccountType.PREMIUM);
            account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
            account.setConcurrentUsePossible(true);
            ai.setUnlimitedTraffic();
            ai.setStatus("Supporter");
        } else {
            account.setType(AccountType.FREE);
            account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
            account.setConcurrentUsePossible(false);
            if (isAPIOnlyMode()) {
                ai.setStatus("Free user [Only pre generated nxm:// URLs can be downloaded]");
                ai.setUnlimitedTraffic();
            } else {
                ai.setStatus("Free user");
                ai.setTrafficLeft(0);
            }
        }
        return ai;
    }

    public static void handleErrorsAPI(final Browser br) throws PluginException {
        if (br.getHttpConnection().getResponseCode() == 400) {
            /*
             * 2020-01-15: Attempted free account download fails:
             * {"code":400,"message":"Provided key and expire time isn't correct for this user/file."}
             */
            throw new AccountRequiredException("nxm URL expired");
        } else if (br.getHttpConnection().getResponseCode() == 401) {
            /* {"message":"Please provide a valid API Key"} */
            throw new AccountInvalidException("Invalid or expired API Key");
        } else if (br.getHttpConnection().getResponseCode() == 403) {
            /*
             * According to API documentation, this may happen if we try to download a file via API with a free account (downloads are only
             * possible via website!)
             */
            /*
             * {"code":403, "message":
             * "You don't have permission to get download links from the API without visting nexusmods.com - this is for premium users only."
             * }
             */
            throw new AccountRequiredException();
        } else if (br.getHttpConnection().getResponseCode() == 404) {
            /* {"error":"File ID '12345' not found"} */
            /* {"code":404,"message":"No Game Found: xskyrimspecialedition"} */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.getHttpConnection().getResponseCode() == 429) {
            /* {"msg":"You have fired too many requests. Please wait for some time."} */
            /* TODO: Maybe check which limit ends first (daily / hourly) to display an even more precise waittime! */
            String reset_date = br.getRequest().getResponseHeader("X-RL-Hourly-Reset").toString();
            if (reset_date != null) {
                /* Try to find the exact waittime */
                reset_date = reset_date.substring(0, reset_date.lastIndexOf(":")) + "00";
                final long reset_timestamp = TimeFormatter.getMilliSeconds(reset_date, "yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH);
                final long waittime_until_reset = reset_timestamp - System.currentTimeMillis();
                if (waittime_until_reset > 0) {
                    /* Wait exact waittime + 5 extra seconds */
                    throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "API limit has been reached", waittime_until_reset + 5000l);
                }
            }
            throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "API limit has been reached", 30 * 60 * 1000l);
        }
    }

    private static String correctPassword(final String pw) {
        return pw.trim();
    }

    public static String getApikey(final Account account) {
        if (account == null) {
            return null;
        } else if (isAPIOnlyMode()) {
            return account.getPass();
        } else {
            return account.getStringProperty("apikey");
        }
    }

    private void saveApikey(final Account account, final String apikey) {
        if (account == null) {
            return;
        } else {
            account.setProperty("apikey", apikey);
        }
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        /*
         * TODO: Consider saving- and re-using direct downloadurls. Consider that premium users do not have any traffic limits so re-using
         * generated downloadurls does not bring any huge benefits. Also when re-using generated downloadlinks consider that they do have
         * different download mirrors/location and these are currently randomly selected on downloadstart!
         */
        if (getApikey(account) != null && linkIsAPICompatible(link)) {
            prepBrAPI(br, account);
            /* We do not have to perform an extra onlinecheck - if the file is offline, the download request will return 404. */
            // requestFileInformationAPI(link);
            final String game_domain_name = link.getStringProperty(PROPERTY_game_domain_name);
            final String mod_id = link.getStringProperty(PROPERTY_mod_id);
            final String file_id = this.getFileID(link);
            if (file_id == null || mod_id == null || game_domain_name == null) {
                /* This should never happen */
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            String action = API_BASE + String.format("/games/%s/mods/%s/files/%s/download_link.json", game_domain_name, mod_id, file_id);
            if (account.getType() == AccountType.FREE) {
                if (!this.isSpecialNexusModmanagerDownloadURL(link)) {
                    // logger.info("Only premium account download is possible as information for free account download is missing");
                    // throw new AccountRequiredException();
                    throw new PluginException(LinkStatus.ERROR_FATAL, "Free Account users can only download nxm:// URLs!");
                }
                final UrlQuery query = UrlQuery.parse(link.getPluginPatternMatcher());
                final String dlExpires = query.get("expires");
                if (Long.parseLong(dlExpires) * 1000 < System.currentTimeMillis()) {
                    /* Do not use LinkStatus FILE_NOT_FOUND here because we can be pretty sure that this file is online! */
                    throw new PluginException(LinkStatus.ERROR_FATAL, "This downloadurl has expired");
                }
                action += "?key=" + query.get("key") + "&expires=" + dlExpires;
            }
            getPage(action);
            handleErrorsAPI(br);
            final List<Object> ressourcelist = (List<Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
            if (ressourcelist == null || ressourcelist.isEmpty()) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unable to find downloadlink");
            }
            /*
             * First element = User preferred mirror. Users can set their preferred mirror here: https://www.nexusmods.com/users/myaccount
             * --> Premium membership preferences
             */
            Map<String, Object> entries = (Map<String, Object>) ressourcelist.get(0);
            final String mirrorName = (String) entries.get("name");
            this.dllink = (String) entries.get("URI");
            if (StringUtils.isEmpty(this.dllink)) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unable to find downloadlink for mirror: " + mirrorName);
            }
            logger.info("Selected random mirror: " + mirrorName);
        } else {
            /* Website handling. Important! Login before requestFileInformation! */
            loginWebsite(account);
            requestFileInformation(link);
        }
        /* Free- and premium download handling is the same. */
        handleDownload(link, account);
    }

    private static boolean isAPIKey(final String str) {
        if (str == null) {
            return false;
        } else if (str.matches("[A-Za-z0-9\\-=/\\+_]{64,}")) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public AccountBuilderInterface getAccountFactory(final InputChangedCallbackInterface callback) {
        if (isAPIOnlyMode()) {
            /* API login */
            return new NexusmodsAccountFactory(callback);
        } else {
            /* Website login */
            return super.getAccountFactory(callback);
        }
    }

    public static class NexusmodsAccountFactory extends MigPanel implements AccountBuilderInterface {
        /**
         *
         */
        private static final long serialVersionUID = 1L;
        private final String      PINHELP          = "Enter your API Key";
        private final JLabel      apikeyLabel;

        private String getPassword() {
            if (this.pass == null) {
                return null;
            } else {
                return NexusmodsCom.correctPassword(new String(this.pass.getPassword()));
            }
        }

        public boolean updateAccount(Account input, Account output) {
            if (!StringUtils.equals(input.getUser(), output.getUser())) {
                output.setUser(input.getUser());
                return true;
            } else if (!StringUtils.equals(input.getPass(), output.getPass())) {
                output.setPass(input.getPass());
                return true;
            } else {
                return false;
            }
        }

        private final ExtPasswordField pass;

        public NexusmodsAccountFactory(final InputChangedCallbackInterface callback) {
            super("ins 0, wrap 2", "[][grow,fill]", "");
            add(new JLabel("Click here to find your API Key:"));
            add(new JLink("https://www.nexusmods.com/users/myaccount?tab=api%20access"));
            add(apikeyLabel = new JLabel("<html><u><b>Personal</b></u> API Key [premium accounts only]:</html>"));
            add(this.pass = new ExtPasswordField() {
                @Override
                public void onChanged() {
                    callback.onChangedInput(this);
                }
            }, "");
            pass.setHelpText(PINHELP);
        }

        @Override
        public JComponent getComponent() {
            return this;
        }

        @Override
        public void setAccount(Account defaultAccount) {
            if (defaultAccount != null) {
                // name.setText(defaultAccount.getUser());
                pass.setText(defaultAccount.getPass());
            }
        }

        @Override
        public boolean validateInputs() {
            final String pw = getPassword();
            if (NexusmodsCom.isAPIKey(pw)) {
                apikeyLabel.setForeground(Color.BLACK);
                return true;
            } else {
                apikeyLabel.setForeground(Color.RED);
                return false;
            }
        }

        @Override
        public Account getAccount() {
            return new Account(null, getPassword());
        }
    }

    private static boolean isAPIOnlyMode() {
        // final NexusmodsConfigInterface cfg = PluginJsonConfig.get(NexusmodsCom.NexusmodsConfigInterface.class);
        // return !cfg.isEnableWebsiteMode();
        /* 2020-01-15: Website login is broken, downloads are only possible via free account */
        return true;
    }

    public void getPage(Browser ibr, String page) throws Exception {
        super.getPage(ibr, page);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return ACCOUNT_PREMIUM_MAXDOWNLOADS;
    }

    @Override
    public boolean canHandle(final DownloadLink link, final Account account) throws Exception {
        if (account == null) {
            /* Downloads without account are not possible */
            return false;
        } else if (account.getType() != AccountType.PREMIUM && !isSpecialNexusModmanagerDownloadURL(link)) {
            /* Free account users can only download special URLs which contain authorization information. */
            return false;
        } else if (!linkIsAPICompatible(link)) {
            /* E.g. older URLs or in case important properties got lost somehow, a download (via API) is not possible at all! */
            return false;
        }
        return true;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}