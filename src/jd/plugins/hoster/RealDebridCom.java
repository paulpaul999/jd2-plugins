//    jDownloader - Downloadmanager
//    Copyright (C) 2013  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.appwork.exceptions.WTFException;
import org.appwork.storage.JSonMapperException;
import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.uio.InputDialogInterface;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Application;
import org.appwork.utils.Exceptions;
import org.appwork.utils.StringUtils;
import org.appwork.utils.Time;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.appwork.utils.swing.dialog.DialogCanceledException;
import org.appwork.utils.swing.dialog.DialogClosedException;
import org.appwork.utils.swing.dialog.InputDialog;
import org.jdownloader.captcha.v2.AbstractResponse;
import org.jdownloader.captcha.v2.ChallengeResponseController;
import org.jdownloader.captcha.v2.ChallengeSolver;
import org.jdownloader.captcha.v2.challenge.oauth.AccountLoginOAuthChallenge;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.captcha.v2.solver.service.BrowserSolverService;
import org.jdownloader.captcha.v2.solverjob.SolverJob;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.plugins.components.realDebridCom.RealDebridComConfig;
import org.jdownloader.plugins.components.realDebridCom.api.Error;
import org.jdownloader.plugins.components.realDebridCom.api.json.CheckLinkResponse;
import org.jdownloader.plugins.components.realDebridCom.api.json.ClientSecret;
import org.jdownloader.plugins.components.realDebridCom.api.json.CodeResponse;
import org.jdownloader.plugins.components.realDebridCom.api.json.ErrorResponse;
import org.jdownloader.plugins.components.realDebridCom.api.json.HostsResponse;
import org.jdownloader.plugins.components.realDebridCom.api.json.TokenResponse;
import org.jdownloader.plugins.components.realDebridCom.api.json.UnrestrictLinkResponse;
import org.jdownloader.plugins.components.realDebridCom.api.json.UserResponse;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.plugins.controller.LazyPlugin;
import org.jdownloader.translate._JDT;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.SubConfiguration;
import jd.controlling.AccountController;
import jd.controlling.captcha.SkipException;
import jd.http.Browser;
import jd.http.Request;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountUnavailableException;
import jd.plugins.CaptchaException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.MultiHosterManagement;
import jd.plugins.download.DownloadLinkDownloadable;
import jd.plugins.download.HashInfo;

@HostPlugin(revision = "$Revision: 48479 $", interfaceVersion = 3, names = { "real-debrid.com" }, urls = { "https?://(?:\\w+(?:\\.download)?\\.)?(?:real\\-debrid\\.com|rdb\\.so|rdeb\\.io)/dl?/\\w+(?:/.+)?" })
public class RealDebridCom extends PluginForHost {
    private static final String CLIENT_SECRET_KEY = "client_secret";
    private static final String CLIENT_ID_KEY     = "client_id";
    private static final String CLIENT_SECRET     = "CLIENT_SECRET";
    private static final String TOKEN             = "TOKEN";
    private static final String AUTHORIZATION     = "Authorization";

    private static class APIException extends Exception {
        private final URLConnectionAdapter connection;

        public APIException(URLConnectionAdapter connection, Error error, String msg) {
            super(msg);
            this.error = error;
            this.connection = connection;
        }

        public URLConnectionAdapter getConnection() {
            return connection;
        }

        private final Error error;

        public Error getError() {
            return error;
        }
    }

    private static MultiHosterManagement mhm               = new MultiHosterManagement("real-debrid.com");
    private static final String          API               = "https://api.real-debrid.com";
    private static final String          CLIENT_ID         = "NJ26PAPGHWGZY";
    private static AtomicInteger         MAX_DOWNLOADS     = new AtomicInteger(Integer.MAX_VALUE);
    private static AtomicInteger         RUNNING_DOWNLOADS = new AtomicInteger(0);
    private final String                 mName             = "real-debrid.com";
    private final String                 mProt             = "https://";
    private Browser                      apiBrowser;
    private TokenResponse                currentToken      = null;

