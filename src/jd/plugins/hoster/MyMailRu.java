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
import java.util.List;
import java.util.Map;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.JDHash;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;

import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.components.config.MyMailRuConfig;
import org.jdownloader.plugins.components.config.MyMailRuConfig.PreferredQuality;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.scripting.JavaScriptEngineFactory;

@HostPlugin(revision = "$Revision: 47483 $", interfaceVersion = 2, names = { "my.mail.ru" }, urls = { "http://my\\.mail\\.ru/jdeatme\\d+|https?://my\\.mail\\.ru/[^<>\"]*?video/(?:top#video=/[a-z0-9\\-_]+/[a-z0-9\\-_]+/[a-z0-9\\-_]+/\\d+|[^<>\"]*?/\\d+\\.html)|https?://(?:videoapi\\.my|api\\.video)\\.mail\\.ru/videos/embed/[^/]+/[^/]+/[a-z0-9\\-_]+/\\d+\\.html|https?://my\\.mail\\.ru/[^/]+/[^/]+/video/embed/[a-z0-9\\-_]+/\\d+|https?://my\\.mail\\.ru/video/embed/-?\\d+" })
public class MyMailRu extends PluginForHost {
    public MyMailRu(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium();
    }

    @Override
    public String getAGBLink() {
        return "http://my.mail.ru/";
    }

    private String              DLLINK                     = null;
    private static final String TYPE_VIDEO_ALL             = "https?://[^/]+/[^<>\"]*?video/.+";
    private static final String TYPE_VIDEO_1               = "https?://my\\.mail\\.ru/[^<>\"]*?video/top#video=/[a-z0-9\\-_]+/[a-z0-9\\-_]+/[a-z0-9\\-_]+/\\d+";
    private static final String TYPE_VIDEO_2               = "https?://my\\.mail\\.ru/[^<>\"]*?video/[a-z0-9\\-_]+/[a-z0-9\\-_]+/[a-z0-9\\-_]+/\\d+\\.html";
    private static final String TYPE_VIDEO_3               = "https?://(?:videoapi\\.my|api\\.video)\\.mail\\.ru/videos/embed/([^/]+/[^/]+)/([a-z0-9\\-_]+/\\d+)\\.html";
    private static final String TYPE_VIDEO_4_EMBED         = "https?://[^/]+/([^/]+/[^/]+)/video/embed/([a-z0-9\\-_]+/\\d+)$";
    private static final String TYPE_VIDEO_5_EMBED_SPECIAL = "https?://my\\.mail\\.ru/video/embed/-?(\\d+)";
    private static final String html_private               = ">Access to video denied<";

    @SuppressWarnings("deprecation")
    public void correctDownloadLink(final DownloadLink link) {
        final String addedlink = link.getDownloadURL();
        final Regex urlregex;
        final String user;
        final String urlpart;
        if (addedlink.matches(TYPE_VIDEO_3)) {
            urlregex = new Regex(addedlink, TYPE_VIDEO_3);
            user = urlregex.getMatch(0);
            urlpart = urlregex.getMatch(1);
            link.setUrlDownload(buildOriginalVideourl(user, urlpart));
        } else if (addedlink.matches(TYPE_VIDEO_4_EMBED)) {
            urlregex = new Regex(addedlink, TYPE_VIDEO_4_EMBED);
            user = urlregex.getMatch(0);
            urlpart = urlregex.getMatch(1);
            link.setUrlDownload(buildOriginalVideourl(user, urlpart));
        }
    }

