//jDownloader - Downloadmanager
//Copyright (C) 2010  JD-Team support@jdownloader.org
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
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.appwork.utils.StringUtils;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountRequiredException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.decrypter.PCloudComFolder;
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision: 48296 $", interfaceVersion = 2, names = { "pcloud.com" }, urls = { "https?://pclouddecrypted\\.com/\\d+" })
public class PCloudCom extends PluginForHost {
    @SuppressWarnings("deprecation")
    public PCloudCom(PluginWrapper wrapper) {
        super(wrapper);
        setConfigElements();
        this.enablePremium("https://my.pcloud.com/#page=register");
    }

    @Override
    public Browser createNewBrowserInstance() {
        final Browser br = new Browser();
        PCloudComFolder.prepBR(br);
        return br;
    }

    @Override
    public String getPluginContentURL(final DownloadLink link) {
        String folderid = null;
        try {
            folderid = this.getFolderID(link);
        } catch (final PluginException ignore) {
        }
        final String parentfolderid = link.getStringProperty("plain_parentfolderid");
        if (folderid != null && parentfolderid != null) {
            return "https://u.pcloud.link/publink/show?code=" + folderid + "#folder=" + parentfolderid + "&tpl=publicfoldergrid";
        } else {
            return super.getPluginContentURL(link);
        }
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        String id = null;
        try {
            final String fileid = getFID(link);
            final String folderID = getFolderID(link);
            if (fileid != null) {
                id = folderID + "_" + fileid;
            }
        } catch (final Throwable e) {
        }
        if (id != null) {
            return "pcloud://" + id;
        } else {
            return super.getLinkID(link);
        }
    }

    @Override
    public String getAGBLink() {
        return "https://my.pcloud.com/#page=policies&tab=terms-of-service";
    }

    private static final String  NICE_HOST                                       = "pcloud.com";
    /* Plugin Settings */
    private static final String  DOWNLOAD_ZIP                                    = "DOWNLOAD_ZIP_2";
    private static final String  MOVE_FILES_TO_ACCOUNT                           = "MOVE_FILES_TO_ACCOUNT";
    private static final String  DELETE_FILE_AFTER_DOWNLOADLINK_CREATION         = "DELETE_FILE_AFTER_DOWNLOADLINK_CREATION";
    private static final String  DELETE_FILE_FOREVER_AFTER_DOWNLOADLINK_CREATION = "DELETE_FILE_FOREVER_AFTER_DOWNLOADLINK_CREATION";
    private static final String  EMPTY_TRASH_AFTER_DOWNLOADLINK_CREATION         = "EMPTY_TRASH_AFTER_DOWNLOADLINK_CREATION";
    /* Errorcodes */
    private static final int     STATUS_CODE_OKAY                                = 0;
    private static final int     STATUS_CODE_PREMIUMONLY                         = 7005;
    private static final int     STATUS_CODE_MAYBE_OWNER_ONLY                    = 2003;
    private static final int     STATUS_CODE_WRONG_LOCATION                      = 2321;
    private static final int     STATUS_CODE_INVALID_LOGIN                       = 2000;
    /* Connection stuff */
    private static final boolean FREE_RESUME                                     = true;
    private static final int     FREE_MAXCHUNKS                                  = 0;
    private static final int     FREE_MAXDOWNLOADS                               = -1;
    private int                  statusCode                                      = 0;
    private String               downloadURL                                     = null;

    public static String getAPIDomain(final String linkDomain) {
        if (linkDomain.matches("(?i).*e\\d*\\.pcloud\\.(com|link)")) {
            // europe data center
            return "eapi.pcloud.com";
        } else {
            // us data center
            return "api.pcloud.com";
        }
    }

