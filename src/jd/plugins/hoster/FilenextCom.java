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

import org.appwork.utils.Regex;
import org.jdownloader.plugins.components.XFileSharingProBasic;

import jd.PluginWrapper;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;

@HostPlugin(revision = "$Revision: 45402 $", interfaceVersion = 3, names = {}, urls = {})
public class FilenextCom extends XFileSharingProBasic {
    public FilenextCom(final PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(super.getPurchasePremiumURL());
    }

    /**
     * DEV NOTES XfileSharingProBasic Version SEE SUPER-CLASS<br />
     * mods: See overridden functions<br />
     * limit-info:<br />
     * captchatype-info: 2019-09-24: unknown<br />
     * other:<br />
     */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "filenext.com" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        final String[] extraNames = { "filenext" };
        final String[] officiallySupportedNames = buildSupportedNames(getPluginDomains());
        String[] finalSupportedNames = new String[officiallySupportedNames.length + extraNames.length];
        System.arraycopy(officiallySupportedNames, 0, finalSupportedNames, 0, officiallySupportedNames.length);
        System.arraycopy(extraNames, 0, finalSupportedNames, officiallySupportedNames.length, extraNames.length);
        return finalSupportedNames;
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
        return 1;
    }

    @Override
    public int getMaxSimultaneousFreeAccountDownloads() {
        return 1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public ArrayList<String> getCleanupHTMLRegexes() {
        final ArrayList<String> regexStuff = super.getCleanupHTMLRegexes();
        /*
         * 2020-04-20: E.g. bad html which breaks password handling in premium mode --> JD would ask for a download captcha although there
         * is none e.g. <div style="display:none;background:#ccc;"><br><b>Password:</b></div>
         */
        regexStuff.add("<div\\s*style\\s*=\\s*\"[^\"]*display:none[^\"]*\"\\s*>(.*?)</div>");
        return regexStuff;
    }

    @Override
    public String[] scanInfo(final String html, final String[] fileInfo) {
        super.scanInfo(html, fileInfo);
        /* 2022-01-24 */
        final String betterFilesize = new Regex(html, "class=\"fLeft\"[^>]+><b>([^<>\"]+)</b>").getMatch(0);
        if (betterFilesize != null) {
            fileInfo[1] = betterFilesize;
        }
        return fileInfo;
    }
}