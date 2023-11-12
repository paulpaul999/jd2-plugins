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

import java.util.HashMap;

import org.appwork.utils.net.httpconnection.HTTPConnection;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.plugins.components.antiDDoSForHost;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.SubConfiguration;
import jd.controlling.AccountController;
import jd.http.Browser;
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
import jd.plugins.components.PluginJSonUtils;
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision: 47664 $", interfaceVersion = 3, names = { "porn.com" }, urls = { "https?://(?:\\w+\\.)?porn\\.com/(?:videos/[a-z0-9\\-]*?-|videos/embed/)(\\d+)" })
public class PornCom extends antiDDoSForHost {
    /* DEV NOTES */
    /* Porn_plugin */
    /* Connection stuff */
    private static final boolean FREE_RESUME       = true;
    private static final int     FREE_MAXCHUNKS    = 1;
    private static final int     FREE_MAXDOWNLOADS = -1;
    // private static final boolean ACCOUNT_FREE_RESUME = true;
    // private static final int ACCOUNT_FREE_MAXCHUNKS = 0;
    // private static final int ACCOUNT_FREE_MAXDOWNLOADS = 20;
    // private static final boolean ACCOUNT_PREMIUM_RESUME = true;
    // private static final int ACCOUNT_PREMIUM_MAXCHUNKS = 0;
    // private static final int ACCOUNT_PREMIUM_MAXDOWNLOADS = 20;
    private String               dllink            = null;
    private String               vq                = null;

