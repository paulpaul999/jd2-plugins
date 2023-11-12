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

import java.util.ArrayList;
import java.util.List;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;

import org.appwork.utils.net.httpconnection.HTTPConnectionUtils.IPVERSION;
import org.jdownloader.plugins.components.XFileSharingProBasic;

@HostPlugin(revision = "$Revision: 47868 $", interfaceVersion = 3, names = {}, urls = {})
public class RareFileNet extends XFileSharingProBasic {
    public RareFileNet(final PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(super.getPurchasePremiumURL());
    }

    /**
     * DEV NOTES XfileSharingProBasic Version SEE SUPER-CLASS<br />
     * mods: See overridden functions<br />
     * limit-info:<br />
     * captchatype-info: 2020-02-17: 4dignum<br />
     * other:<br />
     */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "rarefile.net" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public Browser createNewBrowserInstance() {
        final Browser br = super.createNewBrowserInstance();
        if (br != null) {
            // 2023-06-06: looks like server setup is missing IPv6 configuration
            br.setIPVersion(IPVERSION.IPV4_IPV6);
        }
        return br;
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        return XFileSharingProBasic.buildAnnotationUrls(getPluginDomains());
    }

    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return false;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return true;
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
            return 0;
        } else {
            /* Free(anonymous) and unknown account type */
            return 1;
        }
    }

    @Override
    public int getMaxSimultaneousFreeAnonymousDownloads() {
        /* 2020-02-26: Once long waittime happens, user can start more downloads afterwards e.g. one more every hour */
        return -1;
    }

    @Override
    public int getMaxSimultaneousFreeAccountDownloads() {
        /* 2020-02-26: Once long waittime happens, user can start more downloads afterwards e.g. one more every hour */
        return -1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    protected boolean websiteSupportsHTTPS() {
        /* 2020-02-17: Special */
        return false;
    }

    @Override
    public ArrayList<String> getCleanupHTMLRegexes() {
        /* 2020-02-17: Special */
        final ArrayList<String> regexStuff = new ArrayList<String>();
        // remove custom rules first!!! As html can change because of generic cleanup rules.
        /* generic cleanup */
        regexStuff.add("<\\!(\\-\\-.*?\\-\\-)>");
        regexStuff.add("(display: ?none;\">.*?</div>)");
        regexStuff.add("(visibility:hidden>.*?<)");
        regexStuff.add("(<div style=\"display:none\"><BR>.*?<br><br>)");
        return regexStuff;
    }

    @Override
    protected boolean isOffline(final DownloadLink link, final Browser br, final String html) {
        boolean offline = super.isOffline(link, br, html);
        if (!offline) {
            offline = new Regex(html, ">\\s*This server has crashed but we are still working for this server|>\\s*No such file with this filename").matches();
        }
        return offline;
    }
}