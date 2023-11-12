//    jDownloader - Downloadmanager
//    Copyright (C) 2012  JD-Team support@jdownloader.org
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
import java.io.IOException;
import java.util.Locale;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.captcha.v2.challenge.recaptcha.v1.Recaptcha;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.Property;
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
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision: 48302 $", interfaceVersion = 2, names = { "filesmonster.com" }, urls = { "https?://[\\w\\.\\d]*?filesmonsterdecrypted\\.com/(download\\.php\\?id=|dl/.*?/free/2/).+" })
public class FilesMonsterCom extends PluginForHost {
    private static final String POSTTHATREGEX            = "\"((?:https?://(?:www\\.)?filesmonster\\.com)?/dl/.*?/free/.*?)\"";
    private static final String POSTTHATREGEX2           = "((?:https?://(?:www\\.)?filesmonster\\.com)?/dl/.*?/free/.+)";
    private static final String TEMPORARYUNAVAILABLE     = "Download not available at the moment";
    private static final String REDIRECTFNF              = "DL_FileNotFound";
    private static final String PREMIUMONLYUSERTEXT      = "Only downloadable via premium";
    private static final String ADDLINKSACCOUNTDEPENDANT = "ADDLINKSACCOUNTDEPENDANT";

    public FilesMonsterCom(PluginWrapper wrapper) {
        super(wrapper);
        this.setConfigElements();
        this.enablePremium("http://filesmonster.com/service.php");
    }

    @Override
    public Browser createNewBrowserInstance() {
        final Browser br = new Browser();
        prepBR(br);
        return br;
    }

    public static void prepBR(Browser br) {
        br.setReadTimeout(3 * 60 * 1000);
        br.setCookie("http://filesmonster.com/", "yab_ulanguage", "en");
    }

    public void correctDownloadLink(final DownloadLink link) {
        link.setUrlDownload(link.getDownloadURL().replace("filesmonsterdecrypted.com/", "filesmonster.com/").replace("http://", "https://").replaceFirst("www\\.", ""));
    }

    @Override
    public String getAGBLink() {
        return "http://filesmonster.com/rules.php";
    }

    @Override
    public boolean canHandle(final DownloadLink downloadLink, final Account account) throws Exception {
        if (downloadLink.getBooleanProperty("PREMIUMONLY", false) && account == null) {
            /* premium only */
            return false;
        }
        if (downloadLink.getDownloadURL().contains("/free/2/") && account != null) {
            /* free only */
            return false;
        }
        return true;
    }

    public static String getFileName(Browser br) throws PluginException {
        String ret = br.getRegex("<a class=\"link premium\" href=\"[^\"]+\">\\s*<span class=\"filename\">\\s*([^<>\"]+)\\s*</").getMatch(0);
        if (ret == null) {
            ret = br.getRegex(">\\s*File\\s*name\\s*:\\s*</td>\\s*<td[^>]*>\\s*([^<>\"]+)\\s*</").getMatch(0);
        }
        return ret;
    }

