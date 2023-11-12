//jDownloader - Downloadmanager
//Copyright (C) 2013  JD-Team support@jdownloader.org
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

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jdownloader.plugins.components.XFileSharingProBasic;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;

@HostPlugin(revision = "$Revision: 48265 $", interfaceVersion = 3, names = {}, urls = {})
public class PandafilesCom extends XFileSharingProBasic {
    public PandafilesCom(final PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(super.getPurchasePremiumURL());
    }

    /**
     * DEV NOTES XfileSharingProBasic Version SEE SUPER-CLASS<br />
     * mods: See overridden functions<br />
     * limit-info: 2020-12-15: Premium untested, set default XFS premium limits <br />
     * captchatype-info: 2020-12-07: null<br />
     * other:<br />
     */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "pandafiles.com" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    @Override
    protected String getContentURL(final DownloadLink link) {
        if (link == null) {
            return null;
        }
        final String originalURL = link.getPluginPatternMatcher();
        if (originalURL == null) {
            /* This should never happen! */
            return null;
        }
        /* link cleanup, prefer https if possible */
        try {
            final URL url = new URL(originalURL);
            final String urlHost = getPreferredHost(link, url);
            final String protocol;
            if ("https".equalsIgnoreCase(url.getProtocol()) && allowGetProtocolHttpsAutoHandling(originalURL)) {
                protocol = "https://";
            } else if (this.useHTTPS()) {
                protocol = "https://";
            } else {
                protocol = "http://";
            }
            /* Get full host with subdomain and correct base domain. */
            final String pluginHost = this.getHost();
            final List<String> deadDomains = this.getDeadDomains();
            final String host;
            if (deadDomains != null && deadDomains.contains(urlHost)) {
                /* Fallback to plugin domain */
                /* e.g. down.xx.com -> down.yy.com, keep subdomain(s) */
                host = urlHost.replaceFirst("(?i)" + Pattern.quote(Browser.getHost(url, false)) + "$", pluginHost);
            } else {
                /* Use preferred host */
                host = urlHost;
            }
            final String hostCorrected = this.appendWWWIfRequired(host);
            return protocol + hostCorrected + url.getPath();
        } catch (final MalformedURLException e) {
            logger.log(e);
        }
        /* Return unmodified url. */
        return originalURL;
    }

    public static String[] getAnnotationUrls() {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(?:d/[A-Za-z0-9]+|(?:embed-)?[a-z0-9]{12}(?:/[^/]+(?:\\.html)?)?|[A-Za-z0-9]+)");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        final AccountType type = account != null ? account.getType() : null;
        if (AccountType.FREE.equals(type)) {
            /* Free Account */
            return true;
        } else if (AccountType.PREMIUM.equals(type) || AccountType.LIFETIME.equals(type)) {
            /* Premium account */
            return true;
        } else {
            /* Free(anonymous) and unknown account type */
            return true;
        }
    }

    @Override
    public int getMaxChunks(final Account account) {
        final AccountType type = account != null ? account.getType() : null;
        if (AccountType.FREE.equals(type)) {
            /* Free Account */
            return -2;
        } else if (AccountType.PREMIUM.equals(type) || AccountType.LIFETIME.equals(type)) {
            /* Premium account */
            return 1;
        } else {
            /* Free(anonymous) and unknown account type */
            return -2;
        }
    }

    @Override
    protected boolean supports_availablecheck_filename_abuse() {
        return false;
    }

    @Override
    public int getMaxSimultaneousFreeAnonymousDownloads() {
        return 1;
    }

    @Override
    public int getMaxSimultaneousFreeAccountDownloads() {
        return 1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return 10;
    }

    @Override
    protected boolean supportsShortURLs() {
        return true;
    }
}