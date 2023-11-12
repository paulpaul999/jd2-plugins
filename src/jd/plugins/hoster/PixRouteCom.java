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
import jd.http.URLConnectionAdapter;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

import org.jdownloader.plugins.components.XFileSharingProBasic;

@HostPlugin(revision = "$Revision: 46820 $", interfaceVersion = 3, names = {}, urls = {})
public class PixRouteCom extends XFileSharingProBasic {
    public PixRouteCom(final PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(super.getPurchasePremiumURL());
    }

    /**
     * DEV NOTES XfileSharingProBasic Version SEE SUPER-CLASS<br />
     * mods: See overridden functions<br />
     * limit-info:<br />
     * captchatype-info: null<br />
     * other:<br />
     */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "pixroute.com" });
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
            // todo: add optional plugin settings support for the direct images /i/ (see getFUIDFromURL) , optional because ppl might want
            // to avoid the plugin and
            // use directhttp
            // instead
            ret.add("https?://(?:\\w+\\.)?" + buildHostsPatternPart(domains) + "(" + XFileSharingProBasic.getDefaultAnnotationPatternPart() + "|/(?:th|i)/\\d+/[a-z0-9]{12})");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return true;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return true;
        } else {
            /* Free(anonymous) and unknown account type */
            return true;
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
    protected String getPreferredHost(DownloadLink link, URL url) {
        return getHost();
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

    @Override
    protected void handleDownloadErrors(URLConnectionAdapter con, final DownloadLink link, final Account account) throws Exception {
        /* 2019-07-03: Special */
        super.handleDownloadErrors(con, link, account);
        boolean specialOffline = false;
        try {
            final long final_filesize = con.getCompleteContentLength();
            final String response_last_modified = con.getRequest().getResponseHeader("Last-Modified").toString();
            specialOffline = final_filesize == 40275 && response_last_modified.equalsIgnoreCase("Sun, 10 Mar 2019 14:07:34 GMT");
        } catch (final Throwable e) {
        }
        if (specialOffline) {
            /*
             * Very very rare case: Dummy image which shows "Image Removed" - website itself displays content as online! Example:
             * https://pixroute.com/94dpenhmi6st/ARS32HT02_s.jpg.html
             */
            logger.info("Special offline file");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
    }

    @Override
    public boolean isImagehoster() {
        /* 2019-07-02: Special */
        return true;
    }
}