package jd.plugins.hoster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.Hash;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.requests.PostRequest;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.MultiHosterManagement;

@HostPlugin(revision = "$Revision: 48194 $", interfaceVersion = 3, names = { "linkifier.com" }, urls = { "" })
public class LinkifierCom extends PluginForHost {
    private static MultiHosterManagement mhm      = new MultiHosterManagement("linkifier.com");
    private static final String          API_KEY  = "d046c4309bb7cabd19f49118a2ab25e0";
    private static final String          API_BASE = "https://api.linkifier.com";

    public LinkifierCom(PluginWrapper wrapper) {
        super(wrapper);
        enablePremium("https://www.linkifier.com");
    }

    @Override
    public String getAGBLink() {
        return "https://www.linkifier.com/terms-of-use/";
    }

    @Override
    public AccountInfo fetchAccountInfo(Account account) throws Exception {
        final String username = account.getUser();
        if (username == null || !username.matches("^.+?@.+?\\.[^\\.]+")) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, "Please use your email address to login", PluginException.VALUE_ID_PREMIUM_DISABLE);
        }
        final AccountInfo ai = new AccountInfo();
        final HashMap<String, Object> userJson = new HashMap<String, Object>();
        userJson.put("login", username);
        userJson.put("md5Pass", Hash.getMD5(account.getPass()));
        userJson.put("apiKey", API_KEY);
        final PostRequest userRequest = new PostRequest(API_BASE + "/downloadapi.svc/user");
        userRequest.setContentType("application/json; charset=utf-8");
        userRequest.setPostBytes(JSonStorage.serializeToJsonByteArray(userJson));
        final Map<String, Object> userResponse = restoreFromString(br.getPage(userRequest), TypeRef.MAP);
        if (Boolean.TRUE.equals(userResponse.get("isActive")) && !Boolean.TRUE.equals(userResponse.get("hasErrors"))) {
            final PostRequest hosterRequest = new PostRequest(API_BASE + "/DownloadAPI.svc/hosters");
            hosterRequest.setContentType("application/json; charset=utf-8");
            hosterRequest.setPostBytes(JSonStorage.serializeToJsonByteArray(userJson));
            final Map<String, Object> hosterResponse = restoreFromString(br.getPage(hosterRequest), TypeRef.MAP);
            if ("unlimited".equalsIgnoreCase(String.valueOf(userResponse.get("extraTraffic")))) {
                ai.setUnlimitedTraffic();
            }
            // final long serverDate = ((Number) hosterResponse.get("ServerDate")).longValue();
            final Number expiryDate = (Number) userResponse.get("expirydate");
            ai.setValidUntil(expiryDate.longValue());
            if (!ai.isExpired()) {
                final List<Map<String, Object>> hosters = (List<Map<String, Object>>) hosterResponse.get("hosters");
                if (hosters != null) {
                    final List<String> supportedHosts = new ArrayList<String>();
                    for (Map<String, Object> host : hosters) {
                        final String hostername = host.get("hostername") != null ? String.valueOf(host.get("hostername")) : null;
                        if (Boolean.TRUE.equals(host.get("isActive")) && StringUtils.isNotEmpty(hostername)) {
                            supportedHosts.add(hostername);
                        }
                    }
                    ai.setMultiHostSupport(this, supportedHosts);
                }
                account.setType(AccountType.PREMIUM);
                account.setMaxSimultanDownloads(0);
                return ai;
            }
        }
        final String errorMsg = userResponse.get("ErrorMSG") != null ? String.valueOf(userResponse.get("ErrorMSG")) : null;
        if (StringUtils.containsIgnoreCase(errorMsg, "Error verifying api key")) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
        } else if (StringUtils.containsIgnoreCase(errorMsg, "Could not find a customer with those credentials")) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
        } else if (StringUtils.isNotEmpty(errorMsg)) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, errorMsg, PluginException.VALUE_ID_PREMIUM_DISABLE);
        }
        if (errorMsg == null) {
            final Number expiryDate = (Number) userResponse.get("expirydate");
            if (expiryDate != null && expiryDate.longValue() < System.currentTimeMillis()) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "Account expired", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
        }
        /* 2017-01-05: Free accounts cannot download anything. */
        account.setType(AccountType.FREE);
        account.setMaxSimultanDownloads(1);
        account.setConcurrentUsePossible(false);
        ai.setStatus("Free Account");
        ai.setTrafficLeft(0);
        return ai;
    }

    @Override
    public boolean canHandle(final DownloadLink link, final Account account) throws Exception {
        if (account == null || account.getType() != AccountType.PREMIUM) {
            return false;
        } else {
            mhm.runCheck(account, link);
            return true;
        }
    }

    @Override
    public AvailableStatus requestFileInformation(DownloadLink parameter) throws Exception {
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
    }

    @Override
    public void handleFree(DownloadLink link) throws Exception {
        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
    }

    @Override
    public void handleMultiHost(final DownloadLink link, Account account) throws Exception {
        mhm.runCheck(account, link);
        final HashMap<String, Object> downloadJson = new HashMap<String, Object>();
        downloadJson.put("login", account.getUser());
        downloadJson.put("md5Pass", Hash.getMD5(account.getPass()));
        downloadJson.put("apiKey", API_KEY);
        downloadJson.put("url", link.getDefaultPlugin().buildExternalDownloadURL(link, this));
        Browser br = new Browser();
        final PostRequest downloadRequest = new PostRequest(API_BASE + "/downloadapi.svc/stream");
        downloadRequest.setContentType("application/json; charset=utf-8");
        downloadRequest.setPostBytes(JSonStorage.serializeToJsonByteArray(downloadJson));
        final Map<String, Object> downloadResponse = restoreFromString(br.getPage(downloadRequest), TypeRef.MAP);
        if (Boolean.FALSE.equals(downloadResponse.get("hasErrors"))) {
            final String dllink = downloadResponse.get("url") != null ? String.valueOf(downloadResponse.get("url")) : null;
            if (StringUtils.isEmpty(dllink)) {
                mhm.handleErrorGeneric(account, this.getDownloadLink(), "dllinknull", 50, 5 * 60 * 1000l);
            }
            br.setConnectTimeout(120 * 1000);
            br.setReadTimeout(120 * 1000);
            final Number con_max = (Number) downloadResponse.get("con_max");
            final Boolean con_resume = (Boolean) downloadResponse.get("con_resume");
            final boolean resume;
            final int maxChunks;
            if (Boolean.FALSE.equals(con_resume)) {
                maxChunks = 1;
                resume = false;
            } else {
                resume = true;
                if (con_max != null) {
                    if (con_max.intValue() <= 1) {
                        maxChunks = 1;
                    } else {
                        maxChunks = -con_max.intValue();
                    }
                } else {
                    maxChunks = 1;
                }
            }
            try {
                dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resume, maxChunks);
                if (StringUtils.containsIgnoreCase(dl.getConnection().getContentType(), "json") || StringUtils.containsIgnoreCase(dl.getConnection().getContentType(), "text")) {
                    br.followConnection(true);
                    String errorMessage = new Regex(br.getRequest().getUrl(), "\\!%20Error%20Code:([^\\&]+)").getMatch(0);
                    String errDesc = "Unknown error";
                    try {
                        errDesc = UrlQuery.parse(br.getRequest().getUrl()).getDecoded("ErrDesc");
                    } catch (final Throwable e) {
                    }
                    if (dl.getConnection().getResponseCode() == 500 && br.containsHTML("<title>[^<]*Error[^<]*</title>")) {
                        // throw new PluginException(LinkStatus.ERROR_RETRY, ErrDesc == null ? "Server Error" : ErrDesc,
                        // PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
                        int requests = ((Number) link.getProperty("retryRequests", 0)).intValue() + 1;
                        link.setProperty("retryRequests", requests);
                        int waitTime = 0;
                        switch (requests) {
                        case 1:
                            waitTime = 0;
                            break;
                        case 2:
                            waitTime = 3;
                            break;
                        case 3:
                            waitTime = 5;
                            break;
                        case 4:
                            waitTime = 15;
                            break;
                        case 5:
                            waitTime = 30;
                            break;
                        default:
                            waitTime = 3;
                            break;
                        }
                        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, errDesc == null ? "Server Error" : errDesc, waitTime * 60 * 1000l + 1);
                    } else {
                        mhm.handleErrorGeneric(account, this.getDownloadLink(), "dllinknofile1", 50, 5 * 60 * 1000l);
                    }
                }
                if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                    br.followConnection(true);
                    mhm.handleErrorGeneric(account, this.getDownloadLink(), "dllinknofile12", 50, 5 * 60 * 1000l);
                }
                dl.startDownload();
            } catch (PluginException e) {
                if ("Server: Redirect".equals(e.getMessage())) {
                    // the server often returns errors on multichunk connections. I aggreed with the admin to auto reduce chunk count to 1
                    // if we detect this
                    logger.severe("Server error with multichunk loading. Limiting Link to 1 chunk");
                    link.setChunks(1);
                    throw e;
                } else {
                    throw e;
                }
            }
            return;
        }
        final String errorMsg = downloadResponse.get("ErrorMSG") != null ? String.valueOf(downloadResponse.get("ErrorMSG")) : null;
        if (errorMsg != null) {
            if (StringUtils.containsIgnoreCase(errorMsg, "Error verifying api key")) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, errorMsg, PluginException.VALUE_ID_PREMIUM_DISABLE);
            } else if (StringUtils.containsIgnoreCase(errorMsg, "Could not find a customer with those credentials")) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, errorMsg, PluginException.VALUE_ID_PREMIUM_DISABLE);
            } else if (StringUtils.containsIgnoreCase(errorMsg, "Account expired")) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, errorMsg, PluginException.VALUE_ID_PREMIUM_DISABLE);
            } else if (StringUtils.containsIgnoreCase(errorMsg, "Customer reached daily limit for current hoster")) {
                mhm.putError(account, link, 5 * 60 * 1000l, errorMsg);
            } else if (StringUtils.containsIgnoreCase(errorMsg, "Accounts are maxed out for current hoster")) {
                mhm.putError(account, link, 5 * 60 * 1000l, errorMsg);
            } else if (StringUtils.containsIgnoreCase(errorMsg, "Downloads blocked until")) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, errorMsg, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            } else if (StringUtils.containsIgnoreCase(errorMsg, "Hoster is not supported")) {
                mhm.putError(account, link, 5 * 60 * 1000l, errorMsg);
            }
        } else {
            final Number expiryDate = (Number) downloadResponse.get("expirydate");
            if (expiryDate != null && expiryDate.longValue() < System.currentTimeMillis()) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, "Account expired", PluginException.VALUE_ID_PREMIUM_DISABLE);
            }
        }
        mhm.handleErrorGeneric(account, this.getDownloadLink(), "unknowndlerror", 50, 5 * 60 * 1000l);
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
        link.removeProperty("retryRequests");
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.MULTIHOST };
    }
}
