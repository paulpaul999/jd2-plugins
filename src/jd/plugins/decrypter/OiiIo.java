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
package jd.plugins.decrypter;

import java.util.ArrayList;
import java.util.List;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.parser.html.Form;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@DecrypterPlugin(revision = "$Revision: 48444 $", interfaceVersion = 3, names = {}, urls = {})
public class OiiIo extends MightyScriptAdLinkFly {
    public OiiIo(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "oii.io" });
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
        return buildAnnotationUrls(getPluginDomains());
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/([A-Za-z0-9]+)");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    protected void hookAfterCaptcha(final Browser br, final Form form) throws Exception {
        /* A 2nd captcha can be required. */
        final Form captcha2 = br.getFormbyProperty("id", "link-view");
        final CaptchaType captchaType = getCaptchaType(captcha2);
        final Form form20231023 = br.getFormbyProperty("id", "submit_data");
        if (captcha2 != null && captchaType != null) {
            if (captchaType == CaptchaType.reCaptchaV2 || captchaType == CaptchaType.reCaptchaV2_invisible) {
                handleRecaptcha(captchaType, br, captcha2);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unsupported:" + captchaType);
            }
            submitForm(br, captcha2);
        } else if (form20231023 != null) {
            submitForm(br, form20231023);
        }
        /* Page with button "Click here to continue" */
        final Form continueForm = br.getFormByRegex("ad_form_data");
        if (continueForm != null) {
            submitForm(br, continueForm);
        }
    }

    @Override
    protected String findFinallink(final Browser br) {
        final String finallink = br.getRegex("doc\\.write\\('<a href=\"(http[^\"]+)").getMatch(0);
        if (finallink != null) {
            return finallink;
        } else {
            return super.findFinallink(br);
        }
    }

    @Override
    protected Form getContinueForm(CryptedLink param, Form form, final Browser br) {
        /* 2023-11-10 */
        final Form continueform = super.getContinueForm(param, form, br);
        final String specialContinueURL = br.getRegex("var domain = \"(https?://[^/]+/links/go[^\"]*)\";").getMatch(0);
        if (continueform != null && specialContinueURL != null) {
            continueform.setAction(specialContinueURL);
            return continueform;
        } else {
            return null;
        }
    }
}
