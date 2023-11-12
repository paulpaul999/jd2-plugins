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

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;

import jd.PluginWrapper;
import jd.config.Property;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 47664 $", interfaceVersion = 2, names = { "whatboyswant.com" }, urls = { "https://(?:www\\.)?whatboyswant\\.com/(?:babes|movies|cars)/show/\\d+|https?://(?:www\\.)?whatboyswant\\.com/videos/[a-z0-9\\-]+/[a-z0-9\\-]+\\-\\d+" })
public class WhatBoysWantCom extends PluginForHost {
    public WhatBoysWantCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://whatboyswant.com/register");
    }

    @Override
    public String getAGBLink() {
        return "https://whatboyswant.com/pages/display/termsofuse";
    }

    /* Connection stuff */
    private static final boolean FREE_RESUME                  = false;
    private static final int     FREE_MAXCHUNKS               = 1;
    private static final int     FREE_MAXDOWNLOADS            = -1;
    private static final boolean ACCOUNT_FREE_RESUME          = true;
    private static final int     ACCOUNT_FREE_MAXCHUNKS       = 0;
    private static final int     ACCOUNT_FREE_MAXDOWNLOADS    = -1;
    private static final boolean ACCOUNT_PREMIUM_RESUME       = true;
    private static final int     ACCOUNT_PREMIUM_MAXCHUNKS    = 0;
    private static final int     ACCOUNT_PREMIUM_MAXDOWNLOADS = -1;
    private static final String  TYPE_BABE                    = "h.+/babes/show/\\d+";
    private static final String  TYPE_CAR                     = ".+/car/show/\\d+";
    private static final String  TYPE_MOVIE                   = ".+/(?:movies/show/\\d+|videos/.+)";
    private static final String  TYPE_VIDEOS                  = "https?://[^/]+/videos/[^/]+/([a-z0-9\\-]+)-(\\d+)";
    private static final String  default_EXT_video            = ".mp4";
    private static final String  default_EXT_photo            = ".jpg";

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        final String fid = getFID(link);
        final String type = getTYPE(link);
        String filename = null;
        this.setBrowserExclusive();
        final Account aa = AccountController.getInstance().getValidAccount(this);
        if (aa != null) {
            this.login(aa, false);
            br.getPage("https://whatboyswant.com/" + type + "/properties/" + fid + "/");
            if (br.getHttpConnection().getResponseCode() == 404 || br.getURL().contains("/error404")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            String filesize = br.getRegex("<th>Filesize:</th>[\t\n\r ]+<td>([^<>\"]*?)</td>").getMatch(0);
            filename = br.getRegex("<th>Filename:</th>[\t\n\r ]+<td>([^<>\"]*?)</td>").getMatch(0);
            if (filename == null || filesize == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            filename = Encoding.htmlDecode(filename).trim();
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        } else {
            br.getPage(link.getDownloadURL());
            if (br.getHttpConnection().getResponseCode() == 404 || br.getURL().contains("/error404")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            filename = br.getRegex("<title>([^<>\"]*?)</title>").getMatch(0);
            if (filename == null) {
                filename = fid;
            }
            if (link.getPluginPatternMatcher().matches(TYPE_VIDEOS)) {
                filename = this.getURLTitle(link).replace("-", " ");
                filename += default_EXT_video;
            } else if (link.getDownloadURL().matches(TYPE_MOVIE)) {
                filename += default_EXT_video;
            } else {
                filename += default_EXT_photo;
            }
        }
        link.setFinalFileName(Encoding.htmlDecode(filename.trim()));
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        doFree(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void doFree(final DownloadLink link, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        final String fid = getFID(link);
        String dllink = checkDirectLink(link, directlinkproperty);
        if (dllink == null) {
            dllink = br.getRegex("\"(/picture/(?:babe|car)/" + fid + "/[^<>\"]*?)\"").getMatch(0);
            if (dllink == null) {
                /* 2020-12-09 */
                dllink = br.getRegex("\"(/stream/videos[^\"]+)").getMatch(0);
            }
            if (dllink == null) {
                if (link.getPluginPatternMatcher().matches(TYPE_MOVIE)) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            dllink = "https://whatboyswant.com" + dllink;
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resumable, maxchunks);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (link.getFinalFileName() == null && dl.getConnection().isContentDisposition()) {
            link.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(dl.getConnection())));
        }
        link.setProperty(directlinkproperty, dllink);
        dl.startDownload();
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                br.setFollowRedirects(true);
                con = br2.openGetConnection(dllink);
                if (looksLikeDownloadableContent(con)) {
                    return dllink;
                } else {
                    throw new IOException();
                }
            } catch (final Exception e) {
                logger.log(e);
                downloadLink.setProperty(property, Property.NULL);
                return null;
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return dllink;
    }

    private String getFID(final DownloadLink dl) {
        final String fid;
        if (dl.getDownloadURL().contains("/videos/")) {
            fid = new Regex(dl.getDownloadURL(), "(\\d+)$").getMatch(0);
        } else {
            fid = new Regex(dl.getDownloadURL(), "/show/(\\d+)").getMatch(0);
        }
        return fid;
    }

    private String getURLTitle(final DownloadLink dl) {
        if (dl.getPluginPatternMatcher().matches(TYPE_VIDEOS)) {
            return new Regex(dl.getPluginPatternMatcher(), TYPE_VIDEOS).getMatch(0);
        } else {
            return null;
        }
    }

    private String getTYPE(final DownloadLink dl) {
        return new Regex(dl.getDownloadURL(), "whatboyswant.com/([A-Za-z0-9]+)/show/").getMatch(0);
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    private static final String MAINPAGE = "http://whatboyswant.com";
    private static Object       LOCK     = new Object();

    @SuppressWarnings("unchecked")
    private void login(final Account account, final boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                // Load cookies
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
                            br.setCookie(MAINPAGE, key, value);
                        }
                        return;
                    }
                }
                br.setFollowRedirects(false);
                br.postPage("https://whatboyswant.com/login", "data%5BUser%5D%5Bremember%5D=0&data%5BUser%5D%5Bremember%5D=1&_method=POST&data%5BUser%5D%5Busername%5D=" + Encoding.urlEncode(account.getUser()) + "&data%5BUser%5D%5Bpassword%5D=" + Encoding.urlEncode(account.getPass()));
                if (br.getCookie(MAINPAGE, "UsersCookie[RememberMe]") == null) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                // Save cookies
                final HashMap<String, String> cookies = new HashMap<String, String>();
                final Cookies add = br.getCookies(MAINPAGE);
                for (final Cookie c : add.getCookies()) {
                    cookies.put(c.getKey(), c.getValue());
                }
                account.setProperty("name", Encoding.urlEncode(account.getUser()));
                account.setProperty("pass", Encoding.urlEncode(account.getPass()));
                account.setProperty("cookies", cookies);
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.setProperty("cookies", Property.NULL);
                }
                throw e;
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        try {
            login(account, true);
        } catch (PluginException e) {
            account.setValid(false);
            throw e;
        }
        br.getPage("/credits");
        ai.setUnlimitedTraffic();
        final String expire = br.getRegex("(?i)Your premium membership will expire on:\\s*<span>([^<>\"]*?)</span>").getMatch(0);
        if (expire == null) {
            account.setType(AccountType.FREE);
            /* free accounts can still have captcha */
            account.setMaxSimultanDownloads(ACCOUNT_FREE_MAXDOWNLOADS);
            account.setConcurrentUsePossible(false);
            /* Credits has noi specified amount of bytes but we know that we got no traffic at all if it's zero! */
            if (br.containsHTML("You have 0 credits\\.")) {
                ai.setTrafficLeft(0);
            }
        } else {
            ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "HH:mm dd-MM-yyyy", Locale.ENGLISH));
            account.setType(AccountType.PREMIUM);
            account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
            account.setConcurrentUsePossible(true);
        }
        return ai;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        String dllink = null;
        String directlinkproperty = null;
        boolean resume;
        int maxchunks;
        final String fid = getFID(link);
        final String type = getTYPE(link);
        requestFileInformation(link);
        login(account, false);
        br.setFollowRedirects(false);
        /* Free accounts have credits (traffic) - premium accounts have unlimited credits. */
        if (account.getBooleanProperty("free", false)) {
            resume = ACCOUNT_FREE_RESUME;
            maxchunks = ACCOUNT_FREE_MAXCHUNKS;
            directlinkproperty = "account_free_directlink";
            dllink = this.checkDirectLink(link, directlinkproperty);
            if (dllink == null) {
                br.getPage(link.getDownloadURL());
                if (br.containsHTML(">Not enough credits, you need")) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nTrafficlimit erreicht!", PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nTraffic limit reached!", PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
                    }
                }
            }
        } else {
            resume = ACCOUNT_PREMIUM_RESUME;
            maxchunks = ACCOUNT_PREMIUM_MAXCHUNKS;
            directlinkproperty = "premium_directlink";
            dllink = this.checkDirectLink(link, directlinkproperty);
        }
        if (dllink == null) {
            if (link.getDownloadURL().matches(TYPE_MOVIE)) {
                /* We might have already accessed the link above in case the user is using a free account. */
                if (br.getURL() != null && !br.getURL().equals(link.getDownloadURL())) {
                    br.getPage(link.getDownloadURL());
                }
                /* Grab the highest quality possible */
                dllink = br.getRegex("\"(/movie/movie/" + fid + "/mp4_full/[^<>\"]*?)\"").getMatch(0);
                if (dllink == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            } else {
                dllink = "https://whatboyswant.com/" + type + "/download/" + fid + "/category";
            }
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resume, maxchunks);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (link.getFinalFileName() == null && dl.getConnection().isContentDisposition()) {
            link.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(dl.getConnection())));
        }
        link.setProperty(directlinkproperty, dllink);
        dl.startDownload();
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