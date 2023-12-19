//jDownloader - Downloadmanager
//Copyright (C) 2017  JD-Team support@jdownloader.org
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.HTMLParser;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountRequiredException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 48487 $", interfaceVersion = 3, names = {}, urls = {})
public class BadoinkvrCom extends PluginForHost {
    public BadoinkvrCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://" + getHost() + "/join/");
    }

    @Override
    public Browser createNewBrowserInstance() {
        final Browser br = super.createNewBrowserInstance();
        br.setFollowRedirects(true);
        return br;
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "badoinkvr.com" });
        ret.add(new String[] { "kinkvr.com" });
        ret.add(new String[] { "babevr.com" });
        ret.add(new String[] { "vrcosplayx.com" });
        ret.add(new String[] { "18vr.com" });
        ret.add(new String[] { "czechvrnetwork.com" });
        ret.add(new String[] { "povr.com" });
        ret.add(new String[] { "wankzvr.com" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            /**
             * 2023-11-14: </br>
             * vrpornvideo: badoinkvr.com, babevr.com, 18vr.com </br>
             * cosplaypornvideo: vrcosplayx.com </br>
             * bdsm-vr-video: kinkvr.com
             */
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(?:members/)?(?:[^/]+/)?[^/]*-(\\d{3,})");
            // ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/heresphere/(.*)");
        }
        return ret.toArray(new String[0]);
    }
    /* DEV NOTES */
    // Tags: Porn plugin
    // protocol: no https
    // other:

    /* Connection stuff */
    private static final boolean free_resume            = true;
    private static final int     free_maxchunks         = 0;
    private static final int     free_maxdownloads      = -1;
    private String               dllink                 = null;
    private final String         PROPERTY_ACCOUNT_TOKEN = "authtoken";
    /* Properties for link objects */
    public static final String   PROPERTY_PREMIUM_DL  = "link_premium_dl";
    // public static final String   PROPERTY_MEDIA_NAME    = "link_media_name";
    // public static final String   PROPERTY_MEDIA_RESOLUTION = "link_media_resolution";

    @Override
    public String getAGBLink() {
        return "https://" + getHost() + "/terms";
    }

    public String buildHeresphereVideoUrl(String videoId, DownloadLink link) {
        final String host = this.getHost();
        final String apiBaseUrl = getHeresphereApiBaseUrl();

        final Boolean premiumRouteB = (Boolean) link.getProperty(PROPERTY_PREMIUM_DL);
        final boolean premiumRoute = premiumRouteB != null ? premiumRouteB.booleanValue() : false;

        boolean isBadoinkNetwork = host.equals("badoinkvr.com") 
                                || host.equals("kinkvr.com")
                                || host.equals("babevr.com")
                                || host.equals("vrcosplayx.com")
                                || host.equals("18vr.com");

        if (isBadoinkNetwork) {
            if (premiumRoute) {
                return apiBaseUrl + "/video/" + videoId;
            } else {
                return apiBaseUrl + "/video/" + videoId + "/trailer";
            }
        }

        if (host.equals("czechvrnetwork.com")) {
            return apiBaseUrl + "/videoID" + videoId;
        }

        /* default */
        return apiBaseUrl + "/" + videoId;
    }

    public String getHeresphereApiBaseUrl() {
        final String host = this.getHost();
        if (host.endsWith("czechvrnetwork.com")) {
            return "https://" + "www." + host + "/heresphere";
        }
        return "https://" + host + "/heresphere/";
    }

    private String getVideoFilename(final DownloadLink link, final String dllink, final Map<String, Object> videoInfos) {
        final String host = getHost();
        if (host.endsWith("povr.com") || host.endsWith("wankzvr.com")) {
            String regexpr = "\\.com/(?:[^/]*/)?([^/]+)-\\d+";
            String videoUrlName = new Regex(link.getPluginPatternMatcher(), regexpr).getMatch(0);
            String studioUrlFormatted = "";
            List<String> tags = this.getTags(videoInfos, "Studio:");
            if (tags.size() > 0) {
                studioUrlFormatted = tags.get(0).toLowerCase().replace(' ', '-');
            }
            return studioUrlFormatted + "-" + videoUrlName + "-180_180x180_3dh_LR.mp4"; /* consider handling the case of non-VR180 format */
        }

        /* default: extract filename from dllink */
        String default_regexpr = "([^/?&=]+\\.(?:mp4|webm|mkv))";
        return new Regex(dllink, default_regexpr).getMatch(0);
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        /* TODO: differentiate premium/trailer */
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://video/" + fid + "/" + link.getStringProperty(PROPERTY_PREMIUM_DL);
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        return requestFileInformation(link, account, false);
    }

    private boolean premiumAccessAvailable(final Account account) {
        if (account != null && account.getType() == AccountType.PREMIUM) {
            return true;
        } else {
            return false;
        }
    }

    private String pickVideoQuality(final Map<String, Object> videoInfos) {
        String pickedUrl = null;

        long filesizeMax = 0;
        int resolutionMax = 0;
        String pickedMediaName = null;

        final List<Map<String, Object>> medias = (List<Map<String, Object>>) videoInfos.get("media");
        for (final Map<String, Object> media : medias) {
            final String mediaName = media.get("name").toString();
            final List<Map<String, Object>> sources = (List<Map<String, Object>>) media.get("sources");
            for (final Map<String, Object> source : sources) {
                final Object filesizeO = source.get("size");
                final Object resolutionO = source.get("resolution");
                final String url = source.get("url").toString();
                final boolean enableFilesizePicking = false;
                if (enableFilesizePicking && filesizeO != null) {
                    /* Filesize is not always given */
                    final long thisFilesize = ((Number) filesizeO).longValue();
                    if (thisFilesize > filesizeMax) {
                        filesizeMax = thisFilesize;
                        pickedUrl = url;
                        pickedMediaName = mediaName;
                    }
                } else if (resolutionO instanceof Number) {
                    final int thisResolutionValue = ((Number) resolutionO).intValue();
                    if (thisResolutionValue > resolutionMax) {
                        resolutionMax = thisResolutionValue;
                        pickedUrl = url;
                        pickedMediaName = mediaName;
                    }
                }
                /* Fallback: We always want to have a result */
                if (pickedUrl == null) {
                    pickedUrl = url;
                    pickedMediaName = mediaName;
                }
            }
        }
        return pickedUrl;
    }

    private List<String> getTags(final Map<String, Object> videoInfos, final String prefix) {
        List<String> ret = new ArrayList<String>();

        List<Map<String, String>> tags = (List<Map<String, String>>) videoInfos.get("tags");
        for (Map<String, String> tagMap : tags) {
            String prefixedTag = tagMap.get("name");
            if (prefixedTag.toLowerCase().startsWith(prefix.toLowerCase())) {
                String tag = prefixedTag.substring(prefix.length());
                ret.add(tag);
            }
        }
        return ret;
    }

    private AvailableStatus requestFileInformation(final DownloadLink link, final Account account, final boolean isDownload) throws Exception {
        dllink = null;
        final String videoid = this.getFID(link);
        final String extDefault = ".mp4";
        // final String titleFromURL = new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0).replace("_", " ").trim();
        // if (!link.isNameSet()) {
        //     link.setName(videoid + "_" + titleFromURL + extDefault);
        // }
        this.setBrowserExclusive();
        long filesize = -1;
        String filename = null;
        String title = null;
        String description = null;

        // if (true) {
        //     throw new PluginException(LinkStatus.ERROR_PREMIUM, "Did not rx premium level access");
        // }
        
        final boolean maxPossibleRoute = premiumAccessAvailable(account);
        final Boolean linkAccessLevel = (Boolean) link.getProperty(PROPERTY_PREMIUM_DL, Boolean.valueOf(maxPossibleRoute));
        link.setProperty(PROPERTY_PREMIUM_DL, linkAccessLevel);
        final boolean usePremiumRoute = linkAccessLevel.booleanValue();
        if (usePremiumRoute == true && maxPossibleRoute == false) {
            throw new AccountRequiredException();
        }

        String videoApiUrl = buildHeresphereVideoUrl(videoid, link);
        Account accountOrNull = usePremiumRoute ? account : null; /* TODO: decide if loggedIn=FREE is possible */

        /* Use heresphere API */
        Map<String, Object> entries = null;
        if (!usePremiumRoute) {
            entries = callApi(accountOrNull, videoApiUrl);
        } else {
            entries = loginAndCallApi(accountOrNull, videoApiUrl, false);
        }

        title = entries.get("title").toString();
        description = (String) entries.get("description");

        this.dllink = this.pickVideoQuality(entries);
        filename = this.getVideoFilename(link, this.dllink, entries);

        if (!StringUtils.isEmpty(description) && link.getComment() == null) {
            link.setComment(description);
        }
        if (filename != null) {
            /* Pre defined filename -> Prefer that and use it as final filename. */
            if (!link.isNameSet()) {
                link.setName(filename);
            }
            link.setFinalFileName(filename);
        } else if (!StringUtils.isEmpty(title)) {
            title = videoid + "_" + title;
            title = Encoding.htmlDecode(title);
            title = title.trim();
            link.setName(this.correctOrApplyFileNameExtension(title, extDefault));
        }
        if (filesize > 0) {
            /* Successfully found 'MOCH-filesize' --> Display assumed filesize for MOCH download. */
            link.setDownloadSize(filesize);
        } else if (!StringUtils.isEmpty(dllink) && !isDownload) {
            /* Find filesize via header */
            URLConnectionAdapter con = null;
            try {
                con = br.openHeadConnection(this.dllink);
                handleConnectionErrors(br, con);
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
                final String extReal = Plugin.getExtensionFromMimeTypeStatic(con.getContentType());
                if (extReal != null) {
                    link.setFinalFileName(this.correctOrApplyFileNameExtension(title, "." + extReal));
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link, null, true);
        if (StringUtils.isEmpty(dllink)) {
            /* Assume that trailer download is impossible and this content can only be accessed by premium users. */
            throw new AccountRequiredException();
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, free_resume, free_maxchunks);
        handleConnectionErrors(br, dl.getConnection());
        dl.startDownload();
    }

    private void handleConnectionErrors(final Browser br, final URLConnectionAdapter con) throws PluginException, IOException {
        if (!this.looksLikeDownloadableContent(con)) {
            br.followConnection(true);
            if (con.getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (con.getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Video broken?");
            }
        }
    }


    private void prepareBrowser(final Browser br) {
        br.setCookiesExclusive(true);
        br.getHeaders().put("User-Agent", "HereSphere");
    }


    private Map<String, Object> callApi(final Account account, String apiUrl) throws Exception {
        prepareBrowser(this.br);
        boolean useToken = account != null;
        if (useToken) {
            String token = account.getStringProperty(PROPERTY_ACCOUNT_TOKEN);
            if (token != null) {
                prepLoginHeader(br, token);
            }
        }
        br.postPageRaw(apiUrl, "");
        final Map<String, Object> entries = restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
        return entries;
    }

    private Map<String, Object> loginApi(final Account account, String apiAuthEndpoint) throws Exception {
        prepareBrowser(this.br);
        final Map<String, Object> postdata = new HashMap<String, Object>();
        postdata.put("username", account.getUser());
        postdata.put("password", account.getPass());
        br.postPageRaw(apiAuthEndpoint, JSonStorage.serializeToJson(postdata));
        return restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
    }

    private Map<String, Object> loginAndCallApi(final Account account, String checkUrl, final boolean force) throws Exception {        
        synchronized (account) {
            final boolean checkUrlGiven = checkUrl != null && !checkUrl.isEmpty();
            final String apiMainEndpoint = "https://" + this.getHost() + "/heresphere";
            final String apiAuthEndpoint = apiMainEndpoint + "/auth";
            if (!checkUrlGiven) {
                checkUrl = apiMainEndpoint;
            }
            boolean tokenAvailable = false;
            if (account != null) {
                tokenAvailable = (account.getStringProperty(PROPERTY_ACCOUNT_TOKEN) != null) && !account.getStringProperty(PROPERTY_ACCOUNT_TOKEN).isEmpty();
            }
    
            this.prepareBrowser(this.br);
            if (!force && tokenAvailable) {
                Map<String, Object> response = this.callApi(account, checkUrl);
                final boolean accessLevelMatching = account.getType() == mapLoginStatus(response);
                if (accessLevelMatching) {
                    logger.info("token login successful");
                    return response;
                } else {
                    logger.info("access level missmatch or token expired. re-login to try to elevate access level");
                }
            }
            /* Remove token so we won't try again with this one */
            account.removeProperty(PROPERTY_ACCOUNT_TOKEN);
            /* login */
            logger.info("Performing full login");
            final Map<String, Object> entries = loginApi(account, apiAuthEndpoint);
            boolean isLoggedIn = mapLoginStatus(entries) == AccountType.FREE || mapLoginStatus(entries) == AccountType.PREMIUM;
            if (!isLoggedIn) {
                throw new AccountInvalidException();
            }
            String token = entries.get("auth-token").toString();
            account.setProperty(PROPERTY_ACCOUNT_TOKEN, token);
            return callApi(account, checkUrl);
        }
    }

    private void prepLoginHeader(final Browser br, final String token) {
        br.getHeaders().put("auth-token", token);
    }

    // /* !!!!! TODO check deprecation */
    // private boolean isLoggedin(final Map<String, Object> entries) {
    //     final Number loginstatus = (Number) entries.get("access");
    //     if (loginstatus != null && (loginstatus.intValue() == 0 || loginstatus.intValue() == 1)) {
    //         return true;
    //     } else {
    //         return false;
    //     }
    // }

    private AccountType mapLoginStatus(final Map<String, Object> entries) {
        final Number loginstatus = (Number) entries.get("access");
        if (loginstatus != null) {
            if (loginstatus.intValue() == 0) {
                return AccountType.FREE;
            } else if (loginstatus.intValue() == 1) {
                return AccountType.PREMIUM;
            }
        }
        return AccountType.UNKNOWN;
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        final Map<String, Object> usermap = loginAndCallApi(account, null, true);
        final Number loginstatus = (Number) usermap.get("access");
        ai.setUnlimitedTraffic();
        account.setType(mapLoginStatus(usermap));
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link, account, true);
        if (StringUtils.isEmpty(dllink)) {
            /* No download or only trailer download possible. */
            throw new AccountRequiredException();
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, free_resume, free_maxchunks);
        handleConnectionErrors(br, dl.getConnection());
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public boolean hasCaptcha(final DownloadLink link, final Account acc) {
        return false;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return free_maxdownloads;
    }
    // @Override
    // public Class<? extends PluginConfigInterface> getConfigInterface() {
    // return HereSphereConfig.class;
    // }
    //
    // @PluginHost(host = "badoinkvr.com", type = Type.HOSTER)
    // public static interface HereSphereConfig extends PluginConfigInterface {
    // public static final TRANSLATION TRANSLATION = new TRANSLATION();
    //
    // public static class TRANSLATION {
    // public String getPreferredQuality_label() {
    // return "Use https for final downloadurls?";
    // }
    // }
    //
    // public static enum PreferredQuality implements LabelInterface {
    // BEST {
    // @Override
    // public String getLabel() {
    // return "Best";
    // }
    // },
    // Q_144P {
    // @Override
    // public String getLabel() {
    // return "144p";
    // }
    // },
    // Q_270P {
    // @Override
    // public String getLabel() {
    // return "270p";
    // }
    // };
    // }
    //
    // @AboutConfig
    // @DefaultEnumValue("BEST")
    // @DescriptionForConfigEntry("Preferred quality")
    // @Order(100)
    // PreferredQuality getPreferredQuality();
    //
    // void setPreferredQuality(PreferredQuality quality);
    // }

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
