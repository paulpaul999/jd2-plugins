package jd.plugins.hoster;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "povr.com" }, urls = { "https?://(?:www\\.)?povr\\.com/vr-porn/([^/\\s]+)" })
public class PovrCom extends PluginForHost {
    private final String API_BASE = "https://povr.com/heresphere";

    public PovrCom(PluginWrapper wrapper) {
        super(wrapper);
        enablePremium("https://povr.com");
    }
    // @Override
    // public boolean canHandle(final DownloadLink downloadLink, final Account account) throws Exception {
    // if (account == null) {
    // return false;
    // } else {
    // if (downloadLink != null) {
    // final String user = downloadLink.getStringProperty("requires_account", null);
    // if (user != null) {
    // return StringUtils.equalsIgnoreCase(user, account.getUser());
    // }
    // }
    // return super.canHandle(downloadLink, account);
    // }
    // }
    // private Browser prepBR(final Browser br) {
    // br.getHeaders().put(new HTTPHeader("User-Agent", "jdownloader", false));
    // br.getHeaders().put(new HTTPHeader("Accept", "application/json", false));
    // br.setFollowRedirects(true);
    // return br;
    // }
    // @Override
    // public AccountInfo fetchAccountInfo(final Account account) throws Exception {
    // final String access_token = login(account);
    // final AccountInfo ai = new AccountInfo();
    // if (br.getURL() == null || !br.getURL().contains("/account/info")) {
    // prepBR(br);
    // br.getHeaders().put(new HTTPHeader("Authorization", "token " + access_token, false));
    // br.getPage(API_BASE + "/account/info");
    // if (br.getHttpConnection().getResponseCode() != 200) {
    // throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
    // }
    // }
    // final Map<String, Object> infoResponse = restoreFromString(br.toString(), TypeRef.MAP);
    // final Map<String, Object> info = (Map<String, Object>) infoResponse.get("info");
    // final String dateExpireStr = (String) info.get("plan_expiration_date");
    // final long dateExpire = TimeFormatter.getMilliSeconds(dateExpireStr, "yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH);
    // if (dateExpire - System.currentTimeMillis() > 0) {
    // ai.setValidUntil(dateExpire);
    // account.setType(AccountType.PREMIUM);
    // } else {
    // account.setType(AccountType.FREE);
    // }
    // ai.setUnlimitedTraffic();
    // return ai;
    // }

    @Override
    public String getAGBLink() {
        return "https://povr.com/legal#terms";
    }

    private String getFID(final DownloadLink link) {
        String urlName = new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
        String[] parts = urlName.split("-");
        int lastElementIdx = parts.length - 1;
        String sceneID = parts[lastElementIdx];
        return sceneID;
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

    @Override
    public void handleFree(DownloadLink link) throws Exception {
        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
    }

    @Override
    public void handlePremium(DownloadLink link, Account account) throws Exception {
        String url = "https://trailers.czechvr.com/czechvr/videos/download/645/645-czechvr-3d-7680x3840-60fps-oculusrift_uhq_h265-trailer-1.mp4";
        link.setName("floetenmann_VR180.mp4");
        final Browser brc = br.cloneBrowser();
        brc.setFollowRedirects(true);
        dl = jd.plugins.BrowserAdapter.openDownload(brc, link, url, true, 0);
        // final int responseCode = dl.getConnection().getResponseCode();
        // URLConnectionAdapter con = dl.getConnection().isContentDisposition();
        dl.startDownload();
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return AvailableStatus.TRUE;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}