    public static String getFileSize(Browser br) throws PluginException {
        String ret = br.getRegex("<span class=\"size\">\\s*([^<>\"]+)\\s*</span>").getMatch(0);
        if (ret == null) {
            ret = br.getRegex(">\\s*File\\s*size\\s*:\\s*</td>\\s*<td[^>]+>\\s*([^<>\"]+)\\s*</").getMatch(0);
        }
        return ret;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        correctDownloadLink(link);
        br.setFollowRedirects(false);
        link.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
        // br.getHeaders().put("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:51.0) Gecko/20100101 Firefox/51.0");
        if (link.getDownloadURL().contains("/free/2/")) {
            br.getPage(link.getStringProperty("mainlink"));
            // Link offline 404
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (br.getRedirectLocation() != null) {
                if (br.getRedirectLocation().contains(REDIRECTFNF)) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            link.setName(link.getName());
            link.setDownloadSize(link.getDownloadSize());
        } else {
            if (link.getBooleanProperty("offline", false)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            br.getPage(link.getDownloadURL());
            if (isOffline(this.br)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            // link not possible due to ip ban http://svn.jdownloader.org/issues/27798
            if (br.containsHTML(">You do not have permission to access the requested resource on this server.<|>Perhaps your IP address is blocked due to suspicious activity.<")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (br.getRedirectLocation() != null) {
                // Link offline 2
                if (br.getRedirectLocation().contains(REDIRECTFNF)) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final String filename = getFileName(br);
            final String filesize = getFileSize(br);
            if (filename != null) {
                link.setFinalFileName(Encoding.htmlDecode(filename.trim()));
            }
            if (filesize != null) {
                link.setDownloadSize(SizeFormatter.getSize(filesize.trim()));
            }
        }
        if (br.containsHTML(TEMPORARYUNAVAILABLE)) {
            link.getLinkStatus().setStatusText(JDL.L("plugins.hoster.filesmonstercom.temporaryunavailable", "Download not available at the moment"));
        }
        if (link.getBooleanProperty("PREMIUMONLY", false)) {
            link.getLinkStatus().setStatusText(JDL.L("plugins.hoster.filesmonstercom.only4premium", PREMIUMONLYUSERTEXT));
        }
        return AvailableStatus.TRUE;
    }

    public static boolean isOffline(final Browser br) {
        if (br.containsHTML("(>File was deleted by owner or it was deleted for violation of copyrights<|>File not found<|>The link could not be decoded<)")) {
            return true;
        }
        // Link offline 404
        if (br.getHttpConnection().getResponseCode() == 404) {
            return true;
        }
        // Advertising link
        if (br.containsHTML("the file can be accessed at the|>can be accessed at the")) {
            return true;
        }
        return false;
    }

    public static String getMainLinkID(final String mainlink) {
        return new Regex(mainlink, "filesmonster\\.com/(?:download\\.php\\?id=|dl/)([^/]+)").getMatch(0);
    }

    private String getReferer(final DownloadLink link) {
        final String referOld = link.getStringProperty("referer_url"); // backward compatibility
        if (referOld != null) {
            return referOld;
        } else {
            return link.getReferrerUrl();
        }
    }

    private String getNewTemporaryLink(final String mainlink, final String originalfilename) throws Exception {
        /* Find a new temporary link */
        final String mainlinkpart = getMainLinkID(mainlink);
        String temporaryLink = null;
        final String referer_url = getReferer(this.getDownloadLink());
        if (referer_url != null) {
            logger.info("Accessing URL with referer: " + referer_url);
            br.getPage(mainlink + "&wbst=" + referer_url);
        } else {
            logger.info("Accessing URL without referer");
            br.getPage(mainlink);
        }
        if (isOffline(this.br)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String[] allInfo = getTempLinks();
        if (allInfo == null || allInfo.length == 0) {
            handleErrors(this.br);
            return null;
        }
        for (String singleInfo : allInfo) {
            if (singleInfo.contains("\"name\":\"" + originalfilename + "\"")) {
                temporaryLink = new Regex(singleInfo, "\"dlcode\":\"(.*?)\"").getMatch(0);
            }
        }
        if (temporaryLink == null) {
            handleErrors(this.br);
            logger.warning("Failed to find new temporaryLink");
            return null;
        }
        temporaryLink = "http://filesmonster.com/dl/" + mainlinkpart + "/free/2/" + temporaryLink + "/";
        return temporaryLink;
    }

    public static void handleErrors(final Browser br) throws PluginException {
        if (br.containsHTML(TEMPORARYUNAVAILABLE)) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.filesmonstercom.temporaryunavailable", "Download not available at the moment"), 120 * 60 * 1000l);
        }
        String wait = br.getRegex("You can wait for the start of downloading (\\d+)").getMatch(0);
        if (wait == null) {
            wait = br.getRegex("is already in use (\\d+)").getMatch(0);
            if (wait == null) {
                wait = br.getRegex("You can start new download in (\\d+)").getMatch(0);
                if (wait == null) {
                    if (wait == null) {
                        wait = br.getRegex("will be available for free download in (\\d+) min\\.").getMatch(0);
                        if (wait == null) {
                            wait = br.getRegex("<br>Next free download will be available in (\\d+) min").getMatch(0);
                            if (wait == null) {
                                wait = br.getRegex("will be available for download in (\\d+) min").getMatch(0);
                                if (wait == null) {
                                    wait = br.getRegex("<br> Next download will be available in (\\d+) min").getMatch(0);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (wait != null) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, Long.parseLong(wait) * 60 * 1001l);
        }
        if (br.containsHTML("Minimum interval between free downloads is 45 minutes")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, 45 * 60 * 1001l);
        }
        if (br.containsHTML("Respectfully yours Adminstration of Filesmonster\\.com")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED);
        }
        if (br.containsHTML("You need Premium membership to download files")) {
            throw new PluginException(LinkStatus.ERROR_FATAL, JDL.L("plugins.hoster.filesmonstercom.only4premium", PREMIUMONLYUSERTEXT));
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        if (link.getBooleanProperty("PREMIUMONLY", false)) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        }
        /* 2019-10-16: Re-using generated downloadlinks will often fail with plain text response "volume is downloaded already" */
        String dllink = checkDirectLink(link, "directlink");
        if (dllink == null) {
            final String newTemporaryLink = getNewTemporaryLink(link.getStringProperty("mainlink"), link.getStringProperty("origfilename"));
            if (newTemporaryLink == null) {
                /*
                 * This would mean that our previously found free downloadlinks are not usable anymore. Only the main file (usually video
                 * content) is available to download for premiumusers.
                 */
                logger.warning("Failed to find a new temporary link for this link --> Chances are high that it is not downloadable for freeusers anymore --> Free link(s) are offline");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            br.getPage(newTemporaryLink);
            handleErrors(this.br);
            br.setFollowRedirects(true);
            String postThat = br.getRegex(POSTTHATREGEX).getMatch(0);
            if (postThat == null) {
                postThat = new Regex(link.getDownloadURL(), POSTTHATREGEX2).getMatch(0);
            }
            if (postThat == null) {
                logger.warning("The following string could not be found: postThat");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            if (!this.br.getURL().contains("/free/2/")) {
                /* Check if maybe there are no free downloadable urls (anymore). */
                br.postPage(postThat, "");
                if (br.containsHTML("Free download links:")) {
                    link.setProperty("PREMIUMONLY", true);
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
                }
            }
            /* now we have the data page, check for wait time and data id */
            if (this.br.containsHTML("g\\-recaptcha")) {
                final Form continueform = this.br.getFormbyKey("submitbtn");
                if (continueform == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
                continueform.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                this.br.submitForm(continueform);
            } else {
                final Recaptcha rc = new Recaptcha(br, this);
                rc.parse();
                rc.load();
                File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                String c = getCaptchaCode("recaptcha", cf, link);
                rc.setCode(c);
                handleErrors(this.br);
                if (br.containsHTML("(Captcha number error or expired|api\\.recaptcha\\.net)")) {
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                }
            }
            if (!link.getDownloadURL().contains("/free/2/")) {
                String finalPage = br.getRegex("reserve_ticket\\(\\'(/dl/.*?)\\'\\)").getMatch(0);
                if (finalPage == null) {
                    logger.warning("The following string could not be found: finalPage");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                /* request ticket for this file */
                br.getPage("http://filesmonster.com" + finalPage);
                String linkPart = br.getRegex("dlcode\":\"(.*?)\"").getMatch(0);
                String firstPart = new Regex(postThat, "(https?://filesmonster\\.com/dl/.*?/free/)").getMatch(0);
                if (linkPart == null || firstPart == null) {
                    logger.warning("The following string could not be found: linkPart or firstPart");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                String nextLink = firstPart + "2/" + linkPart + "/";
                br.getPage(nextLink);
            }
            String overviewLink = br.getRegex("get_link\\('(/dl/.*?)'\\)").getMatch(0);
            if (overviewLink == null) {
                logger.warning("The following string could not be found: strangeLink");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            overviewLink = "http://filesmonster.com" + overviewLink;
            String regexedwaittime = br.getRegex("id=\\'sec\\'>(\\d+)</span>").getMatch(0);
            br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            br.getHeaders().put("Accept-Charset", "ISO-8859-1,utf-8;q=0.7,*;q=0.7");
            int shortWaittime = 45;
            if (regexedwaittime != null) {
                shortWaittime = Integer.parseInt(regexedwaittime);
            } else {
                logger.warning("Waittime regex doesn't work, using default waittime...");
            }
            sleep(shortWaittime * 1100l, link);
            try {
                br.getPage(overviewLink);
            } catch (final Exception e) {
                logger.log(e);
            }
            dllink = PluginJSonUtils.getJsonValue(this.br, "url");
            if (StringUtils.isEmpty(dllink)) {
                handleErrors(this.br);
                logger.warning("The following string could not be found: dllink");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            link.setProperty("directlink", dllink);
        }
        dl = new jd.plugins.BrowserAdapter().openDownload(br, link, dllink, false, 1);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            logger.warning("The downloadlink doesn't seem to refer to a file, following the connection...");
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.setFilenameFix(true);
        dl.startDownload();
    }

    /** Returns title (NOT the filename) of an item. */
    public static String getLongTitle(final Browser br) {
        String title = br.getRegex("<a href=\"/report\\.php\\?[^<>\"]+\" target=\"_blank\" title=\"Click this to report for ([^<>\"]+)").getMatch(0);
        if (title != null) {
            title = title.trim();
            if (title.isEmpty()) {
                title = null;
            }
        }
        return title;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 1;
    }

    private void login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                final Cookies cookies = account.loadCookies("");
                if (cookies != null) {
                    br.setCookies(cookies);
                    if (!force) {
                        /* Do not verify cookies */
                        return;
                    }
                    logger.info("Attempting cookie login");
                    br.getPage("https://" + this.getHost());
                    if (this.isLoggedIN(br)) {
                        account.saveCookies(br.getCookies(br.getHost()), "");
                        return;
                    } else {
                        br.clearCookies(null);
                    }
                }
                br.setFollowRedirects(true);
                // get login page first, that way we don't post twice in case
                // captcha is already invoked!
                logger.info("Performing full login");
                br.getPage("https://" + this.getHost() + "/login.php");
                final Form login = br.getFormbyProperty("name", "login");
                if (login == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                if (br.containsHTML("(api\\.recaptcha\\.net|google\\.com/recaptcha/api)")) {
                    DownloadLink dummyLink = new DownloadLink(this, "Account", "http://filesmonster.com", "http://filesmonster.com", true);
                    final Recaptcha rc = new Recaptcha(br, this);
                    String id = br.getRegex("\\?k=([A-Za-z0-9%_\\+\\- ]+)\"").getMatch(0);
                    if (id == null) {
                        id = br.getRegex("sitekey=\"([A-Za-z0-9%_\\+\\- ]+)\"").getMatch(0);
                        if (id == null) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                        }
                    }
                    rc.setId(id);
                    rc.load();
                    final File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                    final String c = getCaptchaCode("recaptcha", cf, dummyLink);
                    login.put("recaptcha_challenge_field", rc.getChallenge());
                    login.put("recaptcha_response_field", Encoding.urlEncode(c));
                }
                login.put("user", Encoding.urlEncode(account.getUser()));
                login.put("pass", Encoding.urlEncode(account.getPass()));
                br.submitForm(login);
                /* Make sure that we have the correct language (English) */
                final String lang_cookie = br.getCookie(this.getHost(), "yab_ulanguage");
                if (!"en".equals(lang_cookie)) {
                    br.getPage("/?setlang=en");
                }
                final String lang = System.getProperty("user.language");
                if (!isLoggedIN(br)) {
                    if (br.containsHTML("(?i)Please confirm that you are not a robot") || br.containsHTML("(api\\.recaptcha\\.net|google\\.com/recaptcha/api)")) {
                        throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                    }
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen? Versuche folgendes:\r\n1. Falls dein Passwort Sonderzeichen enthält, ändere es (entferne diese) und versuche es erneut!\r\n2. Gib deine Zugangsdaten per Hand (ohne kopieren/einfügen) ein.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nYou're sure that the username and password you entered are correct? Some hints:\r\n1. If your password contains special characters, change it (remove them) and try again!\r\n2. Type in your username/password by hand without copy & paste.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                if (br.containsHTML(">Your account is suspended")) {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nDein Account ist gesperrt. Bitte wende dich an den filesmonster Support.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nYour account is banned. Please contact the filesmonster support.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                if (br.containsHTML("(?i)Your membership type\\s*:\\s*<[^<>]+>Regular\\s*<[^<>]+>")) {
                    account.setType(AccountType.FREE);
                } else if (br.containsHTML("(?i)Your membership type\\s*:\\s*<[^<>]+>Premium\\s*<[^<>]+>")) {
                    account.setType(AccountType.PREMIUM);
                } else {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nNicht unterstützter Accounttyp!\r\nFalls du denkst diese Meldung sei falsch die Unterstützung dieses Account-Typs sich\r\ndeiner Meinung nach aus irgendeinem Grund lohnt,\r\nkontaktiere uns über das support Forum.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUnsupported account type!\r\nIf you think this message is incorrect or it makes sense to add support for this account type\r\ncontact us via our support forum.", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.saveCookies(br.getCookies(br.getHost()), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    private boolean isLoggedIN(final Browser br) {
        return br.containsHTML("logout=1");
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        // needed because of cached login and we need to have a browser containing html to regex against!
        if (br.getURL() == null || !br.getURL().matches("https?://[^/]*filesmonster\\.com/?")) {
            br.getPage("https://filesmonster.com/");
        }
        ai.setUnlimitedTraffic();
        // current premium
        String expires = br.getRegex("(?i)Valid until\\s*:\\s*<span class='[^<>]+'>([^<>\"]+)</span>").getMatch(0);
        if (expires == null) {
            expires = br.getRegex("(?i)Premium till\\s*<\\s*span\\s+class=\"expire-date\\s*\"\\s*>\\s*([^<>\"]+)\\s*</span>").getMatch(0);
            if (expires == null) {
                // picks up expired accounts.
                expires = br.getRegex("(?i)(?:<span.*?>Expire(?:s|d):|<span class=\"expire-date\\s*\">)\\s*(\\d{2}/\\d{2}/\\d{2})\\s*</span>").getMatch(0);
                if (expires == null) {
                    expires = br.getRegex("(?i)Premium expired:\\s*(\\d{2}/\\d{2}/\\d{2})\\s*</span>").getMatch(0);
                }
            }
        }
        long expireTimeStamp = -1;
        if (expires != null) {
            expireTimeStamp = TimeFormatter.getMilliSeconds(expires, "MM/dd/yy HH:mm", null);
            if (expireTimeStamp <= 0) {
                expireTimeStamp = TimeFormatter.getMilliSeconds(expires, "MM/dd/yy", Locale.ENGLISH);
                if (expireTimeStamp <= 0) {
                    expireTimeStamp = TimeFormatter.getMilliSeconds(expires, "MMM dd, yyyy", Locale.ENGLISH);
                }
            }
        }
        boolean isFree = true;
        if (expireTimeStamp > 0) {
            ai.setValidUntil(expireTimeStamp);
            isFree = ai.isExpired();
        }
        if (!isFree) {
            trafficUpdate(br, ai, account);
            return ai;
        } else {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, "Expired Account", PluginException.VALUE_ID_PREMIUM_DISABLE);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        if (!link.getDownloadURL().contains("download.php?id=")) {
            logger.info(link.getDownloadURL());
            throw new PluginException(LinkStatus.ERROR_FATAL, JDL.L("plugins.hoster.filesmonstercom.only4freeusers", "This file is only available for freeusers"));
        }
        // will wipe cookies for quick fix of core issue
        br.setCookiesExclusive(false);
        login(account, false);
        br.setDebug(true);
        br.getPage(link.getDownloadURL());
        Regex r = br.getRegex("Please try again in <b>(\\d+)</b> hours and <b>(\\d+)</b> minutes.");
        if (r.count() > 0) {
            long sec = 60 * (Long.parseLong(r.getMatch(0)) * 60 + Long.parseLong(r.getMatch(1)));
            if (sec == 0) { // minimum time of 1 sec
                sec = 1;
            }
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, sec * 1000l);
        }
        if (br.containsHTML(TEMPORARYUNAVAILABLE)) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.filesmonstercom.temporaryunavailable", "Download not available at the moment"), 120 * 60 * 1000l);
        } else if (br.containsHTML("You are using your account from an unusual location")) {
            // You are using your account from an unusual location.
            // >Click here to unblock this location with email message<
            /*
             * <form method="POST" action="/subnets/sendemail/" style="display:none" id="send_email_form"> <input type="hidden" name="token"
             * value="YTUxZWQ5N......LjIzNi45NjoxNjA3NDY3MTM2" /> </form>
             */
            throw new PluginException(LinkStatus.ERROR_PREMIUM, "You are using your account from an unusual location!", PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
        } else if (br.containsHTML("\">Today you have already downloaded ") || br.containsHTML("\">You have not enough traffic in your account to download this file") || br.containsHTML("You have reached your download limit")) {
            throw new AccountUnavailableException("Traffic limit reached", 5 * 60 * 1000);
        }
        String premlink = br.getRegex("\"(https?://filesmonster\\.com/get/.*?)\"").getMatch(0);
        if (premlink == null) {
            if (br.containsHTML("You have reached max \\d+ downloads per")) {
                throw new AccountUnavailableException("Daily max downloads limit reached", 5 * 60 * 1000);
            }
            logger.warning("Failed to find premium downloadlink");
            throw new PluginException(LinkStatus.ERROR_FATAL, "Failed to find premium downloadlink. Check if you can download this file via browser, if so, please report this as a bug.");
        }
        br.getPage(premlink);
        if (br.containsHTML("<div id=\"error\">Today you have already downloaded")) {
            try {
                trafficUpdate(br.cloneBrowser(), null, account);
            } catch (IOException e) {
            }
            throw new PluginException(LinkStatus.ERROR_RETRY);
        }
        String ajaxurl = br.getRegex("get_link\\(\"(.*?)\"\\)").getMatch(0);
        Browser ajax = br.cloneBrowser();
        ajax.getPage(ajaxurl);
        String dllink = ajax.getRegex("url\":\"(https?:.*?)\"").getMatch(0);
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dllink = dllink.replaceAll("\\\\/", "/");
        /* max chunks to 1 , because each chunk gets calculated full size */
        dl = new jd.plugins.BrowserAdapter().openDownload(br, link, dllink, true, 1);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            logger.warning("The downloadlink doesn't seem to refer to a file, following the connection...");
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    private AccountInfo trafficUpdate(final Browser br, final AccountInfo importedAi, final Account account) throws Exception {
        AccountInfo ai;
        if (importedAi == null) {
            ai = account.getAccountInfo();
            if (ai == null) {
                ai = new AccountInfo();
            }
        } else {
            ai = importedAi;
        }
        // care of filesmonster
        br.getPage("/today_downloads/");
        String[] dailyQuota = br.getRegex("(?i)Today you have already downloaded <span[^>]+>(\\d+(\\.\\d+)? ?(KB|MB|GB)) </span>\\.\\s*Daily download limit\\s*<span[^>]+>(\\d+(\\.\\d+)? ?(KB|MB|GB))").getRow(0);
        if (dailyQuota != null) {
            long usedQuota = SizeFormatter.getSize(dailyQuota[0]);
            long maxQuota = SizeFormatter.getSize(dailyQuota[3]);
            long dataLeft = maxQuota - usedQuota;
            if (dataLeft <= 0) {
                dataLeft = 0;
            }
            ai.setTrafficLeft(dataLeft);
            ai.setTrafficMax(maxQuota);
        } else {
            /* Traffic left = unknown */
            ai.setUnlimitedTraffic();
        }
        if (importedAi == null) {
            account.setAccountInfo(ai);
        }
        return ai;
    }

    private String[] getTempLinks() throws Exception {
        String[] decryptedStuff = null;
        final String postThat = br.getRegex("\"[^\"]*(/dl/.*?)\"").getMatch(0);
        if (postThat != null) {
            br.postPage("http://filesmonster.com" + postThat, "");
            final String findOtherLinks = br.getRegex("\\'(/dl/rft/.*?)\\'").getMatch(0);
            if (findOtherLinks != null) {
                br.getPage("http://filesmonster.com" + findOtherLinks);
                decryptedStuff = br.getRegex("\\{(.*?)\\}").getColumn(0);
            }
        }
        return decryptedStuff;
    }

    /**
     * Check if a stored directlink exists under property 'property' and if so, check if it is still valid (leads to a downloadable content
     * [NOT html]).
     */
    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                /* 2017-03-24: Do NOT use Head-Requests here! */
                con = br2.openGetConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
            } catch (final Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return dllink;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public String getDescription() {
        return "JDownloader's Filesmonster.com Plugin helps downloading files from Filesmonster.com.";
    }

    private void setConfigElements() {
        final StringBuilder sbinfo = new StringBuilder();
        sbinfo.append("Filesmonster provides a link which can only be downloaded by premium users\r\n");
        sbinfo.append("and multiple links which can only be downloaded by free users.\r\n");
        sbinfo.append("Whenever you add a filesmonster link, JDownloader will show both links in the linkgrabber via default.\r\n");
        sbinfo.append("The setting below will make this behaviour more intelligent.\r\n");
        sbinfo.append("Note that if a complete link is only available to premium members, it's premium-only\r\n");
        sbinfo.append("link will always show up in the linkgrabber!\r\n");
        sbinfo.append("\r\n");
        sbinfo.append("NOTE: If you enable this feature and add links before setting up your filesmonster premium\r\n");
        sbinfo.append("account in JD you will have to add these links again after adding the account to get the premium links!\r\n");
        sbinfo.append("Do NOT enable this setting if you're not familiar with JDownloader!\r\n");
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, sbinfo.toString()));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), FilesMonsterCom.ADDLINKSACCOUNTDEPENDANT, JDL.L("plugins.hoster.filesmonstercom.AddLinksDependingOnAvailableAccounts", "Add only premium-only links whenever a premium account is available\r\n and add only free-only-links whenever no premium account is available?\r\nDisabled = Always add all links!")).setDefaultValue(false));
    }

    @Override
    public boolean hasAutoCaptcha() {
        return false;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
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