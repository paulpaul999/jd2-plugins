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

import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.components.config.PornoneComConfig;
import org.jdownloader.plugins.components.config.PornoneComConfig.PreferredStreamQuality;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.plugins.controller.LazyPlugin;

@HostPlugin(revision = "$Revision: 47484 $", interfaceVersion = 2, names = { "pornone.com" }, urls = { "https?://(?:www\\.)?(?:vporn|pornone)\\.com/.*?/(\\d+)/?" })
public class PornoneCom extends PluginForHost {
    @SuppressWarnings("deprecation")
    public PornoneCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://pornone.com/register/");
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    @Override
    public String rewriteHost(String host) {
        /* 2020-06-04: vpon.com is now pornone.com - existing vporn accounts are also working via pornone.com. */
        if (host == null || host.equalsIgnoreCase("vporn.com")) {
            return this.getHost();
        } else {
            return super.rewriteHost(host);
        }
    }

    private String dllink = null;

    @Override
    public String getAGBLink() {
        return "https://pornone.com/terms/";
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://" + fid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    public String getMirrorID(DownloadLink link) {
        if (link != null && StringUtils.equals(getHost(), link.getHost())) {
            return getHost() + "://" + getFID(link);
        } else {
            return super.getMirrorID(link);
        }
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, AccountController.getInstance().getValidAccount(this.getHost()));
    }

    public AvailableStatus requestFileInformation(final DownloadLink link, final Account account) throws Exception {
        final String fid = this.getFID(link);
        if (!link.isNameSet()) {
            /* Set fallback-filename */
            link.setName(fid + ".mp4");
        }
        this.setBrowserExclusive();
        if (account != null) {
            logger.info("Account available");
            this.login(account, false);
        }
        br.setFollowRedirects(true);
        if (link.getPluginPatternMatcher().matches("https?://[^/]+/embed/\\d+/?")) {
            /* Access modified URL else we won't find a tile-title */
            br.getPage("https://" + Browser.getHost(link.getPluginPatternMatcher()) + "/mature/x/" + fid);
        } else {
            br.getPage(link.getPluginPatternMatcher());
        }
        if (!br.getURL().contains(fid) || br.containsHTML("This video (is|has been) deleted") || br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String title = br.getRegex("videoname = '([^']*?)'").getMatch(0);
        if (title == null) {
            title = br.getRegex("<title>([^<>\"]*?) - (Vporn Video|vPorn.com)</title>").getMatch(0);
        }
        if (title != null) {
            title = Encoding.htmlDecode(title).trim() + ".mp4";
            link.setFinalFileName(title);
        }
        br.setFollowRedirects(true);
        int foundlinks = 0;
        URLConnectionAdapter con = null;
        String dlurl = null;
        /* videoUrlHD2 = usually only available via account, downloadUrl = Only available via account also == videoUrlLow(2) */
        final String[] quals = { "videoUrlHD2", "videoUrlMedium2", "videoUrlLow2", "videoUrlHD", "videoUrlMedium", "videoUrlLow", "downloadUrl" };
        for (final String qual : quals) {
            dlurl = br.getRegex("flashvars\\." + qual + "\\s*=\\s*\"(https?://[^<>\"]*?)\"").getMatch(0);
            if (dlurl != null) {
                foundlinks++;
                dllink = Encoding.htmlDecode(dlurl);
                try {
                    con = br.openHeadConnection(dlurl);
                    if (this.looksLikeDownloadableContent(con)) {
                        this.dllink = dlurl;
                        link.setVerifiedFileSize(con.getCompleteContentLength());
                        break;
                    }
                } finally {
                    try {
                        con.disconnect();
                    } catch (Throwable e) {
                    }
                }
            }
        }
        final String preferredQuality = getPreferredStreamQuality();
        if (foundlinks == 0) { // 2020-06-12
            if (preferredQuality != null) {
                logger.info("Trying to find user selected quality: " + preferredQuality);
                dlurl = br.getRegex("src=\"(https://[^<>\"]+\\.mp4)\"[^>]+label=\"" + preferredQuality + "\"").getMatch(0);
                if (dlurl != null) {
                    logger.info("Successfully found user selected quality");
                } else {
                    logger.info("Failed to find user selected quality");
                }
            }
            /* Grab ANY- or the BEST quality. */
            if (dlurl == null) {
                dlurl = br.getRegex("<source src=\"(http[^\"]+)\"").getMatch(0);
            }
            logger.info("dlurl: " + dlurl);
            if (dlurl != null) {
                foundlinks++;
                dlurl = Encoding.htmlDecode(dlurl);
                try {
                    con = br.openHeadConnection(dlurl);
                    if (this.looksLikeDownloadableContent(con)) {
                        this.dllink = dlurl;
                        link.setVerifiedFileSize(con.getCompleteContentLength());
                        return AvailableStatus.TRUE;
                    }
                } finally {
                    try {
                        con.disconnect();
                    } catch (Throwable e) {
                    }
                }
            }
        }
        /* js cars equals "" or just a number --> Video is not even playable via browser */
        if (foundlinks == 0 && br.containsHTML("flashvars\\.videoUrlLow\\s*=\\s*\"\"") || br.containsHTML("<source src=\"\"") || !br.containsHTML("<source src=")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (foundlinks == 0) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        handleDownload(link, null);
    }

    @SuppressWarnings("deprecation")
    public void handleDownload(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link, account);
        if (StringUtils.isEmpty(this.dllink)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
        if (!looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            if (dl.getConnection().getResponseCode() == 416) {
                logger.info("Resume failed --> Retrying from zero");
                link.setChunksProgress(null);
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Final downloadurl did not lead to downloadable content");
        }
        dl.startDownload();
    }

    private void login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                // Load cookies
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null && !force) {
                    br.setCookies(cookies);
                    return;
                }
                br.setFollowRedirects(true);
                br.getPage("https://pornone.com/login/");
                Form login = br.getFormByRegex("Login\\s*</button");
                login.put("username", Encoding.urlEncode(account.getUser()));
                login.put("password", Encoding.urlEncode(account.getPass()));
                br.submitForm(login);
                if (!isLoggedIN()) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.saveCookies(br.getCookies(br.getURL()), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    private boolean isLoggedIN() {
        return br.getCookie(br.getHost(), "ual", Cookies.NOTDELETEDPATTERN) != null;
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        /* No captchas can happen */
        account.setConcurrentUsePossible(true);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        handleDownload(link, account);
    }

    private String getPreferredStreamQuality() {
        final PornoneComConfig cfg = PluginJsonConfig.get(this.getConfigInterface());
        final PreferredStreamQuality quality = cfg.getPreferredStreamQuality();
        switch (quality) {
        default:
            return null;
        case BEST:
            return null;
        case Q2160P:
            return "2160p";
        case Q1080P:
            return "1080p";
        case Q720P:
            return "720p";
        case Q480P:
            return "480p";
        case Q360P:
            return "360p";
        case Q240P:
            return "240p";
        }
    }

    @Override
    public Class<? extends PornoneComConfig> getConfigInterface() {
        return PornoneComConfig.class;
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
