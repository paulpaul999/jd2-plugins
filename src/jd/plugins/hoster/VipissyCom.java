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

import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookies;
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

@HostPlugin(revision = "$Revision: 47818 $", interfaceVersion = 3, names = { "vipissy.com" }, urls = { "https?://(?:www\\.)?members\\.vipissy\\.com/(?:updates/)video\\-[^/]+/" })
public class VipissyCom extends PluginForHost {
    public VipissyCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://members.vipissy.com/");
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    @Override
    public String getAGBLink() {
        return "https://members.vipissy.com/misc/privacy-policy/";
    }

    /* Connection stuff */
    private static final boolean FREE_RESUME                  = false;
    private static final int     FREE_MAXCHUNKS               = 1;
    private static final int     FREE_MAXDOWNLOADS            = -1;
    private static final boolean ACCOUNT_PREMIUM_RESUME       = true;
    private static final int     ACCOUNT_PREMIUM_MAXCHUNKS    = 0;
    private static final int     ACCOUNT_PREMIUM_MAXDOWNLOADS = -1;
    private static final String  HTML_LOGGED_IN               = "class=\"members_menu\"";
    private boolean              download_not_yet_possible    = false;
    private String               dllink                       = null;

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        dllink = null;
        download_not_yet_possible = false;
        this.setBrowserExclusive();
        final Account aa = AccountController.getInstance().getValidAccount(this);
        if (aa == null) {
            /* Account needed! */
            return AvailableStatus.UNCHECKABLE;
        }
        this.login(aa);
        this.br.setFollowRedirects(true);
        br.getPage(link.getDownloadURL());
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filename = br.getRegex("class=\"update_title\">[\t\n\r ]+<h1>([^<>\"]*?)</h1>").getMatch(0);
        if (filename == null) {
            filename = new Regex(link.getDownloadURL(), "/updates/video\\-(.+)/$").getMatch(0);
        }
        link.setFinalFileName(Encoding.htmlDecode(filename.trim()) + ".mp4");
        final String[] dltables = this.br.getRegex("<ul class=\"con_video_list\">(.*?)</ul>").getColumn(0);
        if (dltables == null || dltables.length == 0) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String[] quality_info = new Regex(dltables[0], "<a href=\"(https?://content\\.vipissy\\.com/downloads/.*?</span></a>)").getColumn(0);
        if (quality_info == null || quality_info.length == 0) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String best_quality_info = quality_info[quality_info.length - 1];
        dllink = new Regex(best_quality_info, "(http[^<>\"]*?)\"").getMatch(0);
        String filesize = new Regex(best_quality_info, "(\\d+(?: ?\\d+)?(?:\\.\\d+)? ?(KB|MB|GB))").getMatch(0);
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (filesize != null) {
            filesize = filesize.replace(" ", "");
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        requestFileInformation(link);
        doFree(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void doFree(final DownloadLink downloadLink, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        /* Premiumonly */
        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    private static final String MAINPAGE = "http://members.vipissy.com";

    private void login(final Account account) throws Exception {
        synchronized (account) {
            try {
                // Load cookies
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null) {
                    this.br.setCookies(this.getHost(), cookies);
                    br.getPage(MAINPAGE);
                    if (this.br.containsHTML(HTML_LOGGED_IN)) {
                        account.saveCookies(this.br.getCookies(this.getHost()), "");
                        return;
                    }
                    br.clearAll();
                }
                br.setFollowRedirects(true);
                br.getPage(MAINPAGE);
                {
                    // required for the pcah cookie
                    final Browser br2 = br.cloneBrowser();
                    br2.openGetConnection("/img.cptcha");
                }
                final String postData = "rmb=y&rlm=Members+Only&for=http%253a%252f%252fmembers%252evipissy%252ecom%252f&uid=" + Encoding.urlEncode(account.getUser()) + "&pwd=" + Encoding.urlEncode(account.getPass());
                br.postPage("http://members.vipissy.com/auth.form", postData);
                this.br.getPage("http://members.vipissy.com/profile/");
                if (!this.br.containsHTML(HTML_LOGGED_IN)) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.saveCookies(this.br.getCookies(this.getHost()), "");
            } catch (final PluginException e) {
                account.clearCookies("");
                throw e;
            }
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account);
        ai.setUnlimitedTraffic();
        account.setType(AccountType.PREMIUM);
        account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
        account.setConcurrentUsePossible(true);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        if (download_not_yet_possible) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Download not yet possible - content is not yet released!", 3 * 60 * 60 * 1000l);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, ACCOUNT_PREMIUM_RESUME, ACCOUNT_PREMIUM_MAXCHUNKS);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            logger.warning("The final dllink seems not to be a file!");
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