    public PornCom(PluginWrapper wrapper) {
        super(wrapper);
        this.setConfigElements();
        this.enablePremium("https://www.porn.com/profile/premium");
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    @Override
    public String getAGBLink() {
        return "https://www.porn.com/legal#terms";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String linkid = getFID(link);
        if (linkid != null) {
            return this.getHost() + "://" + linkid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        link.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        final Account aa = AccountController.getInstance().getValidAccount(this.getHost());
        if (aa != null) {
            try {
                login(br, aa, false);
            } catch (final Throwable e) {
                logger.log(e);
            }
        }
        br.getPage(link.getDownloadURL().replace("/embed/", "/"));
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("(id=\"error\"><h2>404|No such video|<title>PORN\\.COM</title>)") || !br.getURL().contains(this.getFID(link))) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String q = link.getStringProperty("q", null);
        String filename = getFilename(br);
        if (q != null) {
            final HashMap<String, String> matches = jd.plugins.decrypter.PornCom.getQualities(this.br);
            dllink = matches.get(q);
        } else {
            get_dllink(this.br);
        }
        /* A little trick to download videos that are usually only available for registered users WITHOUT account :) */
        if (dllink == null) {
            final Browser brc = br.cloneBrowser();
            /* This way we can access links which are usually only accessible for registered users */
            brc.getPage("https://www.porn.com/videos/embed/" + this.getFID(link));
            if (q != null) {
                dllink = brc.getRegex(q + "\",url:\"(https?:.*?)\"").getMatch(0);
            } else {
                get_dllink(brc);
            }
        }
        if (dllink == null) {
            if (br.containsHTML(">Sorry, this video is only available to members")) {
                if (q == null) {
                    link.setName(filename + ".mp4");
                }
                link.getLinkStatus().setStatusText("Only available for registered users");
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dllink = dllink.replace("\\", "");
        if (q == null) {
            final String ext = getFileNameExtensionFromString(dllink, ".mp4");
            if (vq == null) {
                link.setFinalFileName(filename + ext);
            } else {
                link.setFinalFileName(filename + "." + vq + ext);
            }
        }
        URLConnectionAdapter con = null;
        try {
            con = br.openHeadConnection(dllink);
            if (this.looksLikeDownloadableContent(con)) {
                if (con.getCompleteContentLength() > 0) {
                    link.setDownloadSize(con.getCompleteContentLength());
                }
            } else {
                // throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
        return AvailableStatus.TRUE;
    }

    @SuppressWarnings("deprecation")
    private void get_dllink(final Browser brc) {
        final SubConfiguration cfg = getPluginConfig();
        boolean q240 = cfg.getBooleanProperty("240p", false);
        boolean q360 = cfg.getBooleanProperty("360p", false);
        boolean q480 = cfg.getBooleanProperty("480p", false);
        boolean q720 = cfg.getBooleanProperty("720p", false);
        if (q720) {
            dllink = brc.getRegex("720p\",url:\"(https?:.*?)\"").getMatch(0);
            vq = "720p";
        }
        if (dllink == null) {
            if (q480) {
                dllink = brc.getRegex("480p\",url:\"(https?:.*?)\"").getMatch(0);
                vq = "480p";
            }
        }
        if (dllink == null) {
            if (q360) {
                dllink = brc.getRegex("360p\",url:\"(https?:.*?)\"").getMatch(0);
                vq = "360p";
            }
        }
        if (dllink == null) {
            dllink = brc.getRegex("240p\",url:\"(https?:.*?)\"").getMatch(0); // Default
            vq = "240p";
        }
        if (dllink != null) {
            return;
        }
        logger.info("Video quality selection failed.");
        // json
        final String a = PluginJSonUtils.getJsonArray(brc.toString(), "streams");
        final String[] array = PluginJSonUtils.getJsonResultsFromArray(a);
        if (array != null) {
            int highestQual = 0;
            String bestUrl = null;
            for (final String aa : array) {
                final String quality = PluginJSonUtils.getJsonValue(aa, "name");
                final String q = quality != null ? new Regex(quality, "\\d+").getMatch(-1) : null;
                final int qual = q != null ? Integer.parseInt(q) : 0;
                if (qual > highestQual) {
                    highestQual = qual;
                    final String url = PluginJSonUtils.getJsonValue(aa, "url");
                    if (url != null) {
                        bestUrl = url;
                    }
                }
            }
            dllink = bestUrl;
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        doFree(link);
    }

    private void doFree(final DownloadLink link) throws Exception {
        if (dllink == null && br.containsHTML(">Sorry, this video is only available to members")) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        } else if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, FREE_RESUME, FREE_MAXCHUNKS);
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            final long responsecode = ((HTTPConnection) dl).getResponseCode();
            if (responsecode == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Error 403", 5 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        dl.startDownload();
    }

    private static final String MAINPAGE = "https://porn.com";

    /** Login e.g. needed for videos that are only available to registered users and/or premium content. */
    public static void login(final Browser br, final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                // Load cookies
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null && !force) {
                    br.setCookies("porn.com", cookies);
                    return;
                }
                br.setFollowRedirects(false);
                br.getPage("https://www.porn.com/");
                // br.getHeaders().put("X-CSRF-Token", "");
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.postPage("/login", "captcha=&captcha_hash=&remember=yes&form_id=login&username=" + Encoding.urlEncode(account.getUser()) + "&password=" + Encoding.urlEncode(account.getPass()));
                br.getPage("https://www.porn.com/");
                if (br.getCookie(MAINPAGE, "auth") == null) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.saveCookies(br.getCookies("porn.com"), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == PluginException.VALUE_ID_PREMIUM_DISABLE) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(br, account, true);
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        /* free accounts can still have captcha */
        account.setMaxSimultanDownloads(FREE_MAXDOWNLOADS);
        account.setConcurrentUsePossible(false);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        doFree(link);
    }

    public static String getFilename(Browser br) throws Exception {
        String filename = br.getRegex("embedURL:\"[^\"]+\",title:\"([^<>\"]*?)\"").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("<title>(.*?)( - PORN.COM)?</title>").getMatch(0);
        }
        if (filename == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else {
            filename = Encoding.htmlDecode(filename.trim());
        }
        return filename;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Override
    public String getDescription() {
        return "Only highest quality video available that you choose will be chosen.";
    }

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), "ALLOW_BEST", JDL.L("plugins.hoster.PornCom.checkbest", "Only grab the best available resolution")).setDefaultValue(false));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SEPARATOR));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), "240p", JDL.L("plugins.hoster.PornCom.check240p", "Choose 240p?")).setDefaultValue(true));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), "360p", JDL.L("plugins.hoster.PornCom.check360p", "Choose 360p?")).setDefaultValue(true));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), "480p", JDL.L("plugins.hoster.PornCom.check480p", "Choose 480p?")).setDefaultValue(true));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), "720p", JDL.L("plugins.hoster.PornCom.check720p", "Choose 720p?")).setDefaultValue(true));
    }

    @Override
    public void reset() {
        dllink = null;
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public void resetPluginGlobals() {
    }
}