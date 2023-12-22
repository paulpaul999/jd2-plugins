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
import jd.http.Request;
import jd.http.URLConnectionAdapter;
import jd.http.requests.GetRequest;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountRequiredException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 48487 $", interfaceVersion = 3, names = {}, urls = {})
public class PlayaVr extends PluginForHost {
    /* "Playa" VR Player. API Spec: https://www.playavr.com/api */
    public PlayaVr(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://" + getHost() + "/join-now/");
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "vrbangers.com" });
        ret.add(new String[] { "vrconk.com" });
        ret.add(new String[] { "vrbtrans.com" });
        ret.add(new String[] { "vrbgay.com" });
        ret.add(new String[] { "blowvr.com" });
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/video/([\\w-]+)");
        }
        return ret.toArray(new String[0]);
    }

    /* Connection stuff */
    private static final boolean free_resume            = true;
    private static final int     free_maxchunks         = 0;
    private String               dllink                 = null;
    private final String         PROPERTY_ACCOUNT_TOKEN = "authtoken";
    private final String         PROPERTY_ACCOUNT_TOKEN_REFRESH = "authtoken_refresh";
    /* Properties for link objects */
    private final String         PROPERTY_PLAYA_ID      = "playa_id";
    public static final String   PROPERTY_PREMIUM_DL    = "link_premium_dl";
    /* Playa API Constants */
    public final int             PLAYA_STATUS_OK        = 2;

    @Override
    public String getAGBLink() {
        return "https://" + getHost() + "/terms-and-conditions/";
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        /* TODO: differentiate premium/trailer, quality etc */
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://video/" + fid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    public String getPlayaApiBase() {
        return "https://" + this.getHost() + "/api/playa/v1/";
    }

    protected String getPlayaId(final DownloadLink link) throws Exception {
        String currentValue = (String) link.getProperty(PROPERTY_PLAYA_ID);
        if (currentValue != null) {
            return currentValue;
        }

        final String BACKEND_API_BASE = "https://content."+ this.getHost() + "/api/content/v1/";

        final String backendApiId = this.getFID(link);
        final String backendApiUrl = BACKEND_API_BASE + "videos/" + backendApiId;

        final Map<String, Object> entries = restoreFromString(br.getPage(backendApiUrl), TypeRef.MAP);
        final Map<String, Object> data = (Map<String, Object>) entries.get("data");
        final Map<String, Object> item = (Map<String, Object>) data.get("item");
        final String playaId = item.get("playaId").toString();
        link.setProperty(PROPERTY_PLAYA_ID, playaId);
        return playaId;
    }

    public int readStatusCode(Map<String, Object> response) {
        Map<String, Object> status = (Map<String, Object>) response.get("status");
        Number statusCode = (Number) status.get("code");
        return statusCode.intValue();
    }


    private boolean login(final Account account) throws Exception {
        /* dummy for refresh only */
        synchronized (account) {
            String refreshToken = account.getPass();
            account.setProperty(PROPERTY_ACCOUNT_TOKEN_REFRESH, refreshToken);
            if (!refreshLogin(account)) {
               throw new AccountInvalidException("Refresh Token is stale");
            }
            return true;
        }
    }


    private boolean loginGuest(final Account account) throws Exception {
        synchronized (account) {
            final String apiEndpoint = getPlayaApiBase() + "auth/guest";

            // final Map<String, Object> postdata = new HashMap<String, Object>();
            // postdata.put("email", account.getUser());
            // postdata.put("password", account.getPass());
            br.postPageRaw(apiEndpoint, (String) null);
            Map<String, Object> res = restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
            
            int statusCode = readStatusCode(res);
            if (statusCode != PLAYA_STATUS_OK) {
                throw new AccountInvalidException();
            }

            Map<String, Object> data = (Map<String, Object>) res.get("data");
            Map<String, Object> token = (Map<String, Object>) data.get("token");
            account.setProperty(PROPERTY_ACCOUNT_TOKEN, token.get("access_token"));
            account.setProperty(PROPERTY_ACCOUNT_TOKEN_REFRESH, token.get("refresh_token"));

            return true;
        }
    }


    private boolean login_(final Account account) throws Exception {
        synchronized (account) {
            final String apiEndpoint = getPlayaApiBase() + "auth/user";

            final Map<String, Object> postdata = new HashMap<String, Object>();
            postdata.put("email", account.getUser());
            postdata.put("password", account.getPass());
            br.postPageRaw(apiEndpoint, JSonStorage.serializeToJson(postdata));
            Map<String, Object> res = restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
            
            int statusCode = readStatusCode(res);
            if (statusCode != PLAYA_STATUS_OK) {
                throw new AccountInvalidException();
            }

            Map<String, Object> data = (Map<String, Object>) res.get("data");
            Map<String, Object> token = (Map<String, Object>) data.get("token");
            account.setProperty(PROPERTY_ACCOUNT_TOKEN, token.get("access_token"));
            account.setProperty(PROPERTY_ACCOUNT_TOKEN_REFRESH, token.get("refresh_token"));

            return true;
        }
    }


    private boolean refreshLogin(final Account account) throws Exception {
        synchronized (account) {
            final String apiEndpoint = getPlayaApiBase() + "auth/refresh";

            final Map<String, Object> postdata = new HashMap<String, Object>();
            postdata.put("refresh_token", account.getStringProperty(PROPERTY_ACCOUNT_TOKEN_REFRESH));

            br.postPageRaw(apiEndpoint, JSonStorage.serializeToJson(postdata));
            Map<String, Object> res = restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
            
            int statusCode = readStatusCode(res);
            if (statusCode != PLAYA_STATUS_OK) {
                return false;
            }

            Map<String, Object> data = (Map<String, Object>) res.get("data");
            Map<String, Object> token = (Map<String, Object>) data.get("token");
            account.setProperty(PROPERTY_ACCOUNT_TOKEN, token.get("access_token"));
            account.setProperty(PROPERTY_ACCOUNT_TOKEN_REFRESH, token.get("refresh_token"));
            return true;
        }
    }

    private Request makeAuthorized(final Request req, final Account account) throws Exception {
        if (StringUtils.isEmpty(account.getStringProperty(PROPERTY_ACCOUNT_TOKEN))) {
            throw new AccountRequiredException();
        }
        req.getHeaders().put("Authorization", "Bearer " + account.getStringProperty(PROPERTY_ACCOUNT_TOKEN));
        return req;
    }

    private Map<String, Object> requestVideoInfo(final Account account, final String playaId) throws Exception {
        final String apiEndpoint = getPlayaApiBase() + "video/" + playaId;
        final GetRequest request = br.createGetRequest(apiEndpoint);
        makeAuthorized(request, account);
        br.getPage(request);
        return restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
    }

    private Map<String, Object> requestAccountInfo(final Account account) throws Exception {
        final String apiEndpoint = getPlayaApiBase() + "account/info";
        final GetRequest request = br.createGetRequest(apiEndpoint);
        makeAuthorized(request, account);
        br.getPage(request);
        return restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
    }

    private Map<String, Object> loginAndCallApi(final Account account, final String playaId) throws Exception {   
        /** Logic
         * try:
         * - If no token yet -> login & get video
         *   Or else:
         *      - Try getting video
         *      - Refreshing the token
         *      - New login
         */
        
        synchronized (account) {
            final boolean tokenExists = !StringUtils.isEmpty((String) account.getProperty(PROPERTY_ACCOUNT_TOKEN))
                                        && !StringUtils.isEmpty((String) account.getProperty(PROPERTY_ACCOUNT_TOKEN_REFRESH));
            final boolean fetchVidInfo = !StringUtils.isEmpty(playaId);
            boolean loginAttempted = false;

            if (!tokenExists) {
                login(account);
                loginAttempted = true;
            }

            /* soft check of token validity: either by piggybacking on videoInfo or by requesting account info */
            final Map<String, Object> infoMap = fetchVidInfo ? requestVideoInfo(account, playaId) : requestAccountInfo(account);
            if (readStatusCode(infoMap) == PLAYA_STATUS_OK) {
                return fetchVidInfo ? infoMap : null;
            }

            if (loginAttempted) {
                throw new AccountInvalidException();
            }

            boolean refreshSuccessful = refreshLogin(account);
            if (!refreshSuccessful) {
                login(account);
            }

            if (fetchVidInfo) {
                final Map<String, Object> videoInfo = requestVideoInfo(account, playaId);
                if (readStatusCode(videoInfo) == PLAYA_STATUS_OK) {
                    return videoInfo;
                }
                throw new AccountInvalidException();
            }

            return null;
        }
    }


    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        loginAndCallApi(account, null);
        ai.setUnlimitedTraffic();
        account.setType(AccountType.PREMIUM);
        return ai;
    }

    private AvailableStatus requestFileInformation(final DownloadLink link, final Account account, final boolean isDownload) throws Exception {
        /* User URL: https://vrbangers.com/video/when-a-ghost-goes-down/
         * Backend API: https://content.vrbangers.com/api/content/v1/videos/when-a-ghost-goes-down
         *    contains "playaId"
         * Playa API: https://vrbangers.com/api/playa/v1/video/{playaId}
         */
        final String playaId = getPlayaId(link);
        final String videoApiUrl = getPlayaApiBase() + playaId;

        final Map<String, Object> vidInfo = loginAndCallApi(account, playaId);
        if (readStatusCode(vidInfo) != PLAYA_STATUS_OK) {
            return AvailableStatus.FALSE;
        }

        /* TODO: pick quality, dl url, filename */
        final Map<String, Object> vidInfoData = (Map<String, Object>) vidInfo.get("data");
        final Map<String, Object> vidInfoDataVid = (Map<String, Object>) vidInfoData.get("video");
        final Map<String, String> videoLinks = (Map<String, String>) vidInfoDataVid.get("video-links"); 


        int maxResolution = -1;
        String maxResolutionKey = null;
        String regexpr = "\\b(\\d+)K"; /* https://regex101.com/r/OGL9A8/1 */
        for (String videoLinkKey : videoLinks.keySet()) {
            String resolutionStr = new Regex(videoLinkKey, regexpr).getMatch(0);
            int resolution = -1;
            try {
                resolution = Integer.valueOf(resolutionStr);
            } catch (Exception e) {
                continue;
            }
            if (resolution > maxResolution) {
                maxResolution = resolution;
                maxResolutionKey = videoLinkKey;
            }
        }

        if (maxResolutionKey != null) {
            this.dllink = videoLinks.get(maxResolutionKey);
            return AvailableStatus.TRUE;
        }
        
        return AvailableStatus.FALSE;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        return requestFileInformation(link, account, false);
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

    @Override
    public void handleFree(DownloadLink link) throws Exception {
        throw new AccountRequiredException();
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
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

}
