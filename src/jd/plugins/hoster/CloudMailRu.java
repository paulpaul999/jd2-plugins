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
import java.util.List;
import java.util.Map;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.Request;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.utils.locale.JDL;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.URLEncode;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.scripting.JavaScriptEngineFactory;

@HostPlugin(revision = "$Revision: 48194 $", interfaceVersion = 2, names = { "cloud.mail.ru" }, urls = { "https?://cloud\\.mail\\.ru/public/[A-Za-z0-9]+/[A-Za-z0-9]+.*|https?://[a-z0-9]+\\.datacloudmail\\.ru/weblink/(view|get)/[a-z0-9]+/[^<>\"/]+/[^<>\"/]+" })
public class CloudMailRu extends PluginForHost {
    public CloudMailRu(PluginWrapper wrapper) {
        super(wrapper);
        setConfigElements();
        /* 2021-01-06: Dropped account support as it does not provide any advantages over downloading anonymously. */
        // this.enablePremium("https://cloud.mail.ru/");
    }

    private static final String  TYPE_FROM_DECRYPTER       = "https?://cloud\\.mail\\.ru/public/[A-Za-z0-9]+/[A-Za-z0-9].*";
    private static final String  TYPE_HOTLINK              = "https?://[a-z0-9]+\\.datacloudmail\\.ru/weblink/(view|get)/[a-z0-9]+/[^<>\"/]+/[^<>\"/]+";
    private static final String  NOCHUNKS                  = "NOCHUNKS";
    private static final String  DOWNLOAD_ZIP              = "DOWNLOAD_ZIP_2";
    public static final String   API_BASE                  = "https://cloud.mail.ru/api/v2";
    /* Connection stuff */
    private static final boolean FREE_RESUME               = true;
    private static final int     FREE_MAXCHUNKS            = 0;
    private static final int     FREE_MAXDOWNLOADS         = -1;
    private static final boolean ACCOUNT_FREE_RESUME       = true;
    private static final int     ACCOUNT_FREE_MAXCHUNKS    = 0;
    private static final int     ACCOUNT_FREE_MAXDOWNLOADS = -1;
    private static final boolean ACCOUNT_PREMIUM_RESUME    = true;
    private static final int     ACCOUNT_PREMIUM_MAXCHUNKS = 0;
    /* DownloadLink properties */
    public static final String   PROPERTY_WEBLINK          = "cloudmailru_weblink";
    public static final String   PROPERTY_COMPLETE_FOLDER  = "complete_folder";

    @Override
    public String getAGBLink() {
        return "https://cloud.mail.ru/";
    }

