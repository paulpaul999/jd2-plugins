//jDownloader - Downloadmanager
//Copyright (C) 2014  JD-Team support@jdownloader.org
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
import java.util.Random;
import java.util.regex.Pattern;

import org.appwork.utils.StringUtils;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperCrawlerPluginRecaptchaV2;
import org.jdownloader.plugins.components.antiDDoSForDecrypt;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.Request;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.Form.MethodType;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.components.SiteType.SiteTemplate;

@DecrypterPlugin(revision = "$Revision: 45277 $", interfaceVersion = 3, names = {}, urls = {})
public class ShorteSt extends antiDDoSForDecrypt {
    // add new domains here.
    private static final String[] domains = { "sh.st", "viid.me", "wiid.me", "skiip.me", "clkme.me", "clkmein.com", "clkme.in", "destyy.com", "festyy.com", "corneey.com", "gestyy.com", "ceesty.com", "destyy.com" };

    public ShorteSt(PluginWrapper wrapper) {
        super(wrapper);
    }

    private boolean containsLoginRedirect(final String input) {
        if (input == null) {
            return false;
        }
        final String redirect = Request.getLocation(input, br.getRequest());
        final boolean result = redirect.matches("(?i)" + getHost() + "/login");
        return result;
    }

    @Override
    public int getMaxConcurrentProcessingInstances() {
        /* 2020-02-10: Try not to trigger their "Site verification" which would lead to (more) captchas. */
        return 1;
    }

    /** 2020-07-13: Not required as the special User-Agent should work for all domains that this crawler can handle. */
    // private boolean useGoogleUA(final String host) {
    // final ArrayList<String> googleDomains = new ArrayList<String>();
    // googleDomains.add("ceesty.com");
    // googleDomains.add("corneey.com");
    // googleDomains.add("destyy.com");
    // googleDomains.add("festyy.com");
    // googleDomains.add("gestyy.com");
    // googleDomains.add("sh.st");
    // googleDomains.add("viid.me");
    // return googleDomains.contains(host);
    // }
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        String parameter = param.toString().replace("//clkme.in/", "//cllkme.com/");
        if (parameter.contains("%29")) {
            parameter = parameter.replace("%29", ")");
            parameter = parameter.replace("%28", "(");
            parameter = parameter.replace("_", "i");
            parameter = parameter.replace("*", "u");
            parameter = parameter.replace("!", "a");
        }
        // blocked via cloudflare, 06.11.2020
        // br.getHeaders().put("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
        getPage(parameter);
        String redirect = br.getRegex("<meta http-equiv=\"refresh\" content=\"\\d+\\;url=(.*?)\" \\/>").getMatch(0);
        if (containsLoginRedirect(redirect) || br.containsHTML(">link removed<")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (redirect != null) {
            parameter = redirect;
            boolean redirectsToSupportedDomain = false;
            final String redirectHost = Browser.getHost(redirect);
            for (final String supportedDomain : domains) {
                if (redirectHost.equalsIgnoreCase(supportedDomain)) {
                    redirectsToSupportedDomain = true;
                    break;
                }
            }
            if (!redirectsToSupportedDomain) {
                /* 2020-07-13: Direct redirect to final downloadurl (e.g. when GoogleBot User-Agent is used) */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            getPage(parameter);
        }
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        br.setFollowRedirects(true);
        handleSiteVerification(parameter);
        String finallink = null;
        if (br.containsHTML("g-recaptcha\"|google\\.com/recaptcha/") || true) {
            // https://github.com/adsbypasser/adsbypasser/blob/master/src/sites/link/sh.st.js
            Form continueForm = br.getForm(0);
            if (continueForm == null) {
                /* 2019-03-08: Form might not necessarily be present in html anymore */
                continueForm = new Form();
                continueForm.setMethod(MethodType.POST);
                if (br.getURL().contains("?r=")) {
                    continueForm.setAction(br.getURL());
                } else {
                    continueForm.setAction(br.getURL() + "?r=");
                }
            }
            if (br.containsHTML("displayCaptcha\\s*:\\s*true")) {
                final String siteKey = br.getRegex("public_key\\s*:\\s*'(.*?)'").getMatch(0);
                final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br, siteKey).getToken();
                continueForm.put("g-recaptcha-response", recaptchaV2Response);
            }
            /* 2019-03-08: Finallink may also be given via direct-redirect */
            br.setFollowRedirects(false);
            submitForm(continueForm);
            redirect = br.getRedirectLocation();
            if (redirect != null) {
                if (new Regex(redirect, this.getSupportedLinks()).matches()) {
                    br.setFollowRedirects(true);
                    getPage(redirect);
                    /* Additional captcha might be required. */
                    handleSiteVerification(parameter);
                } else {
                    finallink = redirect;
                }
            }
        }
        if (finallink == null) {
            /* 2020-02-03: Offline can happen after siteVerification & captcha */
            if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("(?i)>\\s*page not found<")) {
                if (!parameter.contains("!/")) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else {
                    return decryptedLinks;
                }
            }
            final String timer = PluginJSonUtils.getJsonValue(br, "seconds");
            final String cb = PluginJSonUtils.getJsonValue(br, "callbackUrl");
            final String sid = PluginJSonUtils.getJsonValue(br, "sessionId");
            if (cb == null || sid == null) {
                finallink = br.getRegex("destinationUrl\\s*:\\s*'(https?://.*?)'").getMatch(0);
                // destinationURL = PluginJSonUtils.getJson(br, "destinationUrl");
                if (StringUtils.isEmpty(finallink)) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                finallink = finallink.replaceAll(" ", "%20");
                decryptedLinks.add(createDownloadlink(finallink));
                return decryptedLinks;
            }
            int t = 5;
            if (timer != null) {
                t = Integer.parseInt(timer);
            }
            sleep(t * 1001, param);
            final Browser br2 = br.cloneBrowser();
            br2.getHeaders().put("Accept", "application/json, text/javascript");
            br2.getHeaders().put("Content-Type", "application/x-www-form-urlencoded");
            br2.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            postPage(br2, cb, "adSessionId=" + sid + "&callback=reqwest_" + new Regex(String.valueOf(new Random().nextLong()), "(\\d{10})$").getMatch(0));
            finallink = PluginJSonUtils.getJsonValue(br2, "destinationUrl");
            if (finallink == null) {
                logger.warning("Decrypter broken for link: " + parameter);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            finallink = finallink.replaceAll(" ", "%20");
        }
        decryptedLinks.add(createDownloadlink(finallink));
        return decryptedLinks;
    }

