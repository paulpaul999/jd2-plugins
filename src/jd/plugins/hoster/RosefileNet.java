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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.AbstractRecaptchaV2;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Cookies;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountRequiredException;
import jd.plugins.AccountUnavailableException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.SiteType.SiteTemplate;

@HostPlugin(revision = "$Revision: 48528 $", interfaceVersion = 3, names = {}, urls = {})
public class RosefileNet extends PluginForHost {
    public RosefileNet(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://rosefile.net/vip.php");
    }

    @Override
    public String getAGBLink() {
        return "https://rosefile.net/";
    }

    private static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "rosefile.net", "rsfile.cc" });
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(?:d/)?([a-z0-9]{10})(/([^/]+)\\.html)?");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        return true;
    }

    public int getMaxChunks(final Account account) {
        return 1;
    }

    /* Connection stuff */
    private static final int FREE_MAXDOWNLOADS            = 1;
    private static final int ACCOUNT_FREE_MAXDOWNLOADS    = 1;
    private static final int ACCOUNT_PREMIUM_MAXDOWNLOADS = Integer.MAX_VALUE;

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
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        if (!link.isNameSet()) {
            /* Fallback */
            link.setName(this.getFID(link));
        }
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(link.getPluginPatternMatcher());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("(?i)404 File does not exist")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* 2021-04-12: Trust filename inside URL. */
        String filename = br.getRegex("(?i)<title>\\s*(.*?)\\s*-\\s*RoseFile\\s*</title>").getMatch(0);
        if (filename == null) {
            /* 2023-05-04 */
            filename = br.getRegex("<h3>([^<]+)</h3>").getMatch(0);
        }
        if (filename == null) {
            /* Try to get filename from URL */
            filename = new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(2);
        }
        if (filename != null) {
            filename = Encoding.htmlDecode(filename).trim();
            link.setName(filename);
        }
        String filesize = br.getRegex("<span class=\"h4\">(\\d+[^<>\"]+)</span>").getMatch(0);
        if (filesize != null) {
            if (!filesize.toLowerCase(Locale.ENGLISH).contains("b")) {
                filesize += "b";
            }
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        } else {
            logger.warning("Failed to find filesize");
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        handleDownload(link, null);
    }

    private void handleDownload(final DownloadLink link, final Account account) throws Exception, PluginException {
        final String directlinkproperty;
        if (account != null) {
            directlinkproperty = "directurl_account_" + account.getType().getLabel();
        } else {
            directlinkproperty = "directurl_free";
        }
        if (!attemptStoredDownloadurlDownload(link, directlinkproperty, this.isResumeable(link, account), this.getMaxChunks(account))) {
            this.requestFileInformation(link);
            if (account != null) {
                this.login(account, false);
                /* Extra check! */
                br.getPage(link.getPluginPatternMatcher());
                if (!this.isLoggedin(br)) {
                    throw new AccountUnavailableException("Session expired?", 30 * 1000l);
                }
            }
            if (br.containsHTML("(?i)>\\s*This file is available for Premium Users only")) {
                throw new AccountRequiredException();
            }
            final String fileURL = br.getURL();
            String internalFileID = br.getRegex("add_ref\\((\\d+)\\);").getMatch(0);
            if (internalFileID == null) {
                /* 2023-10-23 */
                internalFileID = br.getRegex("add_coun\\((\\d+)\\);").getMatch(0);
            }
            if (internalFileID == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final Browser ajax = br.cloneBrowser();
            ajax.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            final boolean looksLikePremiumADownloadWithoutWait = br.containsHTML("load_down");
            if (account != null && looksLikePremiumADownloadWithoutWait) {
                final UrlQuery query = new UrlQuery();
                query.add("action", "check_recaptcha");
                query.add("file_id", internalFileID);
                ajax.postPage("/ajax.php", query);
            } else {
                /** 2021-04-12: Waittime and captcha (required for anonymous downloads in browser) is skippable! */
                ajax.getPage("/ajax.php?action=load_time&ctime=" + System.currentTimeMillis());
                final Map<String, Object> entries = restoreFromString(ajax.getRequest().getHtmlCode(), TypeRef.MAP);
                final int waitSeconds = ((Number) entries.get("waittime_s")).intValue();
                if (waitSeconds > 300) {
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, waitSeconds * 1000l);
                }
                this.sleep(waitSeconds * 1001l, link);
                br.getPage("/d/" + this.getFID(link) + "/" + Encoding.urlEncode(link.getName()) + ".html");
                if (AbstractRecaptchaV2.containsRecaptchaV2Class(br)) {
                    /* New 2023-11-15 */
                    final UrlQuery query = new UrlQuery();
                    query.add("action", "check_recaptcha");
                    query.add("file_id", internalFileID);
                    final String reCaptchav2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
                    query.add("token", Encoding.urlEncode(reCaptchav2Response));
                    ajax.postPage("/ajax.php", query);
                    if (ajax.getRequest().getHtmlCode().equalsIgnoreCase("false")) {
                        /* This should never happen! */
                        throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                    }
                } else {
                    /* Old handling */
                    final String code = getCaptchaCode("/imagecode.php?t=" + System.currentTimeMillis(), link);
                    ajax.postPage("/ajax.php", "action=check_code&code=" + Encoding.urlEncode(code));
                    if (ajax.getRequest().getHtmlCode().equalsIgnoreCase("false")) {
                        throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                    }
                    ajax.postPage("/ajax.php", "action=load_down_addr1&file_id=" + internalFileID);
                }
            }
            String dllink = ajax.getRegex("true\\|<a href=\"([^<>\"]+)").getMatch(0);
            if (dllink == null) {
                dllink = ajax.getRegex("true\\|(http[^<>\"]+)").getMatch(0);
            }
            if (StringUtils.isEmpty(dllink)) {
                logger.warning("Failed to find final downloadurl");
                handleErrors(ajax);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final ArrayList<String> mirrorurls = new ArrayList<String>();
            final String[] mirrors = ajax.getRegex("<a href=\"(https?[^\"]+)").getColumn(0);
            if (mirrors != null && mirrors.length > 0) {
                for (final String mirror : mirrors) {
                    mirrorurls.add(mirror);
                }
            } else {
                mirrorurls.add(dllink);
            }
            for (int i = 0; i < mirrorurls.size(); i++) {
                if (this.isAbort()) {
                    throw new InterruptedException();
                }
                final String directurl = mirrorurls.get(i);
                logger.info("Trying mirror " + i + " | " + directurl);
                br.getHeaders().put("Referer", fileURL);
                try {
                    dl = jd.plugins.BrowserAdapter.openDownload(br, link, directurl, this.isResumeable(link, account), this.getMaxChunks(account));
                    if (this.looksLikeDownloadableContent(dl.getConnection())) {
                        break;
                    }
                } catch (final Exception e) {
                    if (i == mirrorurls.size() - 1) {
                        /* Last item -> Throw exception */
                        throw e;
                    } else {
                        logger.info("Mirror failed due to exception");
                        logger.log(e);
                        continue;
                    }
                }
            }
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                br.followConnection(true);
                handleErrors(br);
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error");
            }
            link.setProperty(directlinkproperty, dl.getConnection().getURL().toString());
        }
        dl.startDownload();
    }

    private void handleErrors(final Browser br) throws PluginException {
        if (br.containsHTML("(?i)The file does not exist")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("(?i)Your IP address exceeds the number of downloads within the time limit")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Downloadlimit reached", 5 * 60 * 1000l);
        } else if (br.getHttpConnection().getResponseCode() == 403) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 5 * 60 * 1000l);
        } else if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 5 * 60 * 1000l);
        } else if (br.getHttpConnection().getResponseCode() == 503) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 503 too many connections", 5 * 60 * 1000l);
        } else {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error");
        }
    }

    @Override
    public boolean hasCaptcha(final DownloadLink link, final jd.plugins.Account acc) {
        return false;
    }

    private boolean attemptStoredDownloadurlDownload(final DownloadLink link, final String directlinkproperty, final boolean resumable, final int maxchunks) throws Exception {
        final String url = link.getStringProperty(directlinkproperty);
        if (StringUtils.isEmpty(url)) {
            return false;
        }
        try {
            final Browser brc = br.cloneBrowser();
            dl = new jd.plugins.BrowserAdapter().openDownload(brc, link, url, resumable, maxchunks);
            if (this.looksLikeDownloadableContent(dl.getConnection())) {
                return true;
            } else {
                brc.followConnection(true);
                throw new IOException();
            }
        } catch (final Throwable e) {
            logger.log(e);
            try {
                dl.getConnection().disconnect();
            } catch (Throwable ignore) {
            }
            return false;
        }
    }

    private boolean login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setFollowRedirects(true);
                br.setCookiesExclusive(true);
                /*
                 * 2021-11-09: Warning: They only allow one active session per account so user logging in via browser may end JDs session!
                 */
                final String urlRelativeAccountOverview = "/mydisk.php?item=profile&menu=cp";
                final Cookies cookies = account.loadCookies("");
                if (cookies != null) {
                    logger.info("Attempting cookie login");
                    br.setCookies(this.getHost(), cookies);
                    if (!force) {
                        /* Do not verify cookies */
                        return false;
                    }
                    br.getPage("https://" + this.getHost() + urlRelativeAccountOverview);
                    if (this.isLoggedin(br)) {
                        logger.info("Cookie login successful");
                        /* Refresh cookie timestamp */
                        account.saveCookies(br.getCookies(br.getHost()), "");
                        return true;
                    } else {
                        logger.info("Cookie login failed");
                    }
                }
                logger.info("Performing full login");
                br.getPage("https://" + this.getHost() + "/account.php?action=login");
                final Form loginform = br.getFormbyProperty("name", "user_form");
                if (loginform == null) {
                    logger.warning("Failed to find loginform");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                loginform.put("username", Encoding.urlEncode(account.getUser()));
                loginform.put("password", Encoding.urlEncode(account.getPass()));
                br.submitForm(loginform);
                br.getPage(urlRelativeAccountOverview);
                if (!isLoggedin(br)) {
                    throw new AccountInvalidException();
                }
                account.saveCookies(br.getCookies(br.getHost()), "");
                return true;
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    private boolean isLoggedin(final Browser br) {
        if (br.containsHTML("action=logout")) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(account, true);
        ai.setUnlimitedTraffic();
        final boolean isPremiumLifetimeAccount = br.containsHTML(">\\s*Premium\\s*<small>\\s*.?Unlimited");
        final String premiumExpire = br.getRegex("(?i)<td>\\s*Account type\\s*</td>\\s*<td>\\s*<b class=\"text-danger\">\\s*Premium\\s*<small>\\((\\d{4}-\\d{2}-\\d{2})\\)</small>").getMatch(0);
        if (isPremiumLifetimeAccount) {
            account.setType(AccountType.LIFETIME);
            account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
            account.setConcurrentUsePossible(true);
        } else if (premiumExpire != null) {
            ai.setValidUntil(TimeFormatter.getMilliSeconds(premiumExpire, "yyyy-MM-dd", Locale.ENGLISH), br);
            account.setType(AccountType.PREMIUM);
            account.setMaxSimultanDownloads(ACCOUNT_PREMIUM_MAXDOWNLOADS);
            account.setConcurrentUsePossible(true);
        } else {
            account.setType(AccountType.FREE);
            account.setMaxSimultanDownloads(ACCOUNT_FREE_MAXDOWNLOADS);
        }
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        this.handleDownload(link, account);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return ACCOUNT_PREMIUM_MAXDOWNLOADS;
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.PhpDisk;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}