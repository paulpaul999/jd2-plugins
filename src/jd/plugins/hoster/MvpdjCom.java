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

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision: 47483 $", interfaceVersion = 3, names = { "mvpdj.com" }, urls = { "https?://(?:www\\.)?mvpdj\\.com/song/player/\\d+" })
public class MvpdjCom extends PluginForHost {
    public MvpdjCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://www.mvpdj.com/user/register");
    }

    private String  dllink       = null;
    private boolean serverissues = false;
    private String  fid          = null;

    @Override
    public String getAGBLink() {
        return "http://mvpdj.com/about";
    }

    @Override
    public String rewriteHost(String host) {
        if ("sosodo.com".equals(getHost())) {
            if (host == null || "sosodo.com".equals(host)) {
                return "mvpdj.com";
            }
        }
        return super.rewriteHost(host);
    }

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        dllink = null;
        serverissues = false;
        fid = new Regex(link.getDownloadURL(), "(\\d+)$").getMatch(0);
        br = new Browser();
        /* 2017-08-03: Website randomly returns 500 with regular html content --> Allow response 500 */
        br.setAllowedResponseCodes(new int[] { 500 });
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        /* Very old links --> Offline */
        if (link.getDownloadURL().matches("https?://(?:www\\.)?(?:sosodo|mvpdj)\\.com/home/music/track/\\d+/\\d+")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        boolean loggedIN = false;
        final Account aa = AccountController.getInstance().getValidAccount(this);
        if (aa != null) {
            this.login(aa, false);
            loggedIN = true;
        }
        String filename_html = null;
        String filename = null;
        if (loggedIN) {
            /* Download via download button --> Higher quality */
            this.br.postPage("https://www.mvpdj.com/song/download", "id=" + fid);
            filename_html = this.br.getRegex("class=\"dt_tc_big\"[^<>]*?>([^<>]+)<").getMatch(0);
            if (this.br.containsHTML(">账户余额不足，请先充值")) {
                /*
                 * Hmm something like "No traffic left" --> But let's not temp-disable the account - let's simply download the stream then!
                 */
                logger.info("Account traffic exhausted or track not downloadable!");
            } else {
                /* Number at the end seems to be a server/mirror number. Possibilities: 1,2 */
                logger.info("Track should be downloadable fine via account");
                dllink = "https://www.mvpdj.com/song/purchase/" + fid + "/2";
            }
        }
        if (dllink == null) {
            if (loggedIN) {
                logger.info("Official track download via account failed --> Trying stream download");
            } else {
                logger.info("Trying stream download");
            }
            br.getPage(link.getDownloadURL());
            if (!br.containsHTML("<title>") || this.br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            filename = PluginJSonUtils.getJsonValue(br, "name");
            dllink = PluginJSonUtils.getJsonValue(br, "url");
            if (filename == null || dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dllink = Encoding.htmlDecode(dllink);
            filename = filename.trim();
            final String ext = getFileNameExtensionFromString(dllink, ".mp3");
            if (!filename.endsWith(ext)) {
                link.setFinalFileName(Encoding.htmlDecode(filename) + ext);
            } else {
                link.setFinalFileName(Encoding.htmlDecode(filename));
            }
        }
        final Browser br2 = br.cloneBrowser();
        // In case the link redirects to the finallink
        br2.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            con = br2.openHeadConnection(dllink);
            if (this.looksLikeDownloadableContent(con)) {
                if (filename == null) {
                    /* Especially for official account-downloads, server-filenames might be crippled! */
                    final String filename_server = getFileNameFromHeader(con);
                    if (filename_server != null) {
                        if (filename_server != null && filename_html != null && filename_html.length() > filename_server.length()) {
                            link.setFinalFileName(Encoding.htmlDecode(filename_html).trim() + ".mp3");
                        } else {
                            link.setFinalFileName(filename_server);
                        }
                    }
                }
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
            } else {
                serverissues = true;
            }
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception {
        requestFileInformation(downloadLink);
        doFree(downloadLink);
    }

    public void doFree(final DownloadLink link) throws Exception {
        if (serverissues) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 5 * 60 * 1000l);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        /* No need to log in as we're already logged in */
        doFree(link);
    }

    @SuppressWarnings("deprecation")
    private void login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null) {
                    this.br.setCookies(this.getHost(), cookies);
                    br.getPage("https://www." + this.getHost() + "/user/useraccount");
                    if (!this.br.toString().equals("0")) {
                        logger.info("Cookie login successful");
                        account.saveCookies(this.br.getCookies(this.getHost()), "");
                        return;
                    } else {
                        logger.info("Cookie login failed");
                        br.clearCookies(null);
                    }
                }
                logger.info("Performing full login");
                br.setFollowRedirects(false);
                br.getPage("https://www." + this.getHost() + "/");
                final DownloadLink dummyLink = new DownloadLink(this, "Account", this.getHost(), "http://" + this.getHost(), true);
                final String code = this.getCaptchaCode("https://www.mvpdj.com/captcha/number2.php", dummyLink);
                final String postData = "autologin=clicked&username=" + Encoding.urlEncode(account.getUser()) + "&password=" + Encoding.urlEncode(account.getPass()) + "&authcode=" + Encoding.urlEncode(code);
                // this.br.postPage("/user/useraccount", "");
                this.br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
                this.br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                this.br.getHeaders().put("Referer", "https://www." + this.getHost() + "/");
                this.br.getHeaders().put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                this.br.postPage("/user/login", postData);
                final String statuscode = PluginJSonUtils.getJsonValue(br, "code");
                if (!"200".equals(statuscode)) {
                    throw new AccountInvalidException();
                }
                account.saveCookies(this.br.getCookies(this.getHost()), "");
            } catch (final PluginException e) {
                account.clearCookies("");
                throw e;
            }
        }
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetPluginGlobals() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}
