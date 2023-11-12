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

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import jd.PluginWrapper;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;

import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.components.XFileSharingProBasic;

@HostPlugin(revision = "$Revision: 46820 $", interfaceVersion = 3, names = {}, urls = {})
public class ImagetwistCom extends XFileSharingProBasic {
    public ImagetwistCom(final PluginWrapper wrapper) {
        super(wrapper);
        // this.enablePremium(super.getPremiumLink());
    }

    /**
     * DEV NOTES XfileSharingProBasic Version SEE SUPER-CLASS<br />
     * mods: See overridden functions<br />
     * limit-info:<br />
     * captchatype-info: 2019-02-11: null<br />
     * other:<br />
     */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "imagetwist.com", "croea.com", "imageshimage.com", "imagexport.com", "imagenpic.com" });
        return ret;
    }

    @Override
    public String getLinkID(DownloadLink link) {
        if (StringUtils.containsIgnoreCase(link.getPluginPatternMatcher(), "phun.imagetwist.com")) {
            final String fuid = getFUIDFromURL(link);
            if (fuid != null) {
                return "phun.imagetwist.com://" + fuid;
            }
        }
        return super.getLinkID(link);
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    protected String getPreferredHost(DownloadLink link, URL url) {
        // plugin also supports thumbs and correctURL rewrites to normal URLs on main domain
        String host = url.getHost();
        if (host.matches("(?i)^(\\w+)?phun\\.imagetwist\\.com$")) {
            return "phun.imagetwist.com";
        }
        return getHost();
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            // todo: add optional plugin settings support for the direct images /i/ (see getFUIDFromURL) , optional because ppl might want
            // to avoid the plugin and
            // use directhttp
            // instead
            ret.add("https?://(?:\\w+\\.)?" + buildHostsPatternPart(domains) + "(" + XFileSharingProBasic.getDefaultAnnotationPatternPart() + "|/(?:th|i)/\\d+/[a-z0-9]{12})");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    protected boolean supports_availablecheck_filesize_html() {
        return false;
    }

    @Override
    protected boolean supports_availablecheck_alt() {
        /* 2021-04-12 */
        return false;
    }

    @Override
    public boolean isImagehoster() {
        return true;
    }

    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return false;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return false;
        } else {
            /* Free(anonymous) and unknown account type */
            return false;
        }
    }

    @Override
    public int getMaxChunks(final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return 1;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return 1;
        } else {
            /* Free(anonymous) and unknown account type */
            return 1;
        }
    }

    @Override
    public int getMaxSimultaneousFreeAnonymousDownloads() {
        return -1;
    }

    @Override
    public int getMaxSimultaneousFreeAccountDownloads() {
        return -1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }
}