    public static String getAPIDomain(final DownloadLink link) throws Exception {
        final String mainLink = link.getStringProperty("mainlink");
        if (mainLink != null) {
            return getAPIDomain(new URL(mainLink).getHost());
        } else {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, Exception {
        this.setBrowserExclusive();
        final String filename = link.getStringProperty("plain_name");
        final String filesize = link.getStringProperty("plain_size");
        if (filename == null || filesize == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else {
            link.setFinalFileName(filename);
            link.setDownloadSize(Long.parseLong(filesize));
            downloadURL = getDownloadURL(link, null, null, true);
            return AvailableStatus.TRUE;
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        doDownloadURL(link, null, null, true);
    }

    public void doDownloadURL(final DownloadLink link, final Account account, final String account_auth, final boolean publicDownload) throws Exception, PluginException {
        final String directLinkID = account != null ? "account_dllink" : "free_dllink";
        if (downloadURL == null) {
            downloadURL = checkDirectLink(link, directLinkID);
            if (downloadURL == null) {
                downloadURL = getDownloadURL(link, account, account_auth, publicDownload);
                if (downloadURL == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
        }
        boolean resume = FREE_RESUME;
        int maxchunks = FREE_MAXCHUNKS;
        if (isCompleteFolder(link)) {
            resume = false;
            maxchunks = 1;
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, downloadURL, resume, maxchunks);
        if (!looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setProperty(directLinkID, downloadURL);
        dl.startDownload();
    }

    private String getDownloadURL(final DownloadLink link, final Account account, final String account_auth, final boolean publicDownload) throws Exception {
        final String code = getFolderID(link);
        if (isCompleteFolder(link)) {
            if (account_auth != null) {
                br.getPage("https://" + getAPIDomain(link) + "/showpublink?code=" + code + "&auth=" + account_auth);
            } else {
                br.getPage("https://" + getAPIDomain(link) + "/showpublink?code=" + code);
            }
            this.updatestatuscode();
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            handleAPIErrors(br);
            /* Select all IDs of the folder to download all as .zip */
            final String[] fileids = br.getRegex("\"fileid\": (\\d+)").getColumn(0);
            if (fileids == null || fileids.length == 0) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            String dllink = "https://" + getAPIDomain(link) + "/getpubzip?fileids=";
            for (int i = 0; i < fileids.length; i++) {
                final String currentID = fileids[i];
                if (i == fileids.length - 1) {
                    dllink += currentID;
                } else {
                    dllink += currentID + "%2C";
                }
            }
            dllink += "&filename=" + link.getStringProperty("plain_name", null) + "&code=" + code;
            if (account_auth != null) {
                dllink += "&auth=" + account_auth;
            }
            return dllink;
        } else {
            final String fileid = getFID(link);
            if (account_auth != null) {
                if (publicDownload) {
                    br.getPage("https://" + getAPIDomain(link) + "/getpublinkdownload?code=" + code + "&forcedownload=1&fileid=" + fileid + "&auth=" + account_auth);
                    this.updatestatuscode();
                    if (statusCode == STATUS_CODE_MAYBE_OWNER_ONLY) {
                        br.getPage("https://" + getAPIDomain(link) + "/getfilelink?code=" + code + "&forcedownload=1&fileid=" + fileid + "&auth=" + account_auth);
                    }
                } else {
                    br.getPage("https://" + getAPIDomain(link) + "/getfilelink?code=" + code + "&forcedownload=1&fileid=" + fileid + "&auth=" + account_auth);
                }
            } else {
                br.getPage("https://" + getAPIDomain(link) + "/getpublinkdownload?code=" + code + "&forcedownload=1&fileid=" + fileid);
            }
            this.updatestatuscode();
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            handleAPIErrors(br);
            final String hoststext = br.getRegex("\"hosts\": \\[(.*?)\\]").getMatch(0);
            if (hoststext == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final String[] hosts = new Regex(hoststext, "\"([^<>\"]*?)\"").getColumn(0);
            String dllink = PluginJSonUtils.getJsonValue(br, "path");
            if (dllink == null || hosts == null || hosts.length == 0) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dllink = dllink.replace("\\", "");
            dllink = "https://" + hosts[new Random().nextInt(hosts.length - 1)] + dllink;
            return dllink;
        }
    }

    private boolean isCompleteFolder(final DownloadLink dl) {
        return dl.getBooleanProperty("complete_folder", false);
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    private String login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                String account_auth = account.getStringProperty("account_auth", null);
                String account_api = account.getStringProperty("account_api", null);
                if (cookies != null && StringUtils.isAllNotEmpty(account_auth, account_api)) {
                    br.setCookies(cookies);
                    if (!force) {
                        logger.info("Trust token without checking");
                        return account_auth;
                    }
                    br.getPage("https://" + account_api + "/userinfo?auth=" + Encoding.urlEncode(account_auth) + "&getlastsubscription=1");
                    try {
                        updatestatuscode();
                        this.handleAPIErrors(br);
                        logger.info("Token login successful");
                        updateAccountInfo(account, br);
                        return account_auth;
                    } catch (final PluginException e) {
                        /* Wrong token = Will typically fail with errorcode 2000 */
                        logger.info("Token login failed");
                        logger.log(e);
                        br.clearAll();
                    }
                }
                logger.info("Performing full login");
                /* Depending on which selection the user met when he registered his account, a different endpoint is required for login. */
                try {
                    logger.info("Trying to login via US-API endpoint");
                    postAPISafe("https://api.pcloud.com/login", "logout=1&getauth=1&username=" + Encoding.urlEncode(account.getUser()) + "&password=" + Encoding.urlEncode(account.getPass()) + "&_t=" + System.currentTimeMillis());
                } catch (PluginException e) {
                    if (statusCode == STATUS_CODE_WRONG_LOCATION || statusCode == STATUS_CODE_INVALID_LOGIN) {
                        logger.info("Trying to login via EU-API endpoint");
                        postAPISafe("https://eapi.pcloud.com/login", "logout=1&getauth=1&username=" + Encoding.urlEncode(account.getUser()) + "&password=" + Encoding.urlEncode(account.getPass()) + "&_t=" + System.currentTimeMillis());
                    } else {
                        throw e;
                    }
                }
                final String emailverified = PluginJSonUtils.getJson(br, "emailverified");
                if (emailverified != null && !emailverified.equals("true")) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nDein Account ist noch nicht verifiziert!\r\nPrüfe deine E-Mails und verifiziere deinen Account!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nYour account is not yet verified!\r\nCheck your mails and verify it!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account_auth = PluginJSonUtils.getJsonValue(br, "auth");
                if (StringUtils.isEmpty(account_auth)) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                } else {
                    account_api = br.getHost(true);
                    getAPISafe("https://" + account_api + "/userinfo?auth=" + Encoding.urlEncode(account_auth) + "&getlastsubscription=1");
                    account.setProperty("account_api", account_api);
                    account.setProperty("account_auth", account_auth);
                    account.saveCookies(br.getCookies(br.getURL()), "");
                    updateAccountInfo(account, br);
                    return account_auth;
                }
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                    account.removeProperty("account_auth");
                    account.removeProperty("account_api");
                }
                throw e;
            }
        }
    }

    private void updateAccountInfo(Account account, Browser br) {
        synchronized (account) {
            final String premium = PluginJSonUtils.getJsonValue(br, "premium");
            if ("true".equals(premium)) {
                account.setType(AccountType.PREMIUM);
                account.setMaxSimultanDownloads(20);
                account.setConcurrentUsePossible(true);
            } else {
                account.setType(AccountType.FREE);
                /* Last checked: 2020-10-06 */
                account.setMaxSimultanDownloads(20);
                account.setConcurrentUsePossible(true);
            }
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        if (!account.getUser().matches(".+@.+\\..+")) {
            if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nBitte gib deine E-Mail Adresse ins Benutzername Feld ein!", PluginException.VALUE_ID_PREMIUM_DISABLE);
            } else {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlease enter your e-mail address in the username field!", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
        }
        login(account, true);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        PluginException cause = null;
        try {
            requestFileInformation(link);
        } catch (final PluginException e) {
            switch (statusCode) {
            case STATUS_CODE_PREMIUMONLY:
            case STATUS_CODE_MAYBE_OWNER_ONLY:
                cause = e;
                break;
            default:
                throw e;
            }
        }
        final String account_auth;
        final String account_api;
        synchronized (account) {
            account_auth = login(account, false);
            account_api = account.getStringProperty("account_api", null);
            if (account_api == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        boolean publicDownload = true;
        if (STATUS_CODE_MAYBE_OWNER_ONLY == statusCode) {
            final String code = getFolderID(link);
            getAPISafe("https://" + getAPIDomain(link) + "/showpublink?code=" + code + "&auth=" + account_auth);
            final String ownerisme = PluginJSonUtils.getJson(br, "ownerisme");
            if (StringUtils.equals(ownerisme, "true")) {
                // TODO: store Account.getId() or "userid"(userinfo) to DownloadLink, to avoid same handling on next download try
                publicDownload = false;
            } else {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, null, PluginException.VALUE_ID_PREMIUM_ONLY, cause);
            }
        }
        if (STATUS_CODE_PREMIUMONLY == statusCode) {
            if (!isCompleteFolder(link) && StringUtils.equals(account_api, getAPIDomain(link)) && this.getPluginConfig().getBooleanProperty(MOVE_FILES_TO_ACCOUNT, defaultMOVE_FILES_TO_ACCOUNT)) {
                /*
                 * only possible to copy files on same data center region!
                 *
                 * not yet implemented for complete folder(zip)
                 */
                /* tofolderid --> 0 = root */
                final String code = getFolderID(link);
                final String fileid = getFID(link);
                getAPISafe("https://" + getAPIDomain(link) + "/copypubfile?fileid=" + fileid + "&tofolderid=0&code=" + code + "&auth=" + account_auth);
                final String new_fileid = PluginJSonUtils.getJsonValue(br, "fileid");
                final String new_hash = PluginJSonUtils.getJsonValue(br, "hash");
                final String api_filename = PluginJSonUtils.getJsonValue(br, "name");
                if (new_fileid == null || new_hash == null || api_filename == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                getAPISafe("/getfilelink?fileid=" + new_fileid + "&hashCache=" + new_hash + "&forcedownload=1&auth=" + account_auth);
                final Map<String, Object> entries = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
                final List<Object> ressourcelist = (List) entries.get("hosts");
                final String path = PluginJSonUtils.getJsonValue(br, "path");
                if (ressourcelist == null || path == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final String download_host = (String) ressourcelist.get(new Random().nextInt(ressourcelist.size() - 1));
                downloadURL = "https://" + download_host + path;
                if (this.getPluginConfig().getBooleanProperty(DELETE_FILE_AFTER_DOWNLOADLINK_CREATION, defaultDELETE_DELETE_FILE_AFTER_DOWNLOADLINK_CREATION)) {
                    /*
                     * It sounds crazy but we'll actually delete the file before we download it as the directlink will still be valid and
                     * this way we avoid filling up the space of our account.
                     */
                    getAPISafe("/deletefile?fileid=" + new_fileid + "&name=" + Encoding.urlEncode(api_filename) + "&id=000-0&auth=" + account_auth);
                    if (this.getPluginConfig().getBooleanProperty(DELETE_FILE_FOREVER_AFTER_DOWNLOADLINK_CREATION, defaultDELETE_FILE_FOREVER_AFTER_DOWNLOADLINK_CREATION)) {
                        /* Delete file inside trash (FOREVER) in case user wants that. */
                        getAPISafe("/trash_clear?fileid=" + new_fileid + "&id=000-0&auth=" + account_auth);
                    }
                }
                if (this.getPluginConfig().getBooleanProperty(EMPTY_TRASH_AFTER_DOWNLOADLINK_CREATION, defaultEMPTY_TRASH_AFTER_DOWNLOADLINK_CREATION)) {
                    /* Let's empty the trash in case the user wants this. */
                    getAPISafe("/trash_clear?folderid=0&auth=" + account_auth);
                }
            } else if (!Account.AccountType.PREMIUM.equals(account.getType())) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
            }
        } else {
            // use cached Link or generate fresh one with account
            downloadURL = null;
        }
        doDownloadURL(link, account, account_auth, publicDownload);
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

    private String getFolderID(final DownloadLink dl) throws PluginException {
        final String ret = dl.getStringProperty("plain_code");
        if (ret == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else {
            return ret;
        }
    }

    private String getFID(final DownloadLink dl) throws PluginException {
        final String ret = dl.getStringProperty("plain_fileid");
        if (ret == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else {
            return ret;
        }
    }

    private String getAPISafe(final String accesslink) throws IOException, PluginException {
        br.getPage(accesslink);
        updatestatuscode();
        handleAPIErrors(this.br);
        return this.br.toString();
    }

    private String postAPISafe(final String accesslink, final String postdata) throws IOException, PluginException {
        br.postPage(accesslink, postdata);
        updatestatuscode();
        handleAPIErrors(this.br);
        return this.br.toString();
    }

    private void handleAPIErrors(final Browser br) throws PluginException {
        String statusMessage = null;
        try {
            switch (statusCode) {
            case STATUS_CODE_OKAY:
                /* Everything ok */
                break;
            case 1029:
                throw new AccountRequiredException();
            case STATUS_CODE_INVALID_LOGIN:
                if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                    statusMessage = "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen? Versuche folgendes:\r\n1. Falls dein Passwort Sonderzeichen enthält, ändere es (entferne diese) und versuche es erneut!\r\n2. Gib deine Zugangsdaten per Hand (ohne kopieren/einfügen) ein.";
                } else {
                    statusMessage = "\r\nInvalid username/password!\r\nYou're sure that the username and password you entered are correct? Some hints:\r\n1. If your password contains special characters, change it (remove them) and try again!\r\n2. Type in your username/password by hand without copy & paste.";
                }
                throw new AccountInvalidException(statusMessage);
            case 2008:
                if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                    statusMessage = "\r\nDein Account hat keinen freien Speicherplatz mehr!";
                } else {
                    statusMessage = "\r\nYour account has no free space anymore!";
                }
                throw new AccountInvalidException(statusMessage);
            case 2009:
                /* { "result": 2009, "error": "File not found."} */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            case STATUS_CODE_WRONG_LOCATION:
                // wrong location
                /*
                 * {"result": 2321, "error": "This user is on another location.", "location": { "label": "US", "id": 1, "binapi":
                 * "binapi.pcloud.com", "api": "api.pcloud.com" }}
                 */
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            case 5002:
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Server error 'Internal error, no servers available. Try again later.'", 5 * 60 * 1000l);
            case 7002:
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            case STATUS_CODE_PREMIUMONLY:
                throw new AccountRequiredException();
            case STATUS_CODE_MAYBE_OWNER_ONLY:
                /* file might be set to preview only download */
                /* "error": "Access denied. You do not have permissions to perform this operation." */
                throw new AccountRequiredException();
            case 7014:
                /*
                 * 2016-08-31: Added support for this though I'm not sure about this - I guess some kind of account traffic limit has been
                 * reached!
                 */
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            default:
                /* Unknown error */
                statusMessage = "This file can only be downloaded by registered/premium users";
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "This file can only be downloaded by registered/premium users");
            }
        } catch (final PluginException e) {
            logger.info(NICE_HOST + ": Exception: statusCode: " + statusCode + " statusMessage: " + statusMessage);
            throw e;
        }
    }

    // private String postRawAPISafe(final String accesslink, final String postdata) throws IOException, PluginException {
    // br.postPageRaw(accesslink, postdata);
    // updatestatuscode();
    // handleAPIErrors(this.br);
    // return this.br.toString();
    // }
    /**
     * 0 = everything ok, 2000-??? = Normal "result" API errorcodes, 666 = hell
     */
    private void updatestatuscode() {
        final String error = PluginJSonUtils.getJsonValue(br, "result");
        if (error != null) {
            statusCode = Integer.parseInt(error);
        }
    }

    private static final boolean defaultDOWNLOAD_ZIP                                    = false;
    private static final boolean defaultMOVE_FILES_TO_ACCOUNT                           = false;
    private static final boolean defaultDELETE_DELETE_FILE_AFTER_DOWNLOADLINK_CREATION  = false;
    private static final boolean defaultDELETE_FILE_FOREVER_AFTER_DOWNLOADLINK_CREATION = false;
    private static final boolean defaultEMPTY_TRASH_AFTER_DOWNLOADLINK_CREATION         = false;

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "Crawler settings:"));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), PCloudCom.DOWNLOAD_ZIP, JDL.L("plugins.hoster.PCloudCom.DownloadZip", "Download .zip file of all files in the folder?")).setDefaultValue(defaultDOWNLOAD_ZIP));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "Host plugin settings:"));
        final ConfigEntry moveFilesToAcc = new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), MOVE_FILES_TO_ACCOUNT, JDL.L("plugins.hoster.PCloudCom.MoveFilesToAccount", "1. Move files with too high traffic to account before downloading them to avoid downloadlimits?")).setDefaultValue(defaultMOVE_FILES_TO_ACCOUNT);
        getConfig().addEntry(moveFilesToAcc);
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), MOVE_FILES_TO_ACCOUNT, JDL.L("plugins.hoster.PCloudCom.DeleteMovedFiles", "2. Delete moved files after downloadlink-generation?")).setEnabledCondidtion(moveFilesToAcc, true).setDefaultValue(defaultDELETE_DELETE_FILE_AFTER_DOWNLOADLINK_CREATION));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), DELETE_FILE_FOREVER_AFTER_DOWNLOADLINK_CREATION, JDL.L("plugins.hoster.PCloudCom.DeleteMovedFilesForever", "3. Delete moved files FOREVER (inside trash) after downloadlink-generation?")).setEnabledCondidtion(moveFilesToAcc, true).setDefaultValue(defaultDELETE_FILE_FOREVER_AFTER_DOWNLOADLINK_CREATION));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), EMPTY_TRASH_AFTER_DOWNLOADLINK_CREATION, JDL.L("plugins.hoster.PCloudCom.EmptyTrashAfterSuccessfulDownload", "4. Empty trash after downloadlink-generation?")).setEnabledCondidtion(moveFilesToAcc, true).setDefaultValue(defaultEMPTY_TRASH_AFTER_DOWNLOADLINK_CREATION));
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }
}