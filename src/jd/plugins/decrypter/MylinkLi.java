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

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.appwork.utils.StringUtils;
import org.appwork.utils.Time;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperCrawlerPluginRecaptchaV2;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.parser.html.InputField;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision: 48378 $", interfaceVersion = 3, names = {}, urls = {})
public class MylinkLi extends PluginForDecrypt {
    public MylinkLi(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        ret.add(new String[] { "mylink.vc", "mylink.li", "mylink.how", "mylink.cx", "myl.li" });
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/[A-Za-z0-9]+");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        /** 2023-10-19: The usage of getAndSetSpecialCookie is not needed anymore but it doesn't hurt either so I've left it in. */
        // final String contentID = new Regex(param.getCryptedUrl(), "/([A-Za-z0-9]+)$").getMatch(0);
        br.setFollowRedirects(true);
        br.getHeaders().put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        br.getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        br.setCookie(br.getHost(), "prefix_views_counter", "1");
        final String httpRedirect = br.getRegex("<meta http-equiv=\"refresh\"[^>]*url=(https://[^\"]+)\"[^>]*>").getMatch(0);
        if (httpRedirect != null) {
            br.getPage(httpRedirect);
        }
        final Form captchaForm1 = br.getFormbyProperty("id", "captcha");
        if (captchaForm1 != null) {
            // final String phpsessid = br.getCookie(br.getHost(), "PHPSESSID", Cookies.NOTDELETEDPATTERN);
            logger.info("Found captchaForm1");
            getAndSetSpecialCookie(this.br);
            final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br).getToken();
            captchaForm1.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
            captchaForm1.remove("submit");
            br.getHeaders().put("Origin", "https://" + br.getHost());
            br.submitForm(captchaForm1);
        } else {
            logger.info("Did not find captchaForm1");
        }
        /*
         * Contains pretty much the same stuff as the first form and again our captcha result. This time, parameter "hash" is not empty.
         * "hash" usually equals our Cookie "PHPSESSID".
         */
        Form captchaForm2 = br.getFormbyProperty("id", "reCaptchaForm");
        if (captchaForm2 == null) {
            logger.warning("Failed to find captchaForm2");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String domainBeforeCaptcha = br.getHost();
        /* No needed */
        // final String specialHash = br.getRegex("return\\!0\\}\\('([a-f0-9]{32})").getMatch(0);
        // if (specialHash != null) {
        // final Browser brc = br.cloneBrowser();
        // brc.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        // brc.getHeaders().put("Origin", "https://" + br.getHost());
        // // brc.postPage("/user.php", "action=" + specialHash);
        // } else {
        // logger.warning("Failed to find specialHash");
        // }
        final InputField ifield = captchaForm2.getInputField("uri");
        if (ifield == null || StringUtils.isEmpty(ifield.getValue())) {
            /* Invalid/offline link. */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        br.setCookie(br.getHost(), "prefix_views_counter", "1");
        final ScriptEngineManager manager = JavaScriptEngineFactory.getScriptEngineManager(null);
        final ScriptEngine engine = manager.getEngineByName("javascript");
        final StringBuilder sb = new StringBuilder();
        /* 2023-10-18: See js in html code */
        sb.append("var max= 99999999;");
        sb.append("var min= 60000;");
        sb.append("var result = Math.floor(Math.random()*(max-min+1)+min);");
        try {
            engine.eval(sb.toString());
            final Number tab_id = (Number) engine.get("result");
            br.setCookie(br.getHost(), "tab_id", Long.toString(tab_id.longValue()));
        } catch (final Throwable ignore) {
            this.getLogger().log(ignore);
        }
        getAndSetSpecialCookie(this.br);
        /* 2nd captcha - this time, invisible reCaptchaV2 */
        final long timeBefore = Time.systemIndependentCurrentJVMTimeMillis();
        final String recaptchaV2Response_2 = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br) {
            @Override
            public TYPE getType() {
                return TYPE.INVISIBLE;
            }
        }.getToken();
        captchaForm2.put("g-recaptcha-response", recaptchaV2Response_2);
        getAndSetSpecialCookie(this.br);
        /* If the user needs more than 5 seconds to solve that captcha we don't have to wait :) */
        final long timeWait = 6100;
        final long waitLeft = timeWait - (Time.systemIndependentCurrentJVMTimeMillis() - timeBefore);
        final boolean skipWait = true; // 2023-10-18
        if (waitLeft > 0 && !skipWait) {
            this.sleep(waitLeft, param);
        }
        br.submitForm(captchaForm2);
        final Form shareForm = br.getFormbyKey("share");
        if (shareForm != null) {
            getAndSetSpecialCookie(br);
            br.submitForm(shareForm);
        } else {
            logger.info("Did not find shareForm");
        }
        /* A lot of Forms may appear here - all to force the user to share the link, bookmark their page, click on ads and so on ... */
        br.setFollowRedirects(false);
        Form goForm = null;
        int numberof404Responses = 0;
        final int maxLoops = 10;
        for (int i = 0; i <= maxLoops; i++) {
            logger.info("Loop: " + i + "/" + maxLoops);
            goForm = br.getFormbyKey("hash");
            if (goForm == null) {
                logger.info("Stepping out of loop");
                break;
            } else {
                getAndSetSpecialCookie(br);
                // goForm.remove("Continue");
                br.submitForm(goForm);
                /* 2021-07-08: Attempt to avoid strange error/adblock detection stuff hmm unsure about that... but it works! */
                if (br.containsHTML("<title>404</title>")) {
                    /* This should only happen once */
                    numberof404Responses++;
                    logger.info("Trying 404 avoidance | Attempt: " + numberof404Responses);
                    br.submitForm(goForm);
                }
            }
        }
        final String finallink = br.getRedirectLocation();
        logger.info("Final result: " + finallink);
        if (finallink == null) {
            final boolean isMainpage = br.getURL().matches("(?i)https?://[^/]+/?$");
            if (numberof404Responses > 1) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (isMainpage) {
                /* Redirect to mainpage after captcha -> Wrong ID/link -> Contento offline */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        } else if (finallink.matches("(?i)https?://[^/]+/?$") && finallink.contains(domainBeforeCaptcha)) {
            /* Redirect to mainpage */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        ret.add(createDownloadlink(finallink));
        return ret;
    }

    private void getAndSetSpecialCookie(final Browser br) {
        final String specialCookie = br.getRegex("\"/hkz\"\\);setCookie\\(\"([a-z0-9]+)\",1,").getMatch(0);
        if (specialCookie != null) {
            logger.info("Found new specialCookie: " + specialCookie);
            br.setCookie(br.getHost(), specialCookie, "1");
        } else {
            logger.info("Failed to find new specialCookie");
        }
    }
}