    /** 2019-01-25: 'site-verification' without captcha */
    private void handleSiteVerification(final String parameter) throws Exception {
        /* 2020-01-10: <strong>You are not allowed to access requested page </strong> <br> upon successful verification. </p> */
        int counter = 0;
        final int maxcount = 3;
        while (br.containsHTML("BROWSER VERIFICATION|>You are not allowed to access requested page") && counter <= maxcount) {
            counter++;
            logger.info("Browser verification loop: " + counter);
            final Form captchaForm = br.getFormbyActionRegex(".+grey_wizard_captcha.+");
            logger.info("Handling browser-verification ...");
            if (captchaForm != null) {
                /* 2020-01-10: New: First captcha, then wait + cookies */
                logger.info("Handling browser-verification: captcha");
                final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br).getToken();
                captchaForm.put("g-recaptcha-response", recaptchaV2Response);
                this.submitForm(br, captchaForm);
            }
            final String jsurl = br.getRegex("<script src=\\'(/grey_wizard_rewrite_js/\\?[^<>\"\\']+)\\'>").getMatch(0);
            if (jsurl != null) {
                logger.info("Handling browser-verification: js and waittime");
                String waitStr = br.getRegex("timeToWait\\s*:\\s*(\\d+)").getMatch(0);
                final Browser brc = br.cloneBrowser();
                getPage(brc, jsurl);
                final String c_value = brc.getRegex("c_value = \\'([^<>\"\\']+)\\'").getMatch(0);
                if (waitStr == null) {
                    brc.getRegex(">Please wait (\\d+) seconds").getMatch(0);
                }
                if (c_value == null) {
                    throw new DecrypterException("SITE_VERIFICATION_FAILED");
                }
                br.setCookie(br.getURL(), "grey_wizard", c_value);
                br.setCookie(br.getURL(), "grey_wizard_rewrite", c_value);
                int wait = 4;
                if (waitStr != null) {
                    wait = Integer.parseInt(waitStr) + 1;
                }
                logger.info("Waiting (seconds): " + wait);
                this.sleep(wait * 1001, param);
                getPage(parameter);
            }
        }
    }

    @Override
    public String[] siteSupportedNames() {
        return domains;
    }

    /**
     * Returns the annotations names array
     *
     * @return
     */
    public static String[] getAnnotationNames() {
        return domains;
    }

    /**
     * returns the annotation pattern array
     *
     */
    public static String[] getAnnotationUrls() {
        return getHostsPattern();
    }

    private static String[] getHostsPattern() {
        final ArrayList<String> ret = new ArrayList<String>(0);
        for (final String name : domains) {
            ret.add("https?://(www\\.)?" + Pattern.quote(name).toString() + "/[^<>\r\n\t]+");
        }
        return ret.toArray(new String[0]);
    }

    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.ShorteSt_ShorteSt;
    }
}
