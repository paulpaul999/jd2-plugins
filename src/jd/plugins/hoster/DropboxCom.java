package jd.plugins.hoster;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Map;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.JComponent;
import javax.swing.JLabel;

import org.appwork.storage.TypeRef;
import org.appwork.swing.MigPanel;
import org.appwork.swing.components.ExtPasswordField;
import org.appwork.utils.DebugMode;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.HexFormatter;
import org.appwork.utils.net.URLHelper;
import org.appwork.utils.net.httpconnection.HTTPConnection.RequestMethod;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.gui.InputChangedCallbackInterface;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.plugins.accounts.AccountBuilderInterface;
import org.jdownloader.plugins.components.config.DropBoxConfig;
import org.jdownloader.plugins.components.hls.HlsContainer;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.controller.LazyPlugin;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.linkcrawler.LinkCrawlerDeepInspector;
import jd.gui.swing.components.linkbutton.JLink;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.Form.MethodType;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountRequiredException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.decrypter.DropBoxComCrawler;

@HostPlugin(revision = "$Revision: 48417 $", interfaceVersion = 3, names = { "dropbox.com" }, urls = { "" })
public class DropboxCom extends PluginForHost {
    public DropboxCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://www.dropbox.com/pricing");
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.COOKIE_LOGIN_ONLY };
    }

    public void correctDownloadLink(DownloadLink link) {
        link.setPluginPatternMatcher(link.getPluginPatternMatcher().replace("dropboxdecrypted.com/", "dropbox.com/").replaceAll("#", "%23"));
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return DropBoxConfig.class;
    }

    public static Browser prepBrWebsite(final Browser br) {
        br.setCookie("dropbox.com", "locale", "en");
        br.setAllowedResponseCodes(new int[] { 429, 460, 509 });
        return br;
    }

    public static Browser prepBrAPI(final Browser br) {
        br.getHeaders().put("User-Agent", "JDownloader");
        br.setAllowedResponseCodes(new int[] { 400, 409 });
        br.setFollowRedirects(true);
        /* Clear eventually existing old Authorization values */
        br.getHeaders().remove("Authorization");
        return br;
    }

    public static boolean useAPI() {
        // return DebugMode.TRUE_IN_IDE_ELSE_FALSE && (PluginJsonConfig.get(DropBoxConfig.class).isUseAPI() || HARDCODED_ENFORCE_API);
        /* 2020-09-30: Disabled so we can debug website related login stuff more easily */
        return false;
    }

    @Override
    public AccountBuilderInterface getAccountFactory(final InputChangedCallbackInterface callback) {
        if (useAPI()) {
            return new DropboxAccountFactory(callback);
        } else {
            return super.getAccountFactory(callback);
        }
    }

    public static final String  TYPE_S                                = "https?://[^/]+/(s/.+)";
    public static final String  TYPE_SH                               = "https?://[^/]+/sh/[^/]+/[^/]+/[^/]+";
    /* 2019-09-26: API does currently not support this kind of URL. A feature request to support those has been forwarded! */
    public static final String  TYPE_SC_GALLERY                       = "https?://[^/]+/sc/.+";
    public static final String  API_BASE                              = "https://api.dropboxapi.com/2";
    private static final String API_BASE_CONTENT                      = "https://content.dropboxapi.com/2";
    /** 2019-09-25: Website login is broken - enforce API usage for all users! */
    private final boolean       HARDCODED_ENFORCE_API                 = false;
    public static final String  PROPERTY_MAINPAGE                     = "mainlink";
    public static final String  PROPERTY_INTERNAL_PATH                = "serverside_path_to_file_relative";
    public static final String  PROPERTY_PASSWORD_COOKIE              = "password_cookie";
    @Deprecated
    public static final String  PROPERTY_IS_SINGLE_FILE               = "is_single_file";
    public static final String  PROPERTY_ACCOUNT_ACCESS_TOKEN         = "access_token";
    public static final String  PROPERTY_ACCOUNT_LAST_AUTH_VALIDATION = "last_auth_validation";
    public static final String  PROPERTY_DIRECTLINK                   = "directlink";
    public static final String  PROPERTY_PREVIEW_DOWNLOADLINK         = "preview_downloadlink";
    public static final String  PROPERTY_ORIGINAL_FILENAME            = "original_filename";
    public static final String  PROPERTY_IS_OFFICIALLY_DOWNLOADABLE   = "is_officially_downloadable";

    @Override
    public String getLinkID(final DownloadLink link) {
        try {
            final String pathWithoutParams = new URL(Encoding.htmlDecode(link.getPluginPatternMatcher())).getPath();
            return "dropbox://" + pathWithoutParams;
        } catch (final MalformedURLException e) {
            return super.getLinkID(link);
        }
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, false);
    }

    private AvailableStatus requestFileInformation(final DownloadLink link, final boolean isDownload) throws Exception {
        prepBrWebsite(this.br);
        br.setFollowRedirects(true);
        /*
         * Setting this cookie may save some http requests as the website will not ask us to enter the password again if it has been entered
         * successfully before!
         */
        final String password_cookie_value = link.getStringProperty(PROPERTY_PASSWORD_COOKIE);
        if (password_cookie_value != null) {
            DropBoxComCrawler.setPasswordCookie(br, password_cookie_value);
        }
        /**
         * 2019-09-24: Consider updating to the new/current website method: https://www.dropbox.com/sharing/fetch_user_content_link. See
         * also handling for 'TYPE_SC' linktype! </br>
         * This might not be necessary for any other linktype as the old '?dl=1' method is working just fine!
         */
        if (link.getPluginPatternMatcher().matches(TYPE_SC_GALLERY)) {
            final String url = link.getPluginPatternMatcher().replaceFirst("(?i)/dropbox.com/", "/www.dropbox.com/");
            br.getPage(url);
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            /* 2019-09-25: Do nothing, trust filename & size which was set in crawler. At this stage we know that the content is online! */
        } else {
            final String dllink = generateDirecturl(link);
            URLConnectionAdapter con = null;
            try {
                /**
                 * 2023-05-20: Disabled head request because when using it, that will quite often returns a content-length header with value
                 * 0 plus it sometimes fails with http response != 200 for unknown reasons. For those reasons using a GET request is the way
                 * to go. </br>
                 */
                final boolean useHeadRequestFirst = false;
                if (useHeadRequestFirst) {
                    con = br.openHeadConnection(dllink);
                } else {
                    con = br.openGetConnection(dllink);
                }
                if (useHeadRequestFirst && (con.getResponseCode() == 403 || con.getResponseCode() == 404)) {
                    /* Workaround/fallback */
                    logger.info("Looks like HEAD-request is not possible -> Trying GET-request");
                    try {
                        br.followConnection(true);
                    } catch (IOException e) {
                        logger.log(e);
                    }
                    con = br.openGetConnection(dllink);
                }
                if (this.looksLikeDownloadableContent(con)) {
                    /* Success! Element is direct-downloadable. This is what we want. */
                    link.setProperty(PROPERTY_DIRECTLINK, dllink);
                    link.setProperty(PROPERTY_IS_OFFICIALLY_DOWNLOADABLE, true);
                    if (con.getCompleteContentLength() > 0) {
                        link.setVerifiedFileSize(con.getCompleteContentLength());
                    }
                    String filenameFromHeader = getFileNameFromHeader(con);
                    if (!StringUtils.isEmpty(filenameFromHeader)) {
                        if (Encoding.isHtmlEntityCoded(filenameFromHeader)) {
                            filenameFromHeader = Encoding.htmlDecode(filenameFromHeader).trim();
                        }
                        link.setFinalFileName(filenameFromHeader);
                    }
                    return AvailableStatus.TRUE;
                }
                br.followConnection(true);
                if (con.getResponseCode() == 400) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else if (con.getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else if (con.getResponseCode() == 460) {
                    /* Restricted Content: This file is no longer available. For additional information contact Dropbox Support. */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else if (con.getResponseCode() == 509) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Error 509", 60 * 60 * 1000l);
                }
                if (con.getResponseCode() == 403) {
                    /*
                     * Check if the content is offline or just is not downloadable (e.g. owner has disabled download button - can only be
                     * downloaded by himself or other users with appropriate rights.)
                     */
                    logger.info("Error 403 -> Looking to get file infor via root folder URL");
                    br.getPage(this.getRootFolderURL(link, link.getPluginPatternMatcher()));
                    if (br.getHttpConnection().getResponseCode() == 403) {
                        /* Still error 403 -> File is offline. */
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    } else {
                        /**
                         * 2020-08-04: Rare case: Content is available not not (officially) downloadable. </br>
                         * For images, in theory a thumbnail might sometimes be downloadable. Video and audio content can sometimes be
                         * streamed.
                         */
                        logger.info("Looks like this file is officially not downloadable");
                        /* Try to gather more information about this file */
                        final Map<String, Object> fileinfo = getSingleFileJsonMap(br);
                        if (fileinfo != null) {
                            DropBoxComCrawler.parseMiscFileInfo(link, fileinfo);
                        } else {
                            logger.warning("Failed to find additional information about officially un-downloadable file");
                        }
                        link.setProperty(PROPERTY_IS_OFFICIALLY_DOWNLOADABLE, false);
                        if (link.hasProperty(PROPERTY_PREVIEW_DOWNLOADLINK)) {
                            return AvailableStatus.TRUE;
                        } else {
                            /**
                             * File owner has disabled downloads and there is no streaming link available as fallback. </br>
                             * --> File is online but cannot be downloaded.
                             */
                            if (isDownload) {
                                throw new PluginException(LinkStatus.ERROR_FATAL, "File owner has disabled downloads");
                            } else {
                                return AvailableStatus.TRUE;
                            }
                        }
                    }
                }
                /* Rare case */
                logger.info("File is not direct-downloadable");
                if (br.getURL().contains("/speedbump/")) {
                    /* 2019-09-26: TODO: Check this - this should only happen for executable files in some cases */
                    // brc.getURL().replace("/speedbump/", "/speedbump/dl/");
                }
                if (isPasswordProtectedWebsite(br)) {
                    /**
                     * We know that the file is online but it is password protected. </br>
                     * Password handling is located in download handling as we do not want to ask the user for a download password during
                     * linkcheck. </br>
                     * Also, even if we already know the correct password, we do not want to send it during linkcheck as this would slow
                     * down linkcheck tremendously.
                     */
                    logger.info("Link is password protected");
                    link.setPasswordProtected(true);
                    return AvailableStatus.TRUE;
                } else if (password_cookie_value == null) {
                    /*
                     * If password_cookie_value is given, file most likely is password protected but website doesn't ask for password as
                     * access is currently already granted via cookie session.
                     */
                    link.setPasswordProtected(false);
                }
                // TODO: Check if this fallback is still needed
                if (useHeadRequestFirst && RequestMethod.HEAD.equals(con.getRequestMethod())) {
                    logger.info("Accessing URL after HEAD-request");
                    br.getPage(dllink);
                }
            } finally {
                try {
                    con.disconnect();
                } catch (Throwable e) {
                }
            }
            if (br.getHttpConnection().getResponseCode() == 429) {
                /* 2017-01-30 */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Error 429: 'This account's links are generating too much traffic and have been temporarily disabled!'", 60 * 60 * 1000l);
            } else if (br.containsHTML("images/sharing/error_")) {
                /* Offline */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (br.containsHTML("/images/precaution")) {
                /* A previously public shared url is now private (== offline) */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
        }
        return AvailableStatus.TRUE;
    }

    public Map<String, Object> getSingleFileJsonMap(final Browser brc) {
        final String fileJson = brc.getRegex("InitReact\\.mountComponent\\(mod, (\\{.*?\\})\\);").getMatch(0);
        if (fileJson != null) {
            final Map<String, Object> entries = restoreFromString(fileJson, TypeRef.MAP);
            final Map<String, Object> fileinfo = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "props/file");
            return fileinfo;
        } else {
            return null;
        }
    }

    /**
     * Returns URL with "dl=1" parameter. This should work for all officially downloadable items. </br>
     * If an item is password protected, the password needs to be entered correctly otherwise this URL obviously can't be used for
     * downloading.
     */
    private String generateDirecturl(final DownloadLink link) throws MalformedURLException {
        final UrlQuery query = UrlQuery.parse(link.getPluginPatternMatcher());
        query.addAndReplace("dl", "1");
        String dllink = URLHelper.getUrlWithoutParams(link.getPluginPatternMatcher());
        /* Add potentially missing 'www.' to avoid additional redirect. */
        dllink = dllink.replaceFirst("(?i)/dropbox\\.com/", "/www.dropbox.com/");
        dllink += "?" + query.toString();
        return dllink;
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        if (useAPI()) {
            return fetchAccountInfoAPI(account);
        } else {
            return fetchAccountInfoWebsite(account);
        }
    }

    public AccountInfo fetchAccountInfoWebsite(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        if (!account.getUser().matches(".+@.+\\..+")) {
            if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nBitte gib deine E-Mail Adresse ins Benutzername Feld ein!", PluginException.VALUE_ID_PREMIUM_DISABLE);
            } else {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlease enter your e-mail address in the username field!", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
        }
        br.setDebug(true);
        loginWebsite(account, true);
        /* 2019-09-19: Treat all accounts as FREE accounts */
        account.setType(AccountType.FREE);
        ai.setUnlimitedTraffic();
        return ai;
    }

    public AccountInfo fetchAccountInfoAPI(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        this.loginAPI(this.br, account, true);
        this.accessAPIAccountInfo(this.br);
        final boolean account_disabled = "true".equals(PluginJSonUtils.getJson(br, "disabled"));
        if (account_disabled) {
            /* 2019-09-19: No idea what this means - probably banned accounts?! */
            throw new PluginException(LinkStatus.ERROR_PREMIUM, "Your account has been disabled/banned by Dropbox", PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
        }
        /* Make sure we do not store the users' real logindata as he will likely enter them into our login-mask! */
        account.setUser(null);
        account.setPass(null);
        try {
            final Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(br.getRequest().getHtmlCode());
            final String given_name = (String) JavaScriptEngineFactory.walkJson(entries, "name/given_name");
            final String surname = (String) JavaScriptEngineFactory.walkJson(entries, "name/surname");
            if (!StringUtils.isEmpty(given_name)) {
                String jd_account_manager_username = given_name;
                if (!StringUtils.isEmpty(surname)) {
                    jd_account_manager_username += " " + surname.substring(0, 1) + ".";
                }
                /* Save this as username - no one can start attacks on stored logindata based on this! */
                account.setUser(jd_account_manager_username);
            }
            final String account_type = (String) JavaScriptEngineFactory.walkJson(entries, "account_type/.tag");
            if (!StringUtils.isEmpty(account_type)) {
                ai.setStatus("Account type: " + account_type);
            } else {
                /* Fallback */
                ai.setStatus("Registered (free) user");
            }
            /* 2019-09-19: Treat all accounts as FREE accounts - the Account-Type does not change the download procedure! */
            account.setType(AccountType.FREE);
        } catch (final Throwable e) {
            /* 2019-09-19: On failure: Treat all accounts as FREE accounts */
            logger.log(e);
            logger.warning("Exception in json handling");
            account.setType(AccountType.FREE);
            ai.setStatus("Registered (free) user");
        }
        ai.setUnlimitedTraffic();
        return ai;
    }

    protected String generateNonce() {
        return Long.toString(new Random().nextLong());
    }

    protected String generateTimestamp() {
        return Long.toString(System.currentTimeMillis() / 1000L);
    }

    @Override
    public String getAGBLink() {
        return "https://www.dropbox.com/terms";
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        String passCode = link.getDownloadPassword();
        final String t1 = new Regex(link.getPluginPatternMatcher(), "://(.*?):.*?@").getMatch(0);
        final String t2 = new Regex(link.getPluginPatternMatcher(), "://.*?:(.*?)@").getMatch(0);
        if (t1 != null && t2 != null) {
            handlePremium(link, null);
            return;
        }
        requestFileInformation(link, true);
        /*
         * Important! Files may be password protected but if PROPERTY_PASSWORD_COOKIE has been set before we will not have to send the
         * password again! This is why it is crucial to also check for isPasswordProtectedWebsite here!!
         */
        final boolean resume_supported;
        String dllink = null;
        final Object isOfficiallyDownloadable = link.getProperty(PROPERTY_IS_OFFICIALLY_DOWNLOADABLE);
        if (Boolean.FALSE.equals(isOfficiallyDownloadable)) {
            /* Workaround for files without official downloadbutton e.g. some video streams --> Downloads stream/"preview file" */
            logger.info("Attempting stream download");
            dllink = link.getStringProperty(PROPERTY_PREVIEW_DOWNLOADLINK);
            resume_supported = false;
        } else {
            logger.info("Attempting official download");
            if (link.getPluginPatternMatcher().matches(TYPE_SC_GALLERY)) {
                /* Complete image gallery */
                if (StringUtils.endsWithCaseInsensitive(link.getFinalFileName(), ".zip")) {
                    /* .zip containing all files of that gallery - this may only happen if the single-object crawler fails */
                    resume_supported = false;
                } else {
                    /* Single object of a gallery (most likely picture or video) */
                    resume_supported = true;
                }
                final String cookie_t = br.getCookie(this.getHost(), "t", Cookies.NOTDELETEDPATTERN);
                if (cookie_t == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final Form dlform = new Form();
                dlform.setMethod(MethodType.POST);
                /** 2019-09-26: TODO: Maybe use this for all types of URLs */
                dlform.setAction("https://www." + this.getHost() + "/sharing/fetch_user_content_link");
                dlform.put("is_xhr", "true");
                dlform.put("t", cookie_t);
                dlform.put("url", Encoding.urlEncode(link.getPluginPatternMatcher()));
                // dlform.put("url",
                // Encoding.urlEncode("https://www.dropbox.com/scl/fo/5oi20b8fmpv793ll572i2/h/2020-how_it_works%20%281080p%29.mp4?dl=0&rlkey=agqiezx8kvcjp3frtwoqq65bv"));
                // dlform.put("origin", "PREVIEW_PAGE");
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.submitForm(dlform);
                dllink = br.toString();
                if (!dllink.startsWith("http")) {
                    logger.warning("Failed to find final downloadurl");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            } else if (link.isPasswordProtected() && isPasswordProtectedWebsite(br)) {
                /* Single file */
                resume_supported = true;
                final String content_id = new Regex(br.getURL(), "content_id=([^\\&]+)").getMatch(0);
                if (content_id == null) {
                    logger.warning("Failed to find content_id");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                Form pwform = this.br.getFormbyProperty("id", "password-form");
                if (pwform == null) {
                    /* 2019-05-22: New */
                    pwform = this.br.getFormbyAction("/ajax_verify_code");
                }
                if (pwform == null) {
                    pwform = new Form();
                    pwform.setMethod(MethodType.POST);
                }
                pwform.setAction("https://www.dropbox.com/sm/auth");
                if (passCode == null) {
                    passCode = getUserInput("Password?", link);
                }
                final String cookie_t = br.getCookie(getHost(), "t");
                if (cookie_t != null) {
                    pwform.put("t", cookie_t);
                }
                pwform.put("password", passCode);
                pwform.put("is_xhr", "true");
                pwform.put("content_id", content_id);
                final Browser brc = br.cloneBrowser();
                brc.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                brc.submitForm(pwform);
                /* 2019-09-24: E.g. positive response: {"status": "authed"} */
                final String status = PluginJSonUtils.getJson(brc, "status");
                if ("error".equalsIgnoreCase(status)) {
                    link.setDownloadPassword(null);
                    throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
                }
                final String password_cookie = getPasswordCookie(br);
                if (!StringUtils.isEmpty(password_cookie)) {
                    /* Same password-cookie. This may save us some http requests next time! */
                    link.setProperty(PROPERTY_PASSWORD_COOKIE, password_cookie);
                } else {
                    /* This should never happen */
                    logger.warning("User has entered correct password but password cookie is not available");
                }
                link.setDownloadPassword(passCode);
                dllink = this.generateDirecturl(link);
            } else {
                /* Link is not password protected or password-cookie was available so there was no password prompt for us to handle now. */
                resume_supported = true;
                dllink = this.generateDirecturl(link);
            }
        }
        handleDownload(link, null, dllink, resume_supported);
    }

    public static String getPasswordCookie(final Browser br) {
        return br.getCookie(br.getHost(), "sm_auth");
    }

    /** Downloads given directurl. */
    private void handleDownload(final DownloadLink link, final Account account, String dllink, final boolean resume) throws Exception {
        if (StringUtils.isEmpty(dllink)) {
            logger.warning("Failed to find final downloadurl");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final boolean allowFixFileExtension;
        final String directurlPreview = link.getStringProperty(PROPERTY_PREVIEW_DOWNLOADLINK);
        if (StringUtils.equals(dllink, directurlPreview)) {
            /* Stream download active -> Filesize can be different from size of original file. */
            link.setVerifiedFileSize(-1);
            allowFixFileExtension = true;
        } else {
            allowFixFileExtension = false;
        }
        /* Important: URL needs to contain "www."! */
        dllink = dllink.replaceFirst("(?i)/dropbox.com/", "/www.dropbox.com/");
        dl = new jd.plugins.BrowserAdapter().openDownload(br, link, dllink, resume, 1);
        dl.setFilenameFix(true);
        final String filename = link.getName();
        if (LinkCrawlerDeepInspector.looksLikeMpegURL(dl.getConnection())) {
            /* HLS download (usually only needed as fallback if official download is disabled) */
            dl = null;
            checkFFmpeg(link, "Download a HLS Stream");
            br.followConnection();
            final HlsContainer hlsbest = HlsContainer.findBestVideoByBandwidth(HlsContainer.getHlsQualities(br));
            if (hlsbest == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl = new HLSDownloader(link, br, hlsbest.getDownloadurl());
            final String extension = hlsbest.getFileExtension();
            /* Correct file extension */
            if (filename != null) {
                link.setFinalFileName(this.correctOrApplyFileNameExtension(filename, extension));
            }
            dl.startDownload();
        } else {
            /* http download */
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                logger.warning("Final downloadlink lead to HTML code");
                br.followConnection(true);
                checkErrorsHTML(br);
                final URLConnectionAdapter con = dl.getConnection();
                if (con.getResponseCode() == 401) {
                    if (account != null) {
                        throw new AccountInvalidException();
                    } else {
                        throw new AccountRequiredException();
                    }
                } else if (con.getResponseCode() == 403) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403");
                } else if (con.getResponseCode() == 500) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 500", 1 * 60 * 1000);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            final String ext = Plugin.getExtensionFromMimeTypeStatic(dl.getConnection().getContentType());
            if (allowFixFileExtension && filename != null && ext != null) {
                link.setFinalFileName(this.correctOrApplyFileNameExtension(filename, ext));
            }
            dl.startDownload();
        }
    }

    private void checkErrorsHTML(final Browser br) throws PluginException {
        if (br.containsHTML("(?i)Link Temporarily Disabled")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Download is temporary disabled because it has been downloaded too frequently", 10 * 60 * 1000l);
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        String dlURL = link.getPluginPatternMatcher();
        boolean resume = true;
        if (dlURL.matches(".*api-content.dropbox.com.*")) {
            /** 2019-09-24: TODO: Check when this happens, find testlinks for this case! */
            /* api downloads via tokens */
            resume = false;
            try {
                /* Decrypt oauth token and secret */
                byte[] crypted_oauth_consumer_key = org.appwork.utils.encoding.Base64.decode("1lbl8Ts5lNJPxMOBzazwlg==");
                byte[] crypted_oauth_consumer_secret = org.appwork.utils.encoding.Base64.decode("cqqyvFx1IVKNPennzVKUnw==");
                byte[] iv = new byte[] { (byte) 0xF0, 0x0B, (byte) 0xAA, (byte) 0x69, 0x42, (byte) 0xF0, 0x0B, (byte) 0xAA };
                byte[] secretKey = (new Regex(dlURL, "passphrase=([^&]+)").getMatch(0).substring(0, 8)).getBytes("UTF-8");
                SecretKey key = new SecretKeySpec(secretKey, "DES");
                AlgorithmParameterSpec paramSpec = new IvParameterSpec(iv);
                Cipher dcipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
                dcipher.init(Cipher.DECRYPT_MODE, key, paramSpec);
                String oauth_consumer_key = new String(dcipher.doFinal(crypted_oauth_consumer_key), "UTF-8");
                String oauth_token_secret = new String(dcipher.doFinal(crypted_oauth_consumer_secret), "UTF-8");
                /* remove existing tokens from url */
                dlURL = dlURL.replaceFirst("://[\\w:]+@", "://");
                /* remove passphrase from url */
                dlURL = dlURL.replaceFirst("(?i)[\\?&]passphrase=[^&]+", "");
                String t1 = new Regex(link.getPluginPatternMatcher(), "://(.*?):.*?@").getMatch(0);
                String t2 = new Regex(link.getPluginPatternMatcher(), "://.*?:(.*?)@").getMatch(0);
                if (t1 == null) {
                    t1 = account.getUser();
                }
                if (t2 == null) {
                    t2 = account.getPass();
                }
                dlURL = signOAuthURL(dlURL, oauth_consumer_key, oauth_token_secret, t1, t2);
            } catch (PluginException e) {
                throw e;
            } catch (Exception e) {
                logger.log(e);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, null, e);
            }
        } else if (!useAPI()) {
            /* Website downloads */
            loginWebsite(account, false);
            dlURL = this.generateDirecturl(link);
        } else {
            /* API download - uses different handling + errorhandling! */
            handleDownloadAccountAPI(account, link);
            return;
        }
        handleDownload(link, account, dlURL, resume);
    }

    /** API download (account required) */
    private void handleDownloadAccountAPI(final Account account, final DownloadLink link) throws Exception {
        /*
         * 2019-09-25: Do not check login at all. If it is invalid, errorhandling will detect that after download attempt and display
         * account as invalid!
         */
        prepBrAPI(this.br);
        setAPILoginHeaders(this.br, account);
        // this.loginAPI(this.br, account, false);
        final String contentURL = getRootFolderURL(link, link.getPluginPatternMatcher());
        String serverside_path_to_file_relative = link.getStringProperty(PROPERTY_INTERNAL_PATH, null);
        if (serverside_path_to_file_relative != null && !this.isSingleFile(link)) {
            /* Fix json */
            serverside_path_to_file_relative = "\"" + serverside_path_to_file_relative + "\"";
        } else {
            /* We expect this to be a single file --> 'path' value must be 'null'! */
            serverside_path_to_file_relative = "null";
        }
        String download_password = link.getDownloadPassword();
        if (download_password == null) {
            /* Do not send 'null' value to API! */
            download_password = "";
        }
        /**
         * https://www.dropbox.com/developers/documentation/http/documentation#sharing-get_shared_link_file
         */
        final String jsonHeader = "{ \"url\": \"" + contentURL + "\", \"path\":" + serverside_path_to_file_relative + ", \"link_password\":\"" + download_password + "\"  }";
        br.getHeaders().put("Dropbox-API-Arg", jsonHeader);
        br.getHeaders().put("Content-Type", "text/plain;charset=UTF-8");
        dl = new jd.plugins.BrowserAdapter().openDownload(br, link, API_BASE_CONTENT + "/sharing/get_shared_link_file", "", true, 1);
        dl.setFilenameFix(true);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection();
            final String error_summary = getErrorSummaryField(this.br);
            if (error_summary.contains("shared_link_access_denied")) {
                /*
                 * Request password and check it on the next retry. This is a rare case because at least if the user adds URLs via crawler +
                 * API, he will already have entered the correct password by now!
                 */
                logger.info("URL is either password protected or user is lacking the rights to view it");
                link.setPasswordProtected(true);
                final boolean enable_password_protected_workaround = true;
                if (enable_password_protected_workaround) {
                    /**
                     * 2019-09-25: official Dropbox staff confirmed this API bug: Downloads of password protected content via APIv2 is not
                     * possible at the moment.
                     */
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Rights missing or password protected", 1 * 60 * 60 * 1000l);
                } else {
                    download_password = getUserInput("Password?", link);
                    link.setDownloadPassword(download_password);
                    throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password");
                }
            } else {
                link.setPasswordProtected(false);
            }
            handleAPIErrors();
            final URLConnectionAdapter con = dl.getConnection();
            handleAPIResponseCodes(con.getResponseCode());
            br.followConnection();
            logger.warning("Final downloadlink lead to HTML code");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    /** Handles download-API-errors */
    private void handleAPIErrors() throws PluginException {
        final String error_summary = getErrorSummaryField(this.br);
        if (!StringUtils.isEmpty(error_summary)) {
            if (error_summary.contains("shared_link_not_found")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                /* For all other errors, just wait and retry */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "API error: " + error_summary);
            }
        }
    }

    private void handleAPIResponseCodes(final long responsecode) throws PluginException {
        if (responsecode == 400) {
            /*
             * This should never happen but could happen when we e.g. try to download content which does not exist anymore or when we try to
             * download URLs which have been added via website and not API - sometimes we then use bad parameters to request
             * fileinfo/downloads! This will also happen if we e.g. have an invalid Authorization Header but it happened only during testing
             * with false value on purpose! With the Authorization-Header values we get via API this should never happen!
             */
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "API error 400");
        } else if (responsecode == 401) {
            /* HTTP/1.1 401 invalid_access_token/ */
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
        } else if (responsecode == 409) {
            /* 2019-09-20: E.g. "error_summary": "shared_link_not_found/" */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
    }

    private boolean itemHasBeenCrawledViaAPI(final DownloadLink link) {
        return getRootFolderURL(link, null) != null;
    }

    /** Returns either the URL to the root folder of our current file or the link that goes to that particular file. */
    private String getRootFolderURL(final DownloadLink link, final String fallback) {
        final String contentURL = link.getStringProperty(PROPERTY_MAINPAGE);
        if (contentURL != null) {
            return contentURL;
        } else {
            /* Fallback for old URLs or such added via website-crawler. */
            return fallback;
        }
    }

    /**
     * Only use this in crawler!! In host-plugins, use isSingleFile(final DownloadLink link)!! </br>
     * Deprecated since: 2023-05-03: It is not easy / impossible to differentiate between files and folders only by URL-structure!
     */
    @Deprecated
    public static boolean looksLikeSingleFile(final String url) {
        if (url.matches(TYPE_S) || url.matches(TYPE_SH)) {
            return true;
        } else {
            return false;
        }
    }

    private boolean isSingleFile(final DownloadLink link) {
        if (looksLikeSingleFile(this.getRootFolderURL(link, link.getPluginPatternMatcher())) || link.getBooleanProperty(PROPERTY_IS_SINGLE_FILE, false)) {
            return true;
        } else {
            return false;
        }
    }

    // /**
    // * Posts json data first without checking login and if that fails, again with ensuring login!
    // */
    // private void postPageRawAndEnsureLogin(final Account account, final String url, final String data) throws Exception {
    // boolean verifiedCookies = this.loginAPI(br, account, false);
    // this.br.postPageRaw(url, data);
    // /** TODO: Add isLoggedIN function and check */
    // if (!verifiedCookies && !this.isLoggedinAPI(this.br)) {
    // logger.info("Retrying with ensured login");
    // verifiedCookies = this.loginAPI(this.br, account, false);
    // this.br.postPageRaw(url, data);
    // }
    // }
    public static String getErrorSummaryField(final Browser br) {
        return PluginJSonUtils.getJson(br, "error_summary");
    }

    /** Returns whether or not a file/folder is password protected according to current browser html code. */
    public static boolean isPasswordProtectedWebsite(final Browser br) {
        final String currentURL = br.getURL();
        final String redirectURL = br.getRedirectLocation();
        final String pwProtectedIndicator = "/sm/password";
        boolean passwordProtected = false;
        if (currentURL != null && currentURL.contains(pwProtectedIndicator) || redirectURL != null && redirectURL.contains(pwProtectedIndicator)) {
            passwordProtected = true;
        }
        return passwordProtected;
    }

    /** 2019-09-20: Avoid using this. It is outdated - does not support 2FA login and is broken for a long time already! */
    private void loginWebsite(final Account account, boolean validateCookies) throws Exception {
        synchronized (account) {
            setBrowserExclusive();
            br.setFollowRedirects(true);
            final Cookies userCookies = account.loadUserCookies();
            if (userCookies == null || userCookies.isEmpty()) {
                showCookieLoginInfo();
                throw new AccountInvalidException(_GUI.T.accountdialog_check_cookies_required());
            }
            if (userCookies != null) {
                br.setCookies(userCookies);
                if (!validateCookies) {
                    /* Do not validate cookies. */
                    return;
                }
                /* 2020-09-30: This will redirect to login page if cookies are invalid! */
                br.getPage("https://www." + this.getHost() + "/account");
                if (br.getURL().contains(br.getHost() + "/account")) {
                    logger.info("User cookie login successful");
                    return;
                } else {
                    logger.info("User cookie login failed");
                    if (account.hasEverBeenValid()) {
                        throw new AccountInvalidException(_GUI.T.accountdialog_check_cookies_expired());
                    } else {
                        throw new AccountInvalidException(_GUI.T.accountdialog_check_cookies_invalid());
                    }
                }
            }
            if (!true) {
                // Old code
                logger.info("Full login required");
                br.getPage("https://www.dropbox.com/login");
                String t = br.getRegex("type=\"hidden\" name=\"t\" value=\"([^<>\"]*?)\"").getMatch(0);
                if (t == null) {
                    t = this.br.getCookie("dropbox.com", "t");
                }
                if (t == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.getHeaders().put("Accept", "text/plain, */*; q=0.01");
                br.getHeaders().put("Accept-Language", "en-US;q=0.7,en;q=0.3");
                br.postPage("/needs_captcha", "is_xhr=true&t=" + t + "&email=" + Encoding.urlEncode(account.getUser()));
                br.postPage("/sso_state", "is_xhr=true&t=" + t + "&email=" + Encoding.urlEncode(account.getUser()));
                String postdata = "is_xhr=true&t=" + t + "&cont=%2F&require_role=&signup_data=&third_party_auth_experiment=CONTROL&signup_tag=&login_email=" + Encoding.urlEncode(account.getUser()) + "&login_password=" + Encoding.urlEncode(account.getPass()) + "&remember_me=True";
                postdata += "&login_sd=";
                postdata += "";
                br.postPage("/ajax_login", postdata);
                if (!isLoggedInViaCookies(br) || !"OK".equals(PluginJSonUtils.getJsonValue(br, "status"))) {
                    throw new AccountInvalidException();
                }
            }
            /* This code should never be reached! */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    private boolean isLoggedInViaCookies(final Browser br) {
        return br.getCookie(br.getHost(), "jar", Cookies.NOTDELETEDPATTERN) != null;
    }

    /**
     * API login: https://www.dropbox.com/developers/documentation/http/documentation#oa2-authorize and
     * https://www.dropbox.com/developers/reference/oauth-guide
     */
    public boolean loginAPI(final Browser br, final Account account, boolean validateAuthorization) throws Exception {
        synchronized (account) {
            prepBrAPI(br);
            boolean loggedIN = false;
            if (setAPILoginHeaders(br, account)) {
                final long last_auth_validation = account.getLongProperty(PROPERTY_ACCOUNT_LAST_AUTH_VALIDATION, 0);
                if (!validateAuthorization && System.currentTimeMillis() - last_auth_validation <= 300000l) {
                    return false;
                }
                accessAPIAccountInfo(br);
                loggedIN = isLoggedinAPI(br);
            }
            if (!loggedIN) {
                /* Important: This is required as it will also clear any previous "Authorization" headers! */
                prepBrAPI(br);
                logger.info("Performing full login");
                /* Perform full login */
                /* TODO: Check if we still need that dialog */
                // final String user_auth_url = "https://www." + account.getHoster() + "/oauth2/authorize?client_id=" + getAPIClientID() +
                // "&response_type=code&force_reapprove=false";
                // showOauthLoginInformation(user_auth_url);
                final String user_code = account.getPass();
                if (StringUtils.isEmpty(user_code)) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "Authorization code has not been entered", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                final Form loginform = new Form();
                loginform.setMethod(MethodType.POST);
                loginform.setAction("https://api.dropboxapi.com/oauth2/token");
                loginform.put("code", user_code);
                loginform.put("grant_type", "authorization_code");
                loginform.put("client_id", getAPIClientID());
                loginform.put("client_secret", getAPISecret());
                /*
                 * 2019-09-19: redirect_uri field is not required as we're not yet able to use it, thus we're using 'response_type=code'
                 * above.
                 */
                // loginform.put("redirect_uri", "TODO");
                br.submitForm(loginform);
                String access_token = PluginJSonUtils.getJson(br, "access_token");
                /* This is required to obtain account-information later on! */
                // final String account_id = PluginJSonUtils.getJson(br, "account_id");
                /* We do not need this 2nd user-id! */
                // final String uid = PluginJSonUtils.getJson(br, "uid");
                if (StringUtils.isEmpty(access_token)) {
                    /* 2019-09-19: We do not care about the fail-reason - failure = wrong logindata! */
                    /* E.g. expired token: {"error_description": "code has expired (within the last hour)", "error": "invalid_grant"} */
                    final String error_description = PluginJSonUtils.getJson(br, "error_description");
                    if (!StringUtils.isEmpty(error_description)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "Invalid login: " + error_description, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                /*
                 * 2019-09-19: This token should last until the user revokes access! It can even survive password changes if the user wants
                 * it to! It will even last when a user switches from normal login to 2-factor-authorization! Users can open up unlimited(?)
                 * logins via one Application. If they revoke access for that application, all of them will be gone:
                 * https://www.dropbox.com/account/security
                 */
                account.setProperty(PROPERTY_ACCOUNT_ACCESS_TOKEN, access_token);
                /* We do not need to store this value */
                // if (!StringUtils.isEmpty(account_id)) {
                // account.setProperty("account_id", account_id);
                // }
                setAPILoginHeaders(br, account);
            }
            account.setProperty(PROPERTY_ACCOUNT_LAST_AUTH_VALIDATION, System.currentTimeMillis());
            return true;
        }
    }

    private boolean isLoggedinAPI(final Browser br) {
        final String error_summary = getErrorSummaryField(br);
        return br.getHttpConnection().getResponseCode() == 200 && StringUtils.isEmpty(error_summary);
    }

    private void accessAPIAccountInfo(final Browser br) throws IOException {
        /** https://www.dropbox.com/developers/documentation/http/documentation#users-get_current_account */
        if (br.getURL() == null || !br.getURL().contains("/users/get_current_account")) {
            /* 'null' is required to send otherwise we'll get an error-response!! */
            br.postPageRaw(API_BASE + "/users/get_current_account", "null");
        }
    }

    /**
     * Sets Authorization header. Because once generated, an oauth token is valid 'forever' until user revokes access to application, it
     * must not necessarily be re-validated!
     *
     * @return true = api_token found and set </br>
     *         false = no api_token found
     */
    public static boolean setAPILoginHeaders(final Browser br, final Account account) {
        if (account == null || br == null) {
            return false;
        }
        final String access_token = getAPIToken(account);
        if (access_token == null) {
            return false;
        }
        br.getHeaders().put("Authorization", "Bearer " + access_token);
        br.getHeaders().put("Content-Type", "application/json");
        return true;
    }

    // private String getAPIAccountID(final Account account) {
    // return account.getStringProperty("account_id", null);
    // }
    public static String getAPIToken(final Account account) {
        return account.getStringProperty(PROPERTY_ACCOUNT_ACCESS_TOKEN);
    }

    /**
     * Also called App-key and can be found here: https://www.dropbox.com/developers/apps </br>
     * TODO: Change this to public static
     */
    private String getAPIClientID() throws PluginException {
        if (DebugMode.TRUE_IN_IDE_ELSE_FALSE && force_dev_values) {
            return getAPIClientIDDev();
        } else {
            try {
                final String[] dropBox = (String[]) getClass().forName(new String(HexFormatter.hexToByteArray("6F72672E6A646F776E6C6F616465722E636F6E7461696E65722E436F6E666967"), "UTF-8")).getMethod("DropBox").invoke(null);
                return dropBox[0];
            } catch (Throwable e) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, null, -1, e);
            }
        }
    }

    /** Can be found here: https://www.dropbox.com/developers/apps */
    private String getAPISecret() throws PluginException {
        if (DebugMode.TRUE_IN_IDE_ELSE_FALSE && force_dev_values) {
            return getAPISecretDev();
        } else {
            try {
                final String[] dropBox = (String[]) getClass().forName(new String(HexFormatter.hexToByteArray("6F72672E6A646F776E6C6F616465722E636F6E7461696E65722E436F6E666967"), "UTF-8")).getMethod("DropBox").invoke(null);
                return Encoding.Base64Decode(dropBox[1]);
            } catch (Throwable e) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, null, -1, e);
            }
        }
    }

    /** TODO: Remove this once API tests are completed (Stable release) */
    private static final boolean force_dev_values = true;

    private static String getAPIClientIDDev() {
        return "REMOVEME_IN_STABLE";
    }

    /** TODO: Remove this once API tests are completed (Stable release) */
    private static String getAPISecretDev() {
        return "REMOVEME_IN_STABLE";
    }

    // private Thread showOauthLoginInformation(final String auth_url) {
    // final Thread thread = new Thread() {
    // public void run() {
    // try {
    // String message = "";
    // final String title;
    // if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
    // title = "Dropbox.com - neue Login-Methode";
    // message += "Hallo liebe(r) Dropbox NutzerIn\r\n";
    // message += "Seit diesem Update hat sich die Login-Methode dieses Anbieters geändert um die Sicherheit zu erhöhen!\r\n";
    // message += "Um deinen Account weiterhin in JDownloader verwenden zu können, musst du folgende Schritte beachten:\r\n";
    // message += "1. Gehe sicher, dass du im Browser in deinem Dropbox Account eingeloggt bist.\r\n";
    // message += "2. Öffne diesen Link im Browser falls das nicht automatisch geschieht:\r\n\t'" + auth_url + "'\t\r\n";
    // message += "3. Gib den Code, der im Browser angezeigt wird hier ein.\r\n";
    // message += "Dein Account sollte nach einigen Sekunden von JDownloader akzeptiert werden.\r\n";
    // } else {
    // title = "Dropbox.com - New login method";
    // message += "Hello dear Dropbox user\r\n";
    // message += "This update has changed the login method of Dropbox in favor of security.\r\n";
    // message += "In order to keep using this service in JDownloader you need to follow these steps:\r\n";
    // message += "1. Make sure that you're logged in your Dropbox account with your default browser.\r\n";
    // message += "2. Open this URL in your browser if it does not happen automatically:\r\n\t'" + auth_url + "'\t\r\n";
    // message += "3. Enter the code you see in your browser here.\r\n";
    // message += "Your account should be accepted in JDownloader within a few seconds.\r\n";
    // }
    // final ConfirmDialog dialog = new ConfirmDialog(UIOManager.LOGIC_COUNTDOWN, title, message);
    // dialog.setTimeout(30 * 1000);
    // if (CrossSystem.isOpenBrowserSupported() && !Application.isHeadless()) {
    // CrossSystem.openURL(auth_url);
    // }
    // final ConfirmDialogInterface ret = UIOManager.I().show(ConfirmDialogInterface.class, dialog);
    // ret.throwCloseExceptions();
    // } catch (final Throwable e) {
    // getLogger().log(e);
    // }
    // };
    // };
    // thread.setDaemon(true);
    // thread.start();
    // return thread;
    // }
    @Override
    public boolean canHandle(final DownloadLink link, final Account account) throws Exception {
        if ((!useAPI() || account == null) && this.itemHasBeenCrawledViaAPI(link) && !isSingleFile(link)) {
            /* API-item not downloadable via website */
            /*
             * API items have other content-IDs which cannot be accessed via website. This means some items which have been crawled via API
             * can only be downloaded via API, NOT via website!
             */
            return false;
        } else if ((useAPI() && account != null) && link.getPluginPatternMatcher().matches(TYPE_SC_GALLERY)) {
            /* API cannot download image gallerys atm as there are no API calls for such galleries but they are not widely used anyways! */
            return false;
        }
        /* All other cases should work fine via API and website! */
        return true;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    /**
     * Sign an OAuth GET request with HMAC-SHA1 according to OAuth Core spec 1.0
     *
     * @return new url including signature
     * @throws PluginException
     */
    public/* static */String signOAuthURL(String url, String oauth_consumer_key, String oauth_consumer_secret, String oauth_token, String oauth_token_secret) throws PluginException {
        // At first, we remove all OAuth parameters from the url. We add
        // them
        // all manually.
        url = url.replaceAll("[\\?&]oauth_\\w+?=[^&]+", "");
        url += (url.contains("?") ? "&" : "?") + "oauth_consumer_key=" + oauth_consumer_key;
        url += "&oauth_nonce=" + generateNonce();
        url += "&oauth_signature_method=HMAC-SHA1";
        url += "&oauth_timestamp=" + generateTimestamp();
        url += "&oauth_token=" + oauth_token;
        url += "&oauth_version=1.0";
        String signatureBaseString = Encoding.urlEncode(url);
        signatureBaseString = signatureBaseString.replaceFirst("%3F", "&");
        // See OAuth 1.0 spec Appendix A.5.1
        signatureBaseString = "GET&" + signatureBaseString;
        String keyString = oauth_consumer_secret + "&" + oauth_token_secret;
        String signature = "";
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec secret = new SecretKeySpec(keyString.getBytes("UTF-8"), "HmacSHA1");
            mac.init(secret);
            byte[] digest = mac.doFinal(signatureBaseString.getBytes("UTF-8"));
            signature = new String(org.appwork.utils.encoding.Base64.encodeToString(digest, false)).trim();
        } catch (Exception e) {
            logger.log(e);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        url += "&oauth_signature=" + Encoding.urlEncode(signature);
        return url;
    }

    public static class DropboxAccountFactory extends MigPanel implements AccountBuilderInterface {
        /**
         *
         */
        private static final long serialVersionUID = 1L;
        private final String      AUTHHELP         = "Enter your Authorization code";
        private final String      client_id        = getAPIClientIDDev();

        private String getPassword() {
            if (this.pass == null) {
                return null;
            }
            if (EMPTYPW.equals(new String(this.pass.getPassword()))) {
                return null;
            }
            return new String(this.pass.getPassword());
        }

        public boolean updateAccount(Account input, Account output) {
            boolean changed = false;
            if (!StringUtils.equals(input.getUser(), output.getUser())) {
                output.setUser(input.getUser());
                changed = true;
            }
            if (!StringUtils.equals(input.getPass(), output.getPass())) {
                output.setPass(input.getPass());
                changed = true;
            }
            return changed;
        }

        private final ExtPasswordField pass;
        private static String          EMPTYPW = "                 ";

        public DropboxAccountFactory(final InputChangedCallbackInterface callback) {
            /* TODO: Add Headless handling */
            super("ins 0, wrap 2", "[][grow,fill]", "");
            add(new JLabel("Click here to get your authorization code:"));
            add(new JLink("https://www.dropbox.com/oauth2/authorize?client_id=" + client_id + "&response_type=code&force_reapprove=false"));
            // add(new JLink("https://www.dropbox.com/oauth2/authorize?client_id=" + DropboxCom.getAPIClientID() +
            // "&response_type=code&force_reapprove=false"));
            add(new JLabel("Authorization code:"));
            add(this.pass = new ExtPasswordField() {
                @Override
                public void onChanged() {
                    callback.onChangedInput(this);
                }
            }, "");
            pass.setHelpText(AUTHHELP);
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
            // final String userName = getUsername();
            // if (userName == null || !userName.trim().matches("^\\d{9}$")) {
            // idLabel.setForeground(Color.RED);
            // return false;
            // }
            // idLabel.setForeground(Color.BLACK);
            return getPassword() != null;
        }

        @Override
        public Account getAccount() {
            return new Account(null, getPassword());
        }
    }
}