    public RealDebridCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(mProt + mName + "/");
        Browser.setRequestIntervalLimitGlobal(getHost(), 500);
        Browser.setRequestIntervalLimitGlobal("rdb.so", 500);
        Browser.setRequestIntervalLimitGlobal("rdeb.io", 500);
    }

    private <T> T callRestAPI(final Account account, String method, UrlQuery query, TypeRef<T> type) throws IOException, PluginException, APIException, InterruptedException {
        if (account == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        ensureAPIBrowser();
        TokenResponse token = login(account, false);
        try {
            final T ret = callRestAPIInternal(token, "https://api.real-debrid.com/rest/1.0" + method, query, type);
            this.currentToken = token;
            return ret;
        } catch (final APIException e) {
            switch (e.getError()) {
            case BAD_LOGIN:
            case BAD_TOKEN:
                try {
                    // refresh Token
                    token = login(account, true);
                    final T ret = callRestAPIInternal(token, "https://api.real-debrid.com/rest/1.0" + method, query, type);
                    this.currentToken = token;
                    return ret;
                } catch (final APIException e2) {
                    throw handleAPIException(Exceptions.addSuppressed(e2, e), account, null);
                }
            default:
                throw handleAPIException(e, account, null);
            }
        }
    }

    protected synchronized <T> T callRestAPIInternal(TokenResponse token, String url, UrlQuery query, TypeRef<T> type) throws IOException, PluginException, APIException {
        if (token != null) {
            apiBrowser.getHeaders().put(AUTHORIZATION, "Bearer " + token.getAccess_token());
        }
        final Request request;
        if (query != null) {
            request = apiBrowser.createPostRequest(url, query);
        } else {
            request = apiBrowser.createGetRequest(url);
        }
        final String json = apiBrowser.getPage(request);
        this.checkErrorsWebsite(apiBrowser);
        if (request.getHttpConnection().getResponseCode() != 200) {
            if (json.trim().startsWith("{")) {
                final ErrorResponse errorResponse = restoreFromString(json, new TypeRef<ErrorResponse>(ErrorResponse.class) {
                });
                Error errorCode = Error.getByCode(errorResponse.getError_code());
                if (Error.UNKNOWN.equals(errorCode) && request.getHttpConnection().getResponseCode() == 403) {
                    errorCode = Error.BAD_TOKEN;
                }
                throw new APIException(request.getHttpConnection(), errorCode, errorResponse.getError());
            } else {
                throw new IOException("Unexpected Response: " + json);
            }
        }
        try {
            return restoreFromString(json, type);
        } catch (final JSonMapperException e) {
            throw Exceptions.addSuppressed(new AccountUnavailableException("Bad API response", 5 * 60 * 1000l), e);
        }
    }

    private void ensureAPIBrowser() {
        if (apiBrowser == null) {
            apiBrowser = br.cloneBrowser();
            apiBrowser.setAllowedResponseCodes(-1);
        }
    }

    @Override
    public boolean canHandle(DownloadLink downloadLink, Account account) throws Exception {
        if (isDirectRealDBUrl(downloadLink)) {
            // generated links do not require an account to download
            return true;
        } else if (account == null) {
            // no non account handleMultiHost support.
            return false;
        } else {
            mhm.runCheck(account, downloadLink);
            return true;
        }
    }

    private APIException handleAPIException(final APIException e, final Account account, final DownloadLink link) throws APIException, PluginException {
        switch (e.getError()) {
        case IP_ADRESS_FORBIDDEN:
            if (account != null) {
                throw new AccountUnavailableException(e, e.getMessage(), 60 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, e.getMessage(), 60 * 60 * 1000l, e);
            }
        case BAD_LOGIN:
        case BAD_TOKEN:
            throw new AccountInvalidException(e, e.getMessage());
        case SERVICE_UNAVAILABLE:
            if (account != null) {
                throw new AccountUnavailableException(e, e.getMessage(), 1 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, null, 1 * 60 * 1000l, e);
            }
        case ACCOUNT_LOCKED:
        case ACCOUNT_NOT_ACTIVATED:
            if (account != null) {
                throw new AccountInvalidException(e, e.getMessage());
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, null, e);
            }
        default:
            throw e;
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        account.setConcurrentUsePossible(true);
        account.setMaxSimultanDownloads(-1);
        final UserResponse user = callRestAPI(account, "/user", null, UserResponse.TYPE);
        if (StringUtils.equalsIgnoreCase("premium", user.getType())) {
            account.setType(AccountType.PREMIUM);
            ai.setValidUntil(TimeFormatter.getTimestampByGregorianTime(user.getExpiration()));
            final HashMap<String, HostsResponse> hosts = callRestAPI(account, "/hosts", null, new TypeRef<HashMap<String, HostsResponse>>() {
            });
            final ArrayList<String> supportedHosts = new ArrayList<String>();
            for (Entry<String, HostsResponse> es : hosts.entrySet()) {
                if (StringUtils.isNotEmpty(es.getKey())) {
                    supportedHosts.add(es.getKey());
                }
            }
            ai.setMultiHostSupport(this, supportedHosts);
        } else {
            account.setType(AccountType.FREE);
            /* 2020-08-11: Free accounts cannot be used to download anything */
            ai.setMultiHostSupport(this, null);
            ;
            ai.setTrafficLeft(0);
            ai.setExpired(true);
        }
        return ai;
    }

    @Override
    public String getAGBLink() {
        return mProt + mName + "/terms";
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.MULTIHOST };
    }

    @Override
    public int getMaxSimultanDownload(DownloadLink link, final Account account) {
        return MAX_DOWNLOADS.get();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return MAX_DOWNLOADS.get();
    }

    private void handleDL(final Account acc, final DownloadLink link, final String dllink, final UnrestrictLinkResponse linkresp) throws Exception {
        // real debrid connections are flakey at times! Do this instead of repeating download steps.
        final int maxChunks;
        if (linkresp == null) {
            maxChunks = 0;
        } else {
            if (linkresp.getChunks() <= 0 || PluginJsonConfig.get(RealDebridComConfig.class).isIgnoreServerSideChunksNum()) {
                maxChunks = 0;
            } else {
                maxChunks = -(int) linkresp.getChunks();
            }
        }
        final boolean useSSL = PluginJsonConfig.get(RealDebridComConfig.class).isUseSSLForDownload();
        final String downloadLink;
        if (useSSL) {
            downloadLink = dllink.replaceFirst("^http:", "https:");
        } else {
            downloadLink = dllink.replaceFirst("^https:", "http:");
        }
        final String host = Browser.getHost(downloadLink);
        final DownloadLinkDownloadable downloadLinkDownloadable = new DownloadLinkDownloadable(link) {
            @Override
            public HashInfo getHashInfo() {
                if (linkresp == null || linkresp.getCrc() == 1) {
                    return super.getHashInfo();
                } else {
                    return null;
                }
            }

            @Override
            public long getVerifiedFileSize() {
                if (linkresp == null || linkresp.getCrc() == 1) {
                    return super.getVerifiedFileSize();
                } else {
                    return -1;
                }
            }

            @Override
            public String getHost() {
                return host;
            }
        };
        final boolean resume = true;
        final Browser br2 = br.cloneBrowser();
        br2.setAllowedResponseCodes(new int[0]);
        try {
            dl = new jd.plugins.BrowserAdapter().openDownload(br2, downloadLinkDownloadable, br2.createGetRequest(downloadLink), resume, resume ? maxChunks : 1);
            if (this.looksLikeDownloadableContent(dl.getConnection())) {
                if (dl.getConnection().getLongContentLength() == 0) {
                    dl.getConnection().disconnect();
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "RD still processing download", 5 * 60 * 1000l);
                }
                /* We have a file, let's download it. */
                try {
                    RUNNING_DOWNLOADS.incrementAndGet();
                    dl.startDownload();
                } finally {
                    if (RUNNING_DOWNLOADS.decrementAndGet() == 0) {
                        MAX_DOWNLOADS.set(Integer.MAX_VALUE);
                    }
                }
            } else {
                br2.followConnection(true);
                this.br = br2;// required for error handling outside this method
                if (br.containsHTML("(?i)An error occurred while generating a premium link")) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server says 'An error occurred while generating a premium link'", 3 * 60 * 1000l);
                } else if (br.containsHTML("(?i)currently downloading and this hoster is limited")) {
                    // You can not download this file because you already have download(s) currently downloading and this hoster is limited.
                    mhm.putError(acc, link, 1 * 60 * 1000l, "You can not download this file because you already have download(s) currently downloading and this hoster is limited.");
                } else if (link.getHost().equals(this.getHost())) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                } else {
                    /*
                     * TODO: <div class="alert alert-danger">You are not allowed to download this file !<br/>Your current IP adress is:
                     * xx.yy.zz.yy</div>
                     */
                    /* Do not throw plugin defect in multihoster mode */
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown download error", 3 * 60 * 1000l);
                }
            }
        } finally {
            try {
                dl.getConnection().disconnect();
            } catch (final Throwable t) {
            }
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        showMessage(link, "Task 1: Check URL validity!");
        final AvailableStatus status = requestFileInformation(link);
        if (AvailableStatus.UNCHECKABLE.equals(status)) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 60 * 1000l);
        }
        showMessage(link, "Task 2: Download begins!");
        handleDL(null, link, link.getPluginPatternMatcher(), null);
    }

    private AvailableStatus check(final Account account, DownloadLink link) throws Exception {
        if (account != null && !isDirectRealDBUrl(link)) {
            final String dllink = link.getDefaultPlugin().buildExternalDownloadURL(link, this);
            String downloadPassword = link.getDownloadPassword();
            if (downloadPassword == null) {
                downloadPassword = "";
            }
            final CheckLinkResponse checkresp;
            try {
                checkresp = callRestAPI(account, "/unrestrict/check", new UrlQuery().append("link", dllink, true).append("password", downloadPassword, true), CheckLinkResponse.TYPE);
            } catch (final APIException e) {
                switch (e.getError()) {
                case FILE_UNAVAILABLE:
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND, null, e);
                default:
                    logger.log(e);
                    return AvailableStatus.UNCHECKABLE;
                }
            }
            if (checkresp != null) {
                if (StringUtils.isEmpty(checkresp.getHost()) || (checkresp.getFilename() == null && checkresp.getFilesize() == 0)) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                if (checkresp.getFilesize() > 0) {
                    link.setVerifiedFileSize(checkresp.getFilesize());
                }
                if (checkresp.getFilename() != null) {
                    link.setFinalFileName(checkresp.getFilename());
                }
                return AvailableStatus.TRUE;
            } else {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
        }
        return AvailableStatus.UNCHECKABLE;
    }

    @Override
    public boolean hasConfig() {
        return true;
    }

    public void handleMultiHost(final DownloadLink link, final Account account) throws Exception {
        handleMultiHost(0, link, account);
    }

    /** no override to keep plugin compatible to old stable */
    public void handleMultiHost(final int startTaskIndex, final DownloadLink link, final Account account) throws Exception {
        try {
            mhm.runCheck(account, link);
            prepBrowser(br);
            showMessage(link, "Task " + (startTaskIndex + 1) + ": Generating Link");
            /* request Download */
            final String dllink = link.getDefaultPlugin().buildExternalDownloadURL(link, this);
            String downloadPassword = link.getDownloadPassword();
            if (downloadPassword == null) {
                downloadPassword = "";
            }
            final UnrestrictLinkResponse linkresp = callRestAPI(account, "/unrestrict/link", new UrlQuery().append("link", dllink, true).append("password", downloadPassword, true), UnrestrictLinkResponse.TYPE);
            final String genLnk = linkresp.getDownload();
            if (StringUtils.isEmpty(genLnk) || !genLnk.startsWith("http")) {
                throw new PluginException(LinkStatus.ERROR_FATAL, "Unsupported protocol");
            }
            showMessage(link, "Task " + (startTaskIndex + 2) + ": Download begins!");
            try {
                handleDL(account, link, genLnk, linkresp);
            } catch (final PluginException e) {
                logger.log(e);
                try {
                    dl.getConnection().disconnect();
                } catch (final Throwable ignore) {
                }
                if (br.containsHTML("An error occurr?ed while generating a premium link, please contact an Administrator")) {
                    logger.info("Error while generating premium link, removing host from supported list");
                    mhm.handleErrorGeneric(account, link, "An error occured while generating a premium link, please contact an Administrator", 50, 5 * 60 * 1000l);
                } else if (br.containsHTML("(?i)An error occurr?ed while attempting to download the file.")) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 10 * 60 * 1000l);
                } else if (br.containsHTML("Your code is not or no longer valid")) {
                    if (false && !currentToken._isVerified()) {
                        // seems not connected to current token
                        synchronized (account) {
                            final String tokenJSon = account.getStringProperty(TOKEN, null);
                            final TokenResponse existingToken = tokenJSon != null ? restoreFromString(tokenJSon, TokenResponse.TYPE) : null;
                            if (existingToken != null && StringUtils.equals(currentToken.getAccess_token(), existingToken.getAccess_token()) && StringUtils.equals(currentToken.getRefresh_token(), existingToken.getRefresh_token())) {
                                currentToken.setRefresh(true);
                                account.setProperty(TOKEN, JSonStorage.serializeToJson(currentToken));
                                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 1 * 60 * 1000l);
                            }
                        }
                    }
                    mhm.handleErrorGeneric(account, link, "Your code is not or no longer valid", 50, 5 * 60 * 1000l);
                } else if (br.containsHTML("(?i)You can not download this file because you have exceeded your traffic on this hoster")) {
                    mhm.putError(account, link, 5 * 60 * 1000l, "You can not download this file because you have exceeded your traffic on this hoster");
                } else if (br.getHttpConnection() != null && br.getHttpConnection().getResponseCode() == 404 && br.containsHTML("(?i)>\\s*The download server is down or banned from our server")) {
                    // <div class="alert alert-danger">An error occurred while read your file on the remote host ! Timeout of 60s exceeded
                    // !<br/>The download server is down or banned from our server, please contact an Administrator with these following
                    // informations :<br/><br/>Link: http://abc <br/>Server: 130<br/>Code: HASH</div>
                    mhm.putError(account, link, 5 * 60 * 1000l, "The download server is down or banned from our server");
                } else {
                    throw e;
                }
            }
        } catch (final APIException e) {
            switch (e.getError()) {
            case FILE_UNAVAILABLE:
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, _JDT.T.downloadlink_status_error_hoster_temp_unavailable(), 10 * 60 * 1000l, e);
            case UNSUPPORTED_HOSTER:
                // logger.severe("Unsupported Hoster: " + link.getDefaultPlugin().buildExternalDownloadURL(link, this));
                // throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unsupported Hoster: " + link.getHost());
                // 20170501-raztoki not unsupported url format. When this error is returned by rd, we should just jump to the next download
                // candidate, as another link from the same host might work.
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, e.getMessage(), e);
            case HOSTER_TEMP_UNAVAILABLE:
            case HOSTER_IN_MAINTENANCE:
            case HOSTER_LIMIT_REACHED:
            case HOSTER_PREMIUM_ONLY:
            case TRAFFIC_EXHAUSTED:
                mhm.putError(account, link, 5 * 60 * 1000l, "Traffic exhausted");
            case FAIR_USAGE_LIMIT_REACHED:
                mhm.putError(account, link, 30 * 60 * 1000l, "Fair usage limit reahed");
            default:
                mhm.handleErrorGeneric(account, link, e.getMessage(), 50, 1 * 60 * 1000l);
            }
        }
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        showMessage(link, "Task 1: Check URL validity!");
        final AvailableStatus status = requestFileInformation(link);
        if (AvailableStatus.UNCHECKABLE.equals(status)) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 60 * 1000l);
        }
        if (isDirectRealDBUrl(link)) {
            showMessage(link, "Task 2: Download begins!");
            handleDL(account, link, link.getPluginPatternMatcher(), null);
        } else {
            handleMultiHost(2, link, account);
        }
    }

    @Override
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        return false;
    }

    public ClientSecret checkCredentials(CodeResponse code) throws Exception {
        return callRestAPIInternal(null, API + "/oauth/v2/device/credentials?client_id=" + Encoding.urlEncode(CLIENT_ID) + "&code=" + Encoding.urlEncode(code.getDevice_code()), null, ClientSecret.TYPE);
    }

    private TokenResponse login(Account account, boolean force) throws PluginException, IOException, APIException, InterruptedException {
        synchronized (account) {
            try {
                // first try to use the stored token
                String tokenJSon = account.getStringProperty(TOKEN);
                if (!force) {
                    if (StringUtils.isNotEmpty(tokenJSon)) {
                        final TokenResponse existingToken = restoreFromString(tokenJSon, new TypeRef<TokenResponse>(TokenResponse.class) {
                        });
                        // ensure that the token is at elast 5 minutes valid
                        final long expireTime = existingToken.getExpires_in() * 1000 + existingToken.getCreateTime();
                        final long now = System.currentTimeMillis();
                        if (!existingToken.isRefresh() && (expireTime - 5 * 60 * 1000l) > now) {
                            existingToken._setVerified(false);
                            return existingToken;
                        }
                    }
                }
                // token invalid, forcerefresh active or token expired.
                // Try to refresh the token
                tokenJSon = account.getStringProperty(TOKEN);
                final String clientSecretJson = account.getStringProperty(CLIENT_SECRET);
                if (StringUtils.isNotEmpty(tokenJSon) && StringUtils.isNotEmpty(clientSecretJson)) {
                    final TokenResponse existingToken = restoreFromString(tokenJSon, TokenResponse.TYPE);
                    final ClientSecret clientSecret = restoreFromString(clientSecretJson, ClientSecret.TYPE);
                    final String tokenResponseJson = br.postPage(API + "/oauth/v2/token", new UrlQuery().append(CLIENT_ID_KEY, clientSecret.getClient_id(), true).append(CLIENT_SECRET_KEY, clientSecret.getClient_secret(), true).append("code", existingToken.getRefresh_token(), true).append("grant_type", "http://oauth.net/grant_type/device/1.0", true));
                    this.checkErrorsWebsite(br);
                    final TokenResponse newToken = restoreFromString(tokenResponseJson, TokenResponse.TYPE);
                    if (newToken.validate()) {
                        tokenJSon = JSonStorage.serializeToJson(newToken);
                        account.setProperty(TOKEN, tokenJSon);
                        newToken._setVerified(true);
                        return newToken;
                    }
                }
                // Could not refresh the token. login using username and password
                br.setCookiesExclusive(true);
                prepBrowser(br);
                br.clearCookies(API);
                final Browser autoSolveBr = br.cloneBrowser();
                final String responseJson = br.getPage(API + "/oauth/v2/device/code?client_id=" + CLIENT_ID + "&new_credentials=yes");
                this.checkErrorsWebsite(br);
                final CodeResponse code = restoreFromString(responseJson, new TypeRef<CodeResponse>(CodeResponse.class) {
                });
                ensureAPIBrowser();
                final AtomicReference<ClientSecret> clientSecretResult = new AtomicReference<ClientSecret>(null);
                final AtomicBoolean loginsInvalid = new AtomicBoolean(false);
                final AccountLoginOAuthChallenge challenge = new AccountLoginOAuthChallenge(getHost(), null, account, code.getDirect_verification_url()) {
                    private volatile long lastValidation = -1;

                    @Override
                    public Plugin getPlugin() {
                        return RealDebridCom.this;
                    }

                    @Override
                    public void poll(SolverJob<Boolean> job) {
                        if (Time.systemIndependentCurrentJVMTimeMillis() - lastValidation >= code.getInterval() * 1000) {
                            lastValidation = Time.systemIndependentCurrentJVMTimeMillis();
                            try {
                                final ClientSecret clientSecret = checkCredentials(code);
                                if (clientSecret != null) {
                                    clientSecretResult.set(clientSecret);
                                    job.addAnswer(new AbstractResponse<Boolean>(this, ChallengeSolver.EXTERN, 100, true));
                                }
                            } catch (Throwable e) {
                                logger.log(e);
                            }
                        }
                    }

                    private final boolean isInvalid(Browser br) {
                        // Website no longer shows this information? 10.09.2020
                        return br.containsHTML("Your login informations are incorrect") || (Application.isHeadless() && br.containsHTML("The validity period of your password has been exceeded"));
                    }

                    private final boolean isAllowed(Browser br) {
                        return br.containsHTML("Application allowed, you can close this page");
                    }

                    private final boolean is2FARequired(String html) {
                        return StringUtils.contains(html, "A temporary code has been sent to your email address and is required");
                    }

                    private final Boolean check(SolverJob<Boolean> job, Browser br) throws Exception {
                        if (isInvalid(autoSolveBr)) {
                            loginsInvalid.set(true);
                            job.addAnswer(new AbstractResponse<Boolean>(this, this, 100, false));
                            return false;
                        } else if (isAllowed(autoSolveBr)) {
                            final ClientSecret clientSecret = checkCredentials(code);
                            if (clientSecret != null) {
                                clientSecretResult.set(clientSecret);
                                job.addAnswer(new AbstractResponse<Boolean>(this, this, 100, true));
                                return true;
                            } else {
                                logger.info("No ClientSecret?!");
                            }
                        }
                        return null;
                    }

                    protected Form getLoginForm(Browser br) throws PluginException {
                        final Form loginForm = br.getFormbyActionRegex("/authorize\\?.+");
                        if (loginForm == null) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                        } else {
                            return loginForm;
                        }
                    }

                    protected Form handleLoginForm(Browser br, Form loginForm) throws PluginException, InterruptedException, DialogClosedException, DialogCanceledException {
                        if (loginForm.containsHTML("g-recaptcha")) {
                            logger.info("Login requires Recaptcha");
                            final DownloadLink dummyLink = new DownloadLink(RealDebridCom.this, "Account:" + getAccount().getUser(), getHost(), "https://real-debrid.com", true);
                            RealDebridCom.this.setDownloadLink(dummyLink);
                            final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(RealDebridCom.this, br).getToken();
                            loginForm.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                        }
                        loginForm.getInputField("p").setValue(Encoding.urlEncode(getAccount().getPass()));
                        loginForm.getInputField("u").setValue(Encoding.urlEncode(getAccount().getUser()));
                        if (is2FARequired(loginForm.getHtmlCode())) {
                            if (Application.isHeadless() || !BrowserSolverService.getInstance().isOpenBrowserSupported()) {
                                String text = loginForm.getRegex("(A temporary.*?)\\s*</").getMatch(0);
                                if (StringUtils.isEmpty(text)) {
                                    text = "A temporary code has been sent to your email address and is required to complete the login:";
                                }
                                final InputDialog mfaDialog = new InputDialog(UIOManager.LOGIC_COUNTDOWN, "Account is 2fa protected!", text, null, null, _GUI.T.lit_continue(), null);
                                mfaDialog.setTimeout(5 * 60 * 1000);
                                final InputDialogInterface handler = UIOManager.I().show(InputDialogInterface.class, mfaDialog);
                                handler.throwCloseExceptions();
                                loginForm.getInputField("pa").setValue(Encoding.urlEncode(mfaDialog.getText()));
                            } else {
                                logger.info("Skip autoSolveChallenge: 2fa required");
                                return null;
                            }
                        }
                        return loginForm;
                    }

                    private Boolean handleLoginForm(SolverJob<Boolean> job, Browser br) throws Exception {
                        Form loginForm = handleLoginForm(br, getLoginForm(br));
                        if (loginForm == null) {
                            return Boolean.FALSE;
                        }
                        br.submitForm(loginForm);
                        Boolean result = check(job, br);
                        if (result != null) {
                            return result;
                        } else if (is2FARequired(br.toString())) {
                            loginForm = handleLoginForm(br, getLoginForm(br));
                            if (loginForm == null) {
                                return Boolean.FALSE;
                            }
                            br.submitForm(loginForm);
                            result = check(job, br);
                            if (result != null) {
                                return result;
                            }
                        }
                        return null;
                    }

                    private final boolean handleAutoSolveChallenge(SolverJob<Boolean> job) {
                        try {
                            final Account acc = getAccount();
                            if (!StringUtils.isAllNotEmpty(acc.getUser(), acc.getPass())) {
                                logger.info("Skip autoSolveChallenge: user/pass is missing");
                                return false;
                            }
                            final String verificationUrl = getUrl();
                            autoSolveBr.clearCookies(verificationUrl);
                            autoSolveBr.getPage(verificationUrl);
                            for (int index = 0; index < 3; index++) {
                                // (0)no captcha, (1)captcha, (2) maybe another captcha
                                final Boolean result = handleLoginForm(job, autoSolveBr);
                                if (result != null) {
                                    return result.booleanValue();
                                } else {
                                    final Form allow = autoSolveBr.getFormBySubmitvalue("Allow");
                                    if (allow != null) {
                                        allow.setPreferredSubmit("Allow");
                                        autoSolveBr.submitForm(allow);
                                        final ClientSecret clientSecret = checkCredentials(code);
                                        if (clientSecret != null) {
                                            clientSecretResult.set(clientSecret);
                                            job.addAnswer(new AbstractResponse<Boolean>(this, this, 100, true));
                                            return true;
                                        } else {
                                            logger.info("No ClientSecret?!");
                                            return false;
                                        }
                                    }
                                }
                            }
                        } catch (CaptchaException e) {
                            logger.log(e);
                            job.addAnswer(new AbstractResponse<Boolean>(this, this, 100, false));
                        } catch (PluginException e) {
                            logger.log(e);
                            job.addAnswer(new AbstractResponse<Boolean>(this, this, 100, false));
                        } catch (InterruptedException e) {
                            logger.log(e);
                            job.addAnswer(new AbstractResponse<Boolean>(this, this, 100, false));
                        } catch (Throwable e) {
                            logger.log(e);
                        }
                        return false;
                    }

                    @Override
                    public boolean autoSolveChallenge(SolverJob<Boolean> job) {
                        final boolean ret = handleAutoSolveChallenge(job);
                        logger.info("autoSolveChallenge:" + ret);
                        return ret;
                    }
                };
                challenge.setTimeout(5 * 60 * 1000);
                try {
                    ChallengeResponseController.getInstance().handle(challenge);
                } catch (SkipException e) {
                    logger.log(e);
                }
                final ClientSecret clientSecret = clientSecretResult.get();
                if (clientSecret == null) {
                    if (loginsInvalid.get()) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "Your login informations are incorrect", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "OAuth Failed", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                final String tokenResponseJson = br.postPage(API + "/oauth/v2/token", new UrlQuery().append(CLIENT_ID_KEY, clientSecret.getClient_id(), true).append(CLIENT_SECRET_KEY, clientSecret.getClient_secret(), true).append("code", code.getDevice_code(), true).append("grant_type", "http://oauth.net/grant_type/device/1.0", true));
                this.checkErrorsWebsite(br);
                final TokenResponse newToken = restoreFromString(tokenResponseJson, new TypeRef<TokenResponse>(TokenResponse.class) {
                });
                if (newToken.validate()) {
                    final UserResponse user = callRestAPIInternal(newToken, "https://api.real-debrid.com/rest/1.0" + "/user", null, UserResponse.TYPE);
                    if (StringUtils.isEmpty(account.getUser())) {
                        if (StringUtils.isNotEmpty(user.getUsername())) {
                            account.setUser(user.getUsername());
                        } else {
                            account.setUser(user.getEmail());
                        }
                    }
                    if (!StringUtils.equalsIgnoreCase(account.getUser(), user.getEmail()) && !StringUtils.equalsIgnoreCase(account.getUser(), user.getUsername())) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "User Mismatch. You try to add the account " + account.getUser() + "\r\nBut in your browser you are logged in as " + user.getUsername() + "\r\nPlease make sure that there is no username mismatch!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        tokenJSon = JSonStorage.serializeToJson(newToken);
                        account.setProperty(TOKEN, JSonStorage.serializeToJson(newToken));
                        account.setProperty(CLIENT_SECRET, JSonStorage.serializeToJson(clientSecret));
                        newToken._setVerified(true);
                        return newToken;
                    }
                } else {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, "Unknown Error", PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
            } catch (PluginException e) {
                throw e;
            }
        }
    }

    private Browser prepBrowser(Browser prepBr) {
        // define custom browser headers and language settings.
        prepBr.getHeaders().put("Accept-Language", "en-gb, en;q=0.9");
        prepBr.setCookie(mProt + mName, "lang", "en");
        prepBr.getHeaders().put("User-Agent", "JDownloader");
        prepBr.setCustomCharset("utf-8");
        prepBr.setConnectTimeout(2 * 60 * 1000);
        prepBr.setReadTimeout(2 * 60 * 1000);
        prepBr.setFollowRedirects(true);
        prepBr.setAllowedResponseCodes(new int[] { 504 });
        return prepBr;
    }

    /**
     * Checks for errors in html code
     *
     * @throws AccountUnavailableException
     */
    private void checkErrorsWebsite(final Browser br) throws AccountUnavailableException {
        if (br.containsHTML("<title>\\s*Temporarily Down For Maintenance")) {
            throw new AccountUnavailableException("Under maintenance", 5 * 60 * 1000l);
        }
    }

    private boolean isDirectRealDBUrl(DownloadLink dl) {
        final String url = dl.getDownloadURL();
        if (url.contains("download.") || url.matches("https?://\\w+\\.(rdb\\.so|rdeb\\.io)/dl?/\\w+(/.+)?")) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        prepBrowser(br);
        if (isDirectRealDBUrl(link)) {
            URLConnectionAdapter con = null;
            try {
                final Browser brc = br.cloneBrowser();
                brc.setFollowRedirects(true);
                con = brc.openGetConnection(link.getDownloadURL());
                if (looksLikeDownloadableContent(con)) {
                    if (link.getFinalFileName() == null) {
                        link.setFinalFileName(getFileNameFromHeader(con));
                    }
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                    link.setAvailable(true);
                    return AvailableStatus.TRUE;
                } else {
                    brc.followConnection(true);
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            } catch (final PluginException e) {
                throw e;
            } catch (final Throwable e) {
                logger.log(e);
                return AvailableStatus.UNCHECKABLE;
            } finally {
                if (con != null) {
                    try {
                        /* make sure we close connection */
                        con.disconnect();
                    } catch (final Throwable e) {
                    }
                }
            }
        } else {
            final List<Account> accounts = AccountController.getInstance().getValidAccounts(getHost());
            if (accounts != null && accounts.size() > 0) {
                return check(accounts.get(0), link);
            } else {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
            }
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public ConfigContainer getConfig() {
        throw new WTFException("Not implemented");
    }

    @Override
    public SubConfiguration getPluginConfig() {
        throw new WTFException("Not implemented");
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return RealDebridComConfig.class;
    }

    private void showMessage(DownloadLink link, String message) {
        link.getLinkStatus().setStatusText(message);
    }
}