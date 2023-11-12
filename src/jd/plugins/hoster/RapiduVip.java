//jDownloader - Downloadmanager
//Copyright (C) 2016  JD-Team support@jdownloader.org
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

import org.jdownloader.plugins.components.YetiShareCore;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision: 48402 $", interfaceVersion = 2, names = {}, urls = {})
public class RapiduVip extends YetiShareCore {
    public RapiduVip(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(getPurchasePremiumURL());
    }

    /**
     * DEV NOTES YetiShare<br />
     ****************************
     * mods: See overridden functions<br />
     * limit-info:<br />
     * captchatype-info: null solvemedia reCaptchaV2, hcaptcha<br />
     * other: <br />
     */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        /* 2023-10-27: Main domain has changed from rapidu.vip to pobieraj.net. */
        ret.add(new String[] { "pobieraj.net", "rapidu.vip" });
        return ret;
    }

    @Override
    public String rewriteHost(String host) {
        /* 2023-10-27: Main domain has changed from rapidu.vip to pobieraj.net */
        return this.rewriteHost(getPluginDomains(), host);
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        return YetiShareCore.buildAnnotationUrls(getPluginDomains());
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
    public int getMaxSimultanFreeDownloadNum() {
        return 1;
    }

    public int getMaxSimultaneousFreeAccountDownloads() {
        return 1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return 1;
    }

    @Override
    protected void runPostRequestTask(final Browser ibr) throws Exception {
        super.runPostRequestTask(ibr);
        dirtyHack(br);
    }

    @Override
    protected Form getContinueForm(final Browser br, final int loop_counter, final String continue_link) throws PluginException {
        dirtyHack(br);
        return super.getContinueForm(br, loop_counter, continue_link);
    }

    private final void dirtyHack(final Browser br) {
        /* 2023-10-27: Dirty hack - there are two reCaptchaV2 site-keys in their html code -> Remove the one we don't need. */
        String newhtml = br.getRequest().getHtmlCode();
        newhtml = newhtml.replace("6Leb_JEkAAAAAJJco-pJGYa-Vwax_i95EcHsOLIS", "");
        newhtml = newhtml.replace("grecaptcha\\.enterprise", "");
        br.getRequest().setHtmlCode(newhtml);
    }

    @Override
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        /* 2023-10-27 */
        return true;
    }
}