    private String buildOriginalVideourl(final String user, final String urlpart) {
        if (user == null || urlpart == null) {
            return null;
        }
        return String.format("https://my.mail.ru/%s/video/%s.html", user, urlpart);
    }

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        // age check
        br.setCookie(getHost(), "ero_accept", "1");
        if (link.getDownloadURL().matches(TYPE_VIDEO_ALL)) {
            /* Video download. */
            br.setFollowRedirects(true);
            br.getPage(link.getDownloadURL());
            if (this.br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (link.getDownloadURL().matches(TYPE_VIDEO_5_EMBED_SPECIAL)) {
                /* Special embed url --> Find original video-url. */
                final String original_videourl_part = PluginJSonUtils.getJsonValue(this.br, "movieSrc");
                if (original_videourl_part == null) {
                    /* High chances that the video is offline. */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                final Regex urlregex = new Regex(original_videourl_part, "([^/]+/[^/]+)/([^/]+/\\d+)");
                final String user = urlregex.getMatch(0);
                final String urlpart = urlregex.getMatch(1);
                if (user == null || urlpart == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                link.setUrlDownload(buildOriginalVideourl(user, urlpart));
                this.br.getPage(link.getDownloadURL());
            }
            /* TODO: Fix handling for private videos */
            // if (br.containsHTML("class=\"unauthorised\\-user window\\-loading\"")) {
            // link.getLinkStatus().setStatusText("Private video");
            // return AvailableStatus.TRUE;
            // }
            /* Without these Headers, API will return response code 400 */
            br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
            br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            final String videoID = new Regex(link.getDownloadURL(), "(\\d+)\\.html$").getMatch(0);
            final String videourlpart = new Regex(br.getURL(), "my\\.mail\\.ru/([^<>\"]*?)/video/").getMatch(0);
            if (videourlpart == null) {
                /*
                 * 2017-01-27: ERROR_FILE_NOT_FOUND instead of PLUGIN_DEFECT as chances are very high that we do not have a video or the
                 * video is offline at this stage.
                 */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            link.setLinkID(this.getHost() + "://" + videoID);
            br.getPage("https://my.mail.ru/" + videourlpart + "/ajax?ajax_call=1&func_name=video.get_item&mna=&mnb=&arg_id=" + videoID + "&_=" + System.currentTimeMillis());
            br.getRequest().setHtmlCode(br.toString().replace("\\", ""));
            if (br.containsHTML(html_private)) {
                logger.info("Video is private or offline");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                // link.getLinkStatus().setStatusText("Private video");
                // return AvailableStatus.TRUE;
            }
            if (br.containsHTML("b\\-video__layer\\-error") || br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String signvideourl = getJson("signVideoUrl");
            final String filename = getJson("videoTitle");
            if (signvideourl == null || filename == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            br.getPage(signvideourl);
            DLLINK = getVideoURL();
            if (DLLINK == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                con = br2.openHeadConnection(DLLINK);
                if (con.getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else if (looksLikeDownloadableContent(con)) {
                    if (con.getCompleteContentLength() > 0) {
                        link.setDownloadSize(con.getCompleteContentLength());
                    }
                    link.setFinalFileName(Encoding.htmlDecode(filename.trim()) + ".mp4");
                } else {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown error");
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        } else {
            /* Image download. */
            final String originalLink = link.getStringProperty("mainlink", null);
            // final Regex linkInfo = new Regex(originalLink, "\\.mail\\.ru/([^<>\"/]*?)/([^<>\"/]*?)/([^<>\"/]*?)/(\\d+)\\.html");
            final String fid = new Regex(originalLink, "(\\d+)(?:\\.html)?$").getMatch(0);
            br.getPage(originalLink);
            if (br.containsHTML(">Данная страница не найдена на нашем сервере")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            for (int i = 1; i <= 2; i++) {
                if (i == 1) {
                    DLLINK = br.getRegex("data\\-filedimageurl\\s*=\\s*\"(https?://[^<>\"]+\\-" + fid + "[^<>\"]*?)\"").getMatch(0);
                }
                // if (DLLINK == null) DLLINK = "http://content.foto.mail.ru/" + linkInfo.getMatch(0) + "/" + linkInfo.getMatch(1) + "/" +
                // linkInfo.getMatch(2) + "/i-" + linkInfo.getMatch(3) + link.getStringProperty("ext", null);
                if (DLLINK == null) {
                    continue;
                }
                URLConnectionAdapter con = null;
                try {
                    final Browser br2 = br.cloneBrowser();
                    br2.setFollowRedirects(true);
                    con = br2.openGetConnection(DLLINK);
                    if (con.getResponseCode() == 500) {
                        br2.followConnection(true);
                        logger.info("High quality link is invalid, using normal link...");
                        DLLINK = null;
                        continue;
                    }
                    if (looksLikeDownloadableContent(con)) {
                        if (con.getCompleteContentLength() > 0) {
                            link.setDownloadSize(con.getCompleteContentLength());
                        }
                        link.setFinalFileName(fid + link.getStringProperty("ext", null));
                        break;
                    } else {
                        br2.followConnection(true);
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    }
                } finally {
                    try {
                        con.disconnect();
                    } catch (Throwable e) {
                    }
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        int maxChunks = 1;
        requestFileInformation(link);
        if (link.getDownloadURL().matches(TYPE_VIDEO_ALL)) {
            if (br.containsHTML(html_private)) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "Private video! This can only be downloaded by authorized users and the owner.", PluginException.VALUE_ID_PREMIUM_ONLY);
            }
            maxChunks = 0;
        }
        if (DLLINK == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        boolean resume = true;
        if (link.getBooleanProperty("noresume", false)) {
            resume = false;
        }
        // More chunks possible but not needed because we're only downloading
        // pictures here
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, DLLINK, resume, maxChunks);
        if (!looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            if (dl.getConnection().getResponseCode() == 416) {
                if (link.getBooleanProperty("noresume", false)) {
                    link.setProperty("noresume", Boolean.valueOf(false));
                    throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Server error");
                }
                logger.info("Resume impossible, disabling it for the next try");
                link.setChunksProgress(null);
                link.setProperty("noresume", Boolean.valueOf(true));
                throw new PluginException(LinkStatus.ERROR_RETRY, "Resume failed");
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        // fixFilename(downloadLink);
        dl.startDownload();
    }

    private static final String MAINPAGE = "http://my.mail.ru";

    @SuppressWarnings("unchecked")
    public static void login(final Browser br, final Account account, final boolean force) throws Exception {
        synchronized (account) {
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
                final String[] userSplit = account.getUser().split("@");
                if (userSplit.length != 2) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                br.postPage("https://auth.mail.ru/cgi-bin/auth", "level=1&page=http%3A%2F%2Fmy.mail.ru%2F&Login=" + Encoding.urlEncode(userSplit[0]) + "&Domain=" + Encoding.urlEncode(userSplit[1]) + "&Password=" + Encoding.urlEncode(account.getPass()));
                if (br.getCookie(MAINPAGE, "Mpop") == null) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
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

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        login(br, account, true);
        ai.setUnlimitedTraffic();
        ai.setStatus("Registered (free) User");
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        login(br, account, false);
        if (DLLINK == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // More chunks possible but not needed because we're only downloading
        // pictures here
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, DLLINK, false, 1);
        if (!looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        fixFilename(link);
        dl.startDownload();
    }

    private void fixFilename(final DownloadLink downloadLink) {
        final String serverFilename = Encoding.htmlDecode(getFileNameFromHeader(dl.getConnection()));
        final String newExtension = serverFilename.substring(serverFilename.lastIndexOf("."));
        if (newExtension != null && !downloadLink.getFinalFileName().endsWith(newExtension)) {
            final String oldExtension = downloadLink.getFinalFileName().substring(downloadLink.getFinalFileName().lastIndexOf("."));
            if (oldExtension != null) {
                downloadLink.setFinalFileName(downloadLink.getFinalFileName().replace(oldExtension, newExtension));
            } else {
                downloadLink.setFinalFileName(downloadLink.getFinalFileName() + newExtension);
            }
        }
    }

    private String getJson(final String parameter) {
        String result = br.getRegex("\"" + parameter + "\":([0-9\\.]+)").getMatch(0);
        if (result == null) {
            result = br.getRegex("\"" + parameter + "\"([\t\n\r ]+)?:([\t\n\r ]+)?\"([^<>\"]*?)\"").getMatch(2);
        }
        return result;
    }

    @SuppressWarnings("unused")
    private String generateVideoUrl_old(final DownloadLink dl) throws IOException {
        final Regex urlparts = new Regex(dl.getDownloadURL(), "video=/([^<>\"/]*?)/([^<>\"/]*?)/([^<>\"/]*?)/([^<>\"/]+)");
        br.getPage("https://video.mail.ru/" + urlparts.getMatch(0) + "/" + urlparts.getMatch(1) + "/" + urlparts.getMatch(2) + "/" + urlparts.getMatch(3) + ".lite");
        final String srv = grabVar("srv");
        final String vcontentHost = grabVar("vcontentHost");
        final String key = grabVar("key");
        final String rnd = "abcde";
        final String rk = rnd + key;
        final String tempHash = JDHash.getMD5(rk);
        final String pk = tempHash.substring(0, 9) + rnd;
        DLLINK = "https://" + vcontentHost + "/" + urlparts.getMatch(0) + "/" + urlparts.getMatch(1) + "/" + urlparts.getMatch(2) + "/" + urlparts.getMatch(3) + "flv?k=" + pk + "&" + srv;
        return DLLINK;
    }

    private String grabVar(final String var) {
        return br.getRegex("\\$" + var + "=([^<>\"]*?)(\r|\t|\n)").getMatch(0);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private String getVideoURL() throws Exception {
        String bestDirecturl = null;
        final String preferredQuality = getConfiguredQuality();
        final Map<String, Object> entries = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
        final List<Object> videoQualities = (List) entries.get("videos");
        String directurl = null;
        for (final Object quality : videoQualities) {
            final Map<String, Object> quality_map = (Map<String, Object>) quality;
            final String currDirectURL = (String) quality_map.get("url");
            final String qualityKey = (String) quality_map.get("key");
            if (StringUtils.isEmpty(currDirectURL) || StringUtils.isEmpty(qualityKey)) {
                /* Skip invalid items */
                continue;
            }
            /* json Array is sorted from best --> worst */
            if (bestDirecturl == null) {
                bestDirecturl = currDirectURL;
            }
            if (preferredQuality != null && qualityKey.equalsIgnoreCase(preferredQuality)) {
                logger.info("Found user preferred quality: " + preferredQuality);
                directurl = currDirectURL;
                break;
            }
        }
        if (directurl == null) {
            logger.info("Using BEST quality");
            directurl = bestDirecturl;
        }
        return directurl;
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return MyMailRuConfig.class;
    }

    protected String getConfiguredQuality() {
        /* Returns user-set value which can be used to circumvent government based GEO-block. */
        final PreferredQuality cfgquality = PluginJsonConfig.get(MyMailRuConfig.class).getPreferredQuality();
        if (cfgquality == null) {
            return null;
        } else {
            switch (cfgquality) {
            case Q360P:
                return "360p";
            case Q480P:
                return "480p";
            case Q720P:
                return "720p";
            case Q1080P:
                return "1080p";
            case Q2160P:
                return "2160p";
            case BEST:
            default:
                return null;
            }
        }
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}