    private static final String BUILD = jd.plugins.decrypter.CloudMailRuDecrypter.BUILD;

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        if (link.getBooleanProperty("offline", false)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        this.setBrowserExclusive();
        prepBR();
        if (link.getDownloadURL().matches(TYPE_HOTLINK)) {
            URLConnectionAdapter con = null;
            final String dlink = getdllink(link, "free_directlink");
            try {
                final Browser br2 = br.cloneBrowser();
                con = br2.openGetConnection(dlink);
                if (this.looksLikeDownloadableContent(con)) {
                    link.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(con)).trim());
                    if (con.getCompleteContentLength() > 0) {
                        link.setDownloadSize(con.getCompleteContentLength());
                        link.setVerifiedFileSize(con.getCompleteContentLength());
                    }
                } else {
                    br2.followConnection(true);
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        } else {
            /** TODO: Remove this */
            /* Check if main-folder still exists */
            if (link.getBooleanProperty("noapi", false)) {
                br.getPage(getContentURL(link));
                if (br.getHttpConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            } else {
                br.getPage(API_BASE + "/folder?weblink=" + URLEncode.encodeURIComponent(getWeblink(link)) + "&sort=%7B%22type%22%3A%22name%22%2C%22order%22%3A%22asc%22%7D&offset=0&limit=500&api=2&build=" + BUILD);
                if (br.containsHTML("\"status\":400")) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        doFree(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void doFree(final DownloadLink link, boolean resume, int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        requestFileInformation(link);
        final String dllink = getdllink(link, directlinkproperty);
        if (isCompleteFolder(link)) {
            resume = false;
            maxchunks = 1;
        }
        if (link.getBooleanProperty(NOCHUNKS, false)) {
            maxchunks = 1;
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resume, maxchunks);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            if (dl.getConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 30 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        link.setProperty("plain_directlink", dllink);
        try {
            if (!this.dl.startDownload()) {
                try {
                    if (dl.externalDownloadStop()) {
                        return;
                    }
                } catch (final Throwable e) {
                }
                /* unknown error, we disable multiple chunks */
                if (link.getBooleanProperty(CloudMailRu.NOCHUNKS, false) == false) {
                    link.setProperty(CloudMailRu.NOCHUNKS, Boolean.valueOf(true));
                    throw new PluginException(LinkStatus.ERROR_RETRY);
                }
            }
        } catch (final PluginException e) {
            if (e.getLinkStatus() == LinkStatus.ERROR_DOWNLOAD_INCOMPLETE) {
                logger.info("ERROR_DOWNLOAD_INCOMPLETE --> Handling it");
                if (link.getBooleanProperty(NOCHUNKS, false)) {
                    link.setProperty(NOCHUNKS, Boolean.valueOf(false));
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 30 * 60 * 1000l);
                }
                link.setProperty(NOCHUNKS, Boolean.valueOf(true));
                link.setChunksProgress(null);
                throw new PluginException(LinkStatus.ERROR_RETRY, "ERROR_DOWNLOAD_INCOMPLETE");
            }
            // New V2 errorhandling
            /* unknown error, we disable multiple chunks */
            if (e.getLinkStatus() != LinkStatus.ERROR_RETRY && link.getBooleanProperty(CloudMailRu.NOCHUNKS, false) == false) {
                link.setProperty(CloudMailRu.NOCHUNKS, Boolean.valueOf(true));
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
            throw e;
        }
    }

    @SuppressWarnings({ "deprecation", "unchecked", "rawtypes" })
    private String getdllink(final DownloadLink link, final String directlinkproperty) throws Exception {
        String dllink = checkDirectLink(link, "plain_directlink");
        if (dllink == null) {
            if (link.getDownloadURL().matches(TYPE_HOTLINK)) {
                dllink = link.getDownloadURL();
            } else if (isCompleteFolder(link)) {
                br.postPage(API_BASE + "/zip", "weblink_list=%5B%22" + URLEncode.encodeURIComponent(this.getWeblink(link)) + "%22%5D&name=" + URLEncode.encodeURIComponent(link.getName()) + "&cp866=false&api=2&build=" + BUILD);
                dllink = PluginJSonUtils.getJsonValue(br, "body");
            } else if (link.getBooleanProperty("noapi", false)) {
                br.getPage(getContentURL(link));
                final String json = br.getRegex("(\\{\\s*\"tree\":.*?)\\);").getMatch(0);
                if (json == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final Map<String, Object> entries = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(json);
                final Map<String, Object> folder = (Map<String, Object>) entries.get("folder");
                final List<Object> list = (List) folder.get("list");
                for (final Object o : list) {
                    final Map<String, Object> filemap = (Map<String, Object>) o;
                    final Map<String, Object> url = (Map<String, Object>) filemap.get("url");
                    final String get_url = (String) url.get("get");
                    if (Encoding.htmlOnlyDecode(get_url, false).contains(link.getName())) {
                        if (get_url.startsWith("//")) {
                            dllink = Request.getLocation(get_url, br.getRequest());
                        } else {
                            dllink = get_url;
                        }
                        break;
                    }
                }
            } else {
                logger.info("Failed to use saved dllink, trying to generate new link");
                String dataserver = null;
                String pageid = null;
                this.br.getPage(getContentURL(link));
                final String web_json = this.br.getRegex("window\\[\"__configObject[^<>\"]+\"\\] =(\\{.*?\\});<").getMatch(0);
                if (web_json != null) {
                    // using linkedhashmap here will result in exception
                    // java.lang.ClassCastException: java.util.HashMap cannot be cast to java.util.LinkedHashMap
                    // irc report - raztoki20160619
                    // final Map<String, Object> entries = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(web_json);
                    // dataserver = (String) JavaScriptEngineFactory.walkJson(entries, "dispatcher/weblink_get/{0}/url");
                    // pageid = (String) JavaScriptEngineFactory.walkJson(entries, "params/x-page-id");
                    // final Map<String, Object> page_info = (Map<String, Object>) entries.get("");
                    // final List<Object> ressourcelist = (List) entries.get("");
                }
                if (pageid == null) {
                    pageid = PluginJSonUtils.getJson(br, "x-page-id");
                    if (StringUtils.isEmpty(pageid)) {
                        /* 2021-04-09 */
                        pageid = PluginJSonUtils.getJson(br, "pageId");
                    }
                }
                if (StringUtils.isEmpty(pageid)) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                /*
                 * 2020-06-18: Seems like this API does not work anymore as it would always return 403 but we can download without token
                 * parameter ...
                 */
                br.postPage(API_BASE + "/tokens/download", "api=2&build=" + BUILD + "&x-page-id=" + pageid + "&email=anonym&x-email=anonym&_=" + System.currentTimeMillis());
                final String token = PluginJSonUtils.getJsonValue(br, "token");
                if (dataserver == null) {
                    /* Usually this should not be needed! */
                    logger.info("Trying to find dataserver");
                    br.getPage("/api/v2/dispatcher?api=2&build=" + BUILD + "&x-page-id=" + pageid + "&email=anonym&x-email=anonym&_=" + System.currentTimeMillis());
                    final Map<String, Object> entries = restoreFromString(br.toString(), TypeRef.MAP);
                    dataserver = (String) JavaScriptEngineFactory.walkJson(entries, "body/weblink_get/{0}/url");
                    /*
                     * 2020-08-04: Use of static host is also possible: e.g.
                     * https://github.com/Friday14/mailru-cloud-php/blob/master/src/Cloud.php
                     */
                }
                if (dataserver != null) {
                    if (!StringUtils.isEmpty(token)) {
                        /* Old way - won't work as long as the "/tokens/download" API request is broken! */
                        String encoded_weblink = URLEncode.encodeURIComponent(getWeblink(link));
                        /* We need the "/" so let's encode them back. */
                        encoded_weblink = encoded_weblink.replace("%2F", "/");
                        encoded_weblink = encoded_weblink.replace("+", "%20");
                        dllink = dataserver + "/" + encoded_weblink;
                        dllink += "?key=" + token;
                    } else {
                        if (link.getPluginPatternMatcher().matches("http://clouddecrypted\\.mail\\.ru/\\d+")) {
                            /*
                             * "Backwards compatibility": TODO: Remove this workaround - it is only required for older items. Remove in
                             * 2021-04-XX
                             */
                            dllink = dataserver + "/" + URLEncode.encodeURIComponent(link.getStringProperty("unique_id"));
                        } else {
                            String encoded_weblink = URLEncode.encodeURIComponent(getWeblink(link));
                            /* We need the "/" so let's encode them back. */
                            encoded_weblink = encoded_weblink.replace("%2F", "/");
                            encoded_weblink = encoded_weblink.replace("+", "%20");
                            dllink = dataserver + "/" + encoded_weblink;
                        }
                    }
                } else {
                    logger.warning("Failed to find dataserver for finallink");
                }
            }
        }
        if (dllink == null) {
            /* 2020-06-18: We're using an API - no need to throw a PLUGIN_DEFECT error in this case! */
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown API download failure");
        } else {
            return dllink;
        }
    }

    private String getContentURL(final DownloadLink link) {
        if (link.hasProperty("mainlink")) {
            /*
             * "Backwards compatibility": TODO: Remove this workaround - it is only required for older items. Remove in 2021-04-XX
             */
            return link.getStringProperty("mainlink");
        } else {
            return link.getPluginPatternMatcher();
        }
    }

    private String getWeblink(final DownloadLink dl) throws PluginException {
        final String ret;
        if (dl.hasProperty("plain_request_id")) {
            /*
             * "Backwards compatibility": TODO: Remove this workaround - it is only required for older items. Remove in 2021-04-XX
             */
            ret = dl.getStringProperty("plain_request_id");
        } else {
            /* New 2020-12-18 */
            ret = dl.getStringProperty(PROPERTY_WEBLINK);
        }
        if (ret == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else {
            return ret;
        }
    }

    private boolean isCompleteFolder(final DownloadLink dl) {
        return dl.getBooleanProperty(PROPERTY_COMPLETE_FOLDER, false);
    }

    private void prepBR() {
        br.setFollowRedirects(true);
        br.getHeaders().put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        br.getHeaders().put("Accept-Language", "en-us;q=0.7,en;q=0.3");
        br.getHeaders().put("Accept-Charset", null);
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

    private void login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                /* TODO: Add cookie check */
                if (cookies != null && !force) {
                    this.br.setCookies(this.getHost(), cookies);
                    logger.info("Trust cookies without check");
                    return;
                }
                if (true) {
                    /* 2020-12-16: Login is broken */
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                br.setFollowRedirects(true);
                br.getPage("https://account.mail.ru/login");
                final UrlQuery login1 = new UrlQuery();
                login1.appendEncoded("login", account.getUser());
                login1.add("htmlencoded", "false");
                login1.appendEncoded("referrer", "https://cloud.mail.ru/");
                login1.appendEncoded("email", account.getUser());
                br.getHeaders().put("Referer", "https://account.mail.ru/");
                br.postPage("https://auth.mail.ru/api/v1/pushauth/info", login1);
                Map<String, Object> entries = restoreFromString(br.toString(), TypeRef.MAP);
                final int status = ((Number) entries.get("status")).intValue();
                if (status != 200) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                entries = (Map<String, Object>) entries.get("body");
                final boolean twoStep = ((Boolean) entries.get("twostep")).booleanValue();
                if (twoStep) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "two-factor-authentication is not yet supported!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                final UrlQuery login2 = new UrlQuery();
                login2.appendEncoded("username", account.getUser());
                login2.appendEncoded("Login", account.getUser());
                login2.appendEncoded("password", account.getPass());
                login2.appendEncoded("Password", account.getPass());
                login2.appendEncoded("saveauth", "1");
                login2.appendEncoded("new_auth_form", "1");
                login2.add("FromAccount", "opener%3Daccount%26twoSteps%3D1");
                login2.add("act_token", "TODO");
                login2.add("page", "TODO");
                login2.add("lang", "en_US");
                br.postPage("https://auth.mail.ru/cgi-bin/auth", login2);
                final String mail_domain = account.getUser().split("@")[1];
                final String postData = "page=https%3A%2F%2Fcloud.mail.ru%2F&FailPage=&Domain=" + mail_domain + "&Login=" + URLEncode.encodeURIComponent(account.getUser()) + "&Password=" + URLEncode.encodeURIComponent(account.getPass()) + "&new_auth_form=1&saveauth=1";
                br.postPage("https://auth.mail.ru/cgi-bin/auth?lang=ru_RU&from=authpopup", postData);
                if (br.containsHTML("\\&fail=1") || br.getCookie("http://auth.mail.ru/", "ssdc") == null) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                br.getPage("https://cloud.mail.ru/?from=authpopup");
                account.saveCookies(this.br.getCookies(this.getHost()), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
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
        ai.setUnlimitedTraffic();
        account.setMaxSimultanDownloads(ACCOUNT_FREE_MAXDOWNLOADS);
        account.setConcurrentUsePossible(true);
        account.setType(AccountType.FREE);
        ai.setStatus("Free Account");
        return ai;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        login(account, false);
        br.setFollowRedirects(false);
        br.getPage(link.getDownloadURL());
        if (AccountType.FREE.equals(account.getType())) {
            doFree(link, ACCOUNT_FREE_RESUME, ACCOUNT_FREE_MAXCHUNKS, "account_free_directlink");
            return;
        }
        String dllink = this.checkDirectLink(link, "premium_directlink");
        if (dllink == null) {
            logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, ACCOUNT_PREMIUM_RESUME, ACCOUNT_PREMIUM_MAXCHUNKS);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setProperty("premium_directlink", dllink);
        dl.startDownload();
    }

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), CloudMailRu.DOWNLOAD_ZIP, JDL.L("plugins.hoster.CloudMailRu.DownloadZip", "Download .zip file of all files in the folder?")).setDefaultValue(false));
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }
}