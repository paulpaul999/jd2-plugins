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
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;

import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.captcha.v2.challenge.recaptcha.v1.Recaptcha;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.parser.html.HTMLParser;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.SiteType.SiteTemplate;
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision: 48354 $", interfaceVersion = 2, names = { "grabitshare.com" }, urls = { "http://(www\\.)?grabitshare\\.com/((\\?d|download\\.php\\?id)=[A-Z0-9]+|((en|ru|fr|es)/)?file/[0-9]+/)" })
public class GrabItShareCom extends PluginForHost {
    private static final String COOKIE_HOST      = "http://grabitshare.com";
    private static final String IPBLOCKED        = "(You have got max allowed bandwidth size per hour|You have got max allowed download sessions from the same IP|\">Dostigli ste download limit\\. Pričekajte 1h za nastavak)";
    private static final String RECAPTCHATEXT    = "(api\\.recaptcha\\.net|google\\.com/recaptcha/api/)";
    private static final String CHEAPCAPTCHATEXT = "captcha\\.php";

    public GrabItShareCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(COOKIE_HOST + "/en/register.php");
    }

    private String findLink() throws Exception {
        String finalLink = br.getRegex("(http://.{5,30}getfile\\.php\\?id=\\d+\\&a=[a-z0-9]+\\&t=[a-z0-9]+.*?)(\\'|\")").getMatch(0);
        if (finalLink == null) {
            String[] sitelinks = HTMLParser.getHttpLinks(br.toString(), null);
            if (sitelinks == null || sitelinks.length == 0) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            for (String alink : sitelinks) {
                alink = Encoding.htmlDecode(alink);
                if (alink.contains("access_key=") || alink.contains("getfile.php?")) {
                    finalLink = alink;
                    break;
                }
            }
        }
        return finalLink;
    }

    // MhfScriptBasic 1.2, added new filesize-/name regexes & limit-reached text
    @Override
    public String getAGBLink() {
        return COOKIE_HOST + "/rules.php";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.setCustomCharset("utf-8");
        br.setCookie(COOKIE_HOST, "mfh_mylang", "en");
        br.setCookie(COOKIE_HOST, "yab_mylang", "en");
        br.getPage(link.getDownloadURL());
        String newlink = br.getRegex("<p>The document has moved <a href=\"(.*?)\">here</a>\\.</p>").getMatch(0);
        if (newlink != null) {
            logger.info("This link has moved, trying to find and set the new link...");
            newlink = newlink.replaceAll("(\\&amp;|setlang=en)", "");
            link.setUrlDownload(newlink);
            br.getPage(newlink);
        }
        checkOffline();
        String filename = br.getRegex("title=\"Click this to report (.*?)\"").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("<p align=\"center\"><b><font size=\"4\">(.*?)</font><font").getMatch(0);
        }
        String filesize = br.getRegex("<b>(File size|Filesize):</b></td>[\r\t\n ]+<td align=([\r\t\n ]+|(\")?left(\")?)>(.*?)</td>").getMatch(4);
        if (filesize == null) {
            filesize = br.getRegex("<b>\\&#4324;\\&#4304;\\&#4312;\\&#4314;\\&#4312;\\&#4321; \\&#4310;\\&#4317;\\&#4315;\\&#4304;:</b></td>[\t\r\n ]+<td align=left>(.*?)</td>").getMatch(0);
            if (filesize == null) {
                filesize = br.getRegex("</font><font size=\"5\">\\&nbsp;</font>(.*?)\\&nbsp;\\&nbsp;").getMatch(0);
            }
        }
        if (filename == null || filename.matches("")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        link.setFinalFileName(filename.trim());
        if (filesize != null) {
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        return AvailableStatus.TRUE;
    }

    private void checkOffline() throws PluginException {
        if (br.containsHTML("(?i)(Your requested file is not found|No file found)")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        requestFileInformation(link);
        doFree(link);
    }

    private void doFree(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        requestFileInformation(link);
        if (br.containsHTML(">Pristup ovom sadržaju imaju samo")) {
            throw new PluginException(LinkStatus.ERROR_FATAL, JDL.L("plugins.hoster.grabitsharecom.only4premium", "Download only possible for premium users"));
        }
        if (br.containsHTML("value=\"Free Users\"")) {
            br.postPage(link.getDownloadURL(), "Free=Free+Users");
        } else if (br.getFormbyProperty("name", "entryform1") != null) {
            br.submitForm(br.getFormbyProperty("name", "entryform1"));
        }
        String passCode = null;
        Form captchaform = br.getFormbyProperty("name", "myform");
        if (captchaform == null) {
            captchaform = br.getFormbyProperty("name", "validateform");
            if (captchaform == null) {
                captchaform = br.getFormbyProperty("name", "valideform");
                if (captchaform == null) {
                    captchaform = br.getFormbyProperty("name", "verifyform");
                }
            }
        }
        if (br.containsHTML("class=textinput name=downloadpw") || br.containsHTML(RECAPTCHATEXT) || br.containsHTML(CHEAPCAPTCHATEXT)) {
            if (captchaform == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            for (int i = 0; i <= 3; i++) {
                if (br.containsHTML(CHEAPCAPTCHATEXT)) {
                    logger.info("Found normal captcha");
                    String captchaurl = COOKIE_HOST + "/captcha.php";
                    String code = getCaptchaCode(captchaurl, link);
                    captchaform.put("captchacode", code);
                } else if (br.containsHTML(RECAPTCHATEXT)) {
                    logger.info("Found reCaptcha");
                    final Recaptcha rc = new Recaptcha(br, this);
                    rc.parse();
                    rc.load();
                    File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                    captchaform.put("recaptcha_challenge_field", rc.getChallenge());
                    captchaform.put("recaptcha_response_field", getCaptchaCode("recaptcha", cf, link));
                }
                if (br.containsHTML("class=textinput name=downloadpw")) {
                    if (link.getDownloadPassword() == null) {
                        passCode = getUserInput("Password?", link);
                    } else {
                        /* gespeicherten PassCode holen */
                        passCode = link.getDownloadPassword();
                    }
                    captchaform.put("downloadpw", passCode);
                }
                br.submitForm(captchaform);
                if (br.containsHTML("Password Error")) {
                    logger.warning("Wrong password!");
                    link.setDownloadPassword(null);
                    continue;
                }
                if (br.containsHTML(IPBLOCKED)) {
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 10 * 60 * 1001l);
                }
                if (br.containsHTML("Captcha number error") || br.containsHTML(RECAPTCHATEXT) || br.containsHTML(CHEAPCAPTCHATEXT)) {
                    logger.warning("Wrong captcha or wrong password!");
                    link.setDownloadPassword(null);
                    continue;
                }
                break;
            }
        }
        if (br.containsHTML(IPBLOCKED)) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 10 * 60 * 1001l);
        }
        if (br.containsHTML("Password Error")) {
            logger.warning("Wrong password!");
            link.setDownloadPassword(null);
            throw new PluginException(LinkStatus.ERROR_RETRY);
        }
        if (br.containsHTML("Captcha number error") || br.containsHTML(RECAPTCHATEXT) || br.containsHTML(CHEAPCAPTCHATEXT)) {
            logger.warning("Wrong captcha or wrong password!");
            link.setDownloadPassword(null);
            throw new PluginException(LinkStatus.ERROR_CAPTCHA);
        }
        if (passCode != null) {
            link.setDownloadPassword(passCode);
        }
        String finalLink = findLink();
        if (finalLink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, finalLink, false, 1);
        checkErrorsAfterDownloadAttempt();
        dl.startDownload();
    }

    private void checkErrorsAfterDownloadAttempt() throws PluginException, IOException {
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            final UrlQuery query = UrlQuery.parse(br.getURL());
            final String errorStr = query.get("code");
            if (errorStr != null) {
                if (errorStr.equalsIgnoreCase("DL_FileNotFound")) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else {
                    throw new PluginException(LinkStatus.ERROR_FATAL, errorStr);
                }
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                /** Load cookies */
                br.setCookiesExclusive(true);
                final Object ret = account.getProperty("cookies", null);
                boolean acmatch = Encoding.urlEncode(account.getUser()).equals(account.getStringProperty("name", Encoding.urlEncode(account.getUser())));
                if (acmatch) {
                    acmatch = Encoding.urlEncode(account.getPass()).equals(account.getStringProperty("pass", Encoding.urlEncode(account.getPass())));
                }
                if (acmatch && ret != null && ret instanceof Map<?, ?> && !force) {
                    final Map<String, String> cookies = (Map<String, String>) ret;
                    if (account.isValid()) {
                        for (final Map.Entry<String, String> cookieEntry : cookies.entrySet()) {
                            final String key = cookieEntry.getKey();
                            final String value = cookieEntry.getValue();
                            this.br.setCookie(COOKIE_HOST, key, value);
                        }
                        return;
                    }
                }
                br.setFollowRedirects(true);
                br.setCookie(COOKIE_HOST, "mfh_mylang", "en");
                br.setCookie(COOKIE_HOST, "yab_mylang", "en");
                br.getPage(COOKIE_HOST + "/en/login.php");
                final String lang = System.getProperty("user.language");
                Form form = br.getFormbyProperty("name", "lOGIN");
                if (form == null) {
                    form = br.getForm(0);
                }
                if (form == null) {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin defekt, bitte den JDownloader Support kontaktieren!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin broken, please contact the JDownloader Support!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                form.put("user", Encoding.urlEncode(account.getUser()));
                form.put("pass", Encoding.urlEncode(account.getPass()));
                // If the referer is still in the form (and if it is a valid
                // downloadlink) the download starts directly after logging in
                // so we
                // MUST remove it!
                form.remove("refer_url");
                form.put("autologin", "0");
                br.submitForm(form);
                br.getPage(COOKIE_HOST + "/members.php");
                final String premium = br.getRegex("return overlay\\(this, \\'package_details\\',\\'width=\\d+px,height=\\d+px,center=1,resize=1,scrolling=1\\'\\)\">(Premium)</a>").getMatch(0);
                if (br.getCookie(COOKIE_HOST, "mfh_passhash") == null || "0".equals(br.getCookie(COOKIE_HOST, "mfh_uid"))) {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                if (premium == null) {
                    account.setType(AccountType.FREE);
                } else {
                    account.setType(AccountType.PREMIUM);
                }
                /** Save cookies */
                final HashMap<String, String> cookies = new HashMap<String, String>();
                final Cookies add = this.br.getCookies(COOKIE_HOST);
                for (final Cookie c : add.getCookies()) {
                    cookies.put(c.getKey(), c.getValue());
                }
                account.setProperty("name", Encoding.urlEncode(account.getUser()));
                account.setProperty("pass", Encoding.urlEncode(account.getPass()));
                account.setProperty("cookies", cookies);
            } catch (final PluginException e) {
                account.setProperty("cookies", Property.NULL);
                throw e;
            }
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        login(account, true);
        br.getPage(COOKIE_HOST + "/en/members.php");
        String expired = getData("Aktivno");
        if (expired != null) {
            expired = expired.trim();
            if (expired.equalsIgnoreCase("No")) {
                ai.setExpired(false);
            } else if (expired.equalsIgnoreCase("Yes")) {
                ai.setExpired(true);
            }
        }
        String expires = getData("Vaše članstvo istiće");
        if (expires != null) {
            expires = expires.trim();
            if (!expires.equals("Never") && !expires.equals("Nikada")) {
                String[] e = expires.split("/");
                Calendar cal = new GregorianCalendar(Integer.parseInt("20" + e[2]), Integer.parseInt(e[1]) - 1, Integer.parseInt(e[0]));
                ai.setValidUntil(cal.getTimeInMillis());
            }
        }
        final String create = getData("Datum registracije");
        if (create != null) {
            String[] c = create.split("/");
            Calendar cal = new GregorianCalendar(Integer.parseInt("20" + c[2]), Integer.parseInt(c[1]) - 1, Integer.parseInt(c[0]));
            ai.setCreateTime(cal.getTimeInMillis());
        }
        return ai;
    }

    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        login(account, true);
        br.setFollowRedirects(false);
        br.setCookie(COOKIE_HOST, "mfh_mylang", "en");
        br.getPage(link.getDownloadURL());
        checkOffline();
        if (account.getType() == AccountType.FREE) {
            doFree(link);
        } else {
            String finalLink = null;
            if (br.getRedirectLocation() != null && (br.getRedirectLocation().contains("access_key=") || br.getRedirectLocation().contains("getfile.php"))) {
                finalLink = br.getRedirectLocation();
            } else {
                if (br.containsHTML("You have got max allowed download sessions from the same IP")) {
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 10 * 60 * 1001l);
                }
                String passCode = null;
                if (br.containsHTML("downloadpw")) {
                    logger.info("The file you're trying to download is password protected...");
                    link.setPasswordProtected(true);
                    Form pwform = br.getFormbyProperty("name", "myform");
                    if (pwform == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    if (link.getDownloadPassword() == null) {
                        passCode = getUserInput("Password?", link);
                    } else {
                        /* gespeicherten PassCode holen */
                        passCode = link.getDownloadPassword();
                    }
                    pwform.put("downloadpw", Encoding.urlEncode(passCode));
                    br.submitForm(pwform);
                }
                if (br.containsHTML("You have got max allowed download sessions from the same IP")) {
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 10 * 60 * 1001l);
                }
                if (br.containsHTML("Password Error")) {
                    link.setDownloadPassword(null);
                    throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password");
                }
                if (passCode != null) {
                    link.setDownloadPassword(passCode);
                }
                finalLink = findLink(br);
            }
            if (finalLink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, finalLink, true, -5);
            checkErrorsAfterDownloadAttempt();
            dl.startDownload();
        }
    }

    private String getData(final String data) {
        String result = br.getRegex(">" + data + "</strong></li>[\t\n\r ]+<li class=\"col\\-w50\">([^<>\"]*?)</li>").getMatch(0);
        if (result == null) {
            result = br.getRegex("<b>" + data + "</b></td>[\t\n\r ]+<td align=left( width=\\d+px)?>([^<>\"]*?)</td>").getMatch(1);
        }
        return result;
    }

    private String findLink(final Browser br) throws Exception {
        return br.getRegex("(http://[a-z0-9\\-\\.]{5,30}/getfile\\.php\\?id=\\d+[^<>\"\\']*?)(\"|\\')").getMatch(0);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public boolean hasAutoCaptcha() {
        return true;
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

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.MhfScriptBasic;
    }
}