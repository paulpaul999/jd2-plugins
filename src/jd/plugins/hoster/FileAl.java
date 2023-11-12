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

import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.plugins.components.XFileSharingProBasic;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision: 47633 $", interfaceVersion = 3, names = {}, urls = {})
public class FileAl extends XFileSharingProBasic {
    public FileAl(final PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(super.getPurchasePremiumURL());
    }

    /**
     * DEV NOTES XfileSharingProBasic Version SEE SUPER-CLASS<br />
     * mods: See overridden functions<br />
     * limit-info:<br />
     * captchatype-info: 2019-05-03: reCaptchaV2<br />
     * other: Their website has strict anti-ddos measures which can easily be triggered and will lead to 'ip_check' pages --> More
     * reCaptchaV2 <br />
     * 2020-09-16: I was unable to trigger these captchas anymore (in premium mode) thus set junlimited simultaneous downloads for premium
     * mode
     */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "file.al", "1f.al" });
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
        return XFileSharingProBasic.buildAnnotationUrls(getPluginDomains());
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
        /* 2019-05-08: Serverside limited to 1 */
        /* 2020-03-02: Set to unlimited due to user request */
        return -1;
    }

    @Override
    public int getMaxSimultaneousFreeAccountDownloads() {
        /* 2019-05-08: Serverside limited to 1 */
        /* 2020-03-02: Set to unlimited due to user request */
        return -1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public Form findLoginform(final Browser br) {
        /* 2019-05-03: Special */
        final Form loginform = super.findLoginform(br);
        if (loginform != null) {
            /* This may help to reduce login-captchas / required full login attempts */
            loginform.remove("bind_to_ip");
            loginform.put("bind_to_ip", "0");
        }
        return loginform;
    }

    @Override
    public Form findFormDownload2Premium(final DownloadLink downloadLink, final Account account, final Browser br) throws Exception {
        /* 2019-05-03: Special */
        checkForSpecialCaptcha(br);
        return super.findFormDownload2Premium(downloadLink, account, br);
    }

    @Override
    public Form findFormDownload1Free(final Browser br) throws Exception {
        /* 2019-05-03: Special */
        checkForSpecialCaptcha(br);
        return super.findFormDownload1Free(br);
    }

    private void checkForSpecialCaptcha(final Browser br) throws Exception {
        if (br.getURL() != null && br.getURL().contains("/ip_check/")) {
            /*
             * 2019-01-23: Special - this may also happen in premium mode! This will only happen when accessing downloadurl. It gets e.g.
             * triggered when accessing a lot of different downloadurls in a small timeframe.
             */
            /* Tags: XFS_IP_CHECK /ip_check/ */
            final Form specialCaptchaForm = br.getFormbyProperty("name", "F1");
            if (specialCaptchaForm != null) {
                logger.info("Handling specialCaptchaForm");
                final boolean redirectSetting = br.isFollowingRedirects();
                final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
                specialCaptchaForm.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                br.setFollowRedirects(true);
                super.submitForm(br, specialCaptchaForm);
                final boolean siteVerificationFailure = br.toString().equalsIgnoreCase("Verification failed");
                if (siteVerificationFailure) {
                    logger.info("Site verification failed");
                    /*
                     * 2020-04-16: Log: 16.04.20 18.51.47 <--> 16.04.20 19.24.47 jdlog://8437815302851/
                     */
                    throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, 5 * 60 * 1000l);
                } else {
                    logger.info("Site verification successful");
                }
                /*
                 * 2020-05-04: Small workaround to prevent upper code from taking a redirect after this captcha as the final downloadurl by
                 * mistake.
                 */
                final String redirect = br.getRedirectLocation();
                if (!redirectSetting && redirect != null && redirect.matches("https?://[^/]+/[a-z0-9]{12}")) {
                    this.getPage(br, redirect);
                }
                br.setFollowRedirects(redirectSetting);
            }
        }
    }
}