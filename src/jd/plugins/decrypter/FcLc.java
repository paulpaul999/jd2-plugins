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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.appwork.utils.StringUtils;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperCrawlerPluginRecaptchaV2;
import org.jdownloader.plugins.components.antiDDoSForDecrypt;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.components.SiteType.SiteTemplate;

@DecrypterPlugin(revision = "$Revision: 48347 $", interfaceVersion = 2, names = {}, urls = {})
public class FcLc extends antiDDoSForDecrypt {
    private static final String[]     domains                    = { "fc.lc", "fcc.lc", "short.fc-lc.com", "short.articlix.com", "fc-lc.com", "fc-lc.xyz" };
    /** List of services for which waittime is skippable. */
    private static final List<String> domains_waittime_skippable = Arrays.asList(new String[] { "fcc.lc" });
    // /** List of services for which captcha is skippable or not required. */
    /** TODO: Find a way to automatically detect this edge-case */
    private static final List<String> domains_captcha_skippable  = Arrays.asList(new String[] {});

    /**
     * returns the annotation pattern array
     *
     */
    public static String[] getAnnotationUrls() {
        // construct pattern
        final String host = getHostsPattern();
        return new String[] { host + "/[a-zA-Z0-9]{2,}" };
    }

    private static String getHostsPattern() {
        final StringBuilder pattern = new StringBuilder();
        for (final String name : domains) {
            pattern.append((pattern.length() > 0 ? "|" : "") + Pattern.quote(name));
        }
        final String hosts = "https?://(?:www\\.)?" + "(?:" + pattern.toString() + ")";
        return hosts;
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
        return new String[] { "fc.lc" };
    }

    public enum CaptchaType {
        reCaptchaV2,
        reCaptchaV2_invisible,
        solvemedia,
        WTF
    };

    public FcLc(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public int getMaxConcurrentProcessingInstances() {
        return 1;
    }

    private String appVars = null;

    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        br = new Browser();
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String contentURL = param.getCryptedUrl();
        final String source_host = Browser.getHost(contentURL);
        br.setFollowRedirects(false);
        getPage(contentURL);
        // 2019-11-13: http->https->different domain(https)
        // 2019-11-13: http->https->different domain(http)->different domain(https)
        while (true) {
            String redirect = br.getRedirectLocation();
            if (redirect == null) {
                /* 2019-01-29: E.g. cuon.io */
                redirect = br.getRegex("<meta http\\-equiv=\"refresh\" content=\"\\d+;url=(https?://[^\"]+)\">").getMatch(0);
            }
            if (redirect == null) {
                /* 2019-04-25: E.g. clkfly.pw */
                redirect = br.getHttpConnection().getHeaderField("Refresh");
                if (redirect != null && redirect.matches("^\\d+, http.+")) {
                    redirect = new Regex(redirect, "^\\d+, (http.+)").getMatch(0);
                }
            }
            if (redirect != null && !redirect.contains(source_host + "/")) {
                /*
                 * 2018-07-18: Direct redirect without captcha or any Form e.g. vivads.net OR redirect to other domain of same service e.g.
                 * wi.cr --> wicr.me
                 */
                ret.add(this.createDownloadlink(redirect));
                return ret;
            } else {
                if (redirect != null) {
                    getPage(redirect);
                } else {
                    break;
                }
            }
        }
        br.setFollowRedirects(true);
        if (br.getHttpConnection().getResponseCode() == 403 || br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.toString().length() < 100) {
            /* 2020-05-29: E.g. https://uii.io/full */
            logger.info("Invalid HTML - probably offline content");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Form beforeCaptcha = br.getFormbyProperty("id", "before-captcha");
        if (beforeCaptcha != null) {
            /* 2019-10-30: E.g. exe.io, redirecto.link */
            logger.info("Found pre-captcha Form");
            this.submitForm(beforeCaptcha);
        }
        /* 2020-07-06: E.g. fc.lc, fcc.lc */
        Form beforeCaptcha2 = br.getFormbyKey("ad_form_data");
        if ((br.getHost().equalsIgnoreCase("fc.lc") || br.getHost().equalsIgnoreCase("fc-lc.com")) && beforeCaptcha2 != null) {
            logger.info("Sending Form beforeCaptcha2");
            if (beforeCaptcha2.containsHTML("recaptcha") && br.getHost().equalsIgnoreCase("fc.lc")) {
                /* 2021-05-07: Can contain invisible reCaptchaV2 but not every domain has recaptcha js! */
                final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br).getToken();
                beforeCaptcha2.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
            } else if (beforeCaptcha2.containsHTML("g-recaptcha btn btn-primary")) {
                /* 2021-10-12 */
                final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br).getToken();
                beforeCaptcha2.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
            }
            this.submitForm(beforeCaptcha2);
            beforeCaptcha2 = br.getFormbyKey("ad_form_data");
            if (beforeCaptcha2 != null && beforeCaptcha2.getAction() != null) {
                logger.info("Submitting second beforeCaptcha2");
                this.submitForm(beforeCaptcha2);
            }
        }
        appVars = br.getRegex("var (app_vars.*?)</script>").getMatch(0);
        Form form = getCaptchaForm();
        if (form == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (!this.evalulateCaptcha(CaptchaType.reCaptchaV2, contentURL)) {
            /* 2020-10-26: short.fc-lc.com */
            this.submitForm(form);
        }
        if (form.hasInputFieldByName("captcha")) {
            /* original captcha/ VERY OLD way! [2018-07-18: Very rare or non existent anymore!] */
            logger.info("OLD captcha required");
            final String code = getCaptchaCode("cp.php", param);
            form.put("captcha", Encoding.urlEncode(code));
            submitForm(form);
            if (br.containsHTML("<script>alert\\('(?:Empty Captcha|Incorrect Captcha)\\s*!'\\);")) {
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            form = br.getForm(0);
            if (form == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            // we want redirect off here
            br.setFollowRedirects(false);
            submitForm(form);
            final String finallink = br.getRedirectLocation();
            if (finallink == null) {
                if (br.containsHTML("<script>alert\\('(?:Link not found)\\s*!'\\);")) {
                    // invalid link
                    logger.warning("Invalid link : " + contentURL);
                    return ret;
                }
                logger.warning("Decrypter broken for link: " + contentURL);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            ret.add(createDownloadlink(finallink));
        } else {
            /* 2020-07-06: Static attempt */
            final boolean forceRecaptchaV2 = false;
            final CaptchaType captchaType;
            if (forceRecaptchaV2) {
                captchaType = CaptchaType.reCaptchaV2;
            } else {
                captchaType = getCaptchaType();
            }
            if (evalulateCaptcha(captchaType, contentURL)) {
                logger.info("Captcha required");
                boolean requiresCaptchaWhichCanFail = false;
                boolean captchaFailed = false;
                for (int i = 0; i <= 2; i++) {
                    form = getCaptchaForm();
                    if (form == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    if (captchaType == CaptchaType.reCaptchaV2 || captchaType == CaptchaType.reCaptchaV2_invisible) {
                        requiresCaptchaWhichCanFail = false;
                        final String key;
                        if (captchaType == CaptchaType.reCaptchaV2) {
                            key = getAppVarsResult("reCAPTCHA_site_key");
                        } else {
                            key = getAppVarsResult("invisible_reCAPTCHA_site_key");
                        }
                        if (StringUtils.isEmpty(key)) {
                            logger.warning("Failed to find reCaptchaV2 key --> Fallback to default handling");
                            final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br) {
                                @Override
                                public TYPE getType() {
                                    if (captchaType == CaptchaType.reCaptchaV2_invisible) {
                                        return TYPE.INVISIBLE;
                                    } else {
                                        return TYPE.NORMAL;
                                    }
                                }
                            }.getToken();
                            form.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                        } else {
                            final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br, key) {
                                @Override
                                public TYPE getType() {
                                    if (captchaType == CaptchaType.reCaptchaV2_invisible) {
                                        return TYPE.INVISIBLE;
                                    } else {
                                        return TYPE.NORMAL;
                                    }
                                }
                            }.getToken();
                            form.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                        }
                    } else if (captchaType == CaptchaType.solvemedia) {
                        final String solvemediaChallengeKey = this.getAppVarsResult("solvemedia_challenge_key");
                        if (StringUtils.isEmpty(solvemediaChallengeKey)) {
                            logger.warning("Failed to find solvemedia_challenge_key");
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                        }
                        requiresCaptchaWhichCanFail = true;
                        final org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia sm = new org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia(br);
                        sm.setChallengeKey(solvemediaChallengeKey);
                        File cf = null;
                        cf = sm.downloadCaptcha(getLocalCaptchaFile());
                        final String code = getCaptchaCode("solvemedia", cf, param);
                        final String chid = sm.getChallenge(code);
                        form.put("adcopy_challenge", chid);
                        form.put("adcopy_response", "manual_challenge");
                    } else {
                        /* Unsupported captchaType */
                        logger.warning("Unsupported captcha type!");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    submitForm(form);
                    if (requiresCaptchaWhichCanFail && this.br.containsHTML("The CAPTCHA was incorrect")) {
                        captchaFailed = true;
                    } else {
                        captchaFailed = false;
                    }
                    if (!captchaFailed) {
                        /* Captcha success or we did not have to enter any captcha! */
                        break;
                    }
                }
                if (requiresCaptchaWhichCanFail && captchaFailed) {
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                }
            } else if (form != null) {
                logger.info("No captcha required");
                this.submitForm(form);
            }
            final Form[] forms = br.getForms();
            if (forms != null && forms.length > 0) {
                /* 2023-10-07: We are on tophostingapp.com -> Form -> Another form -> Wait -> Form -> Final URL */
                final Form continueForm = forms[0];
                this.submitForm(continueForm);
            }
            Form finalform = null;
            int counter = -1;
            do {
                counter++;
                final Form thisform = br.getFormbyKey("_Token%5Bfields%5D");
                if (thisform == null) {
                    break;
                }
                if (br.containsHTML("id=\"linkrdy\"")) {
                    finalform = thisform;
                    break;
                } else {
                    this.submitForm(thisform);
                }
            } while (counter <= 3);
            final boolean skipWait = waittimeIsSkippable(source_host);
            if (finalform != null) {
                if (finalform.getAction() == null || (!finalform.getAction().startsWith("/") && !finalform.getAction().startsWith("http"))) {
                    /* 2020-10-26 */
                    final String action = br.getRegex("var domain = \"(https?://[^<>\"]+)\";").getMatch(0);
                    if (action != null) {
                        finalform.setAction(action);
                    } else {
                        finalform.setAction("/links/go");
                    }
                }
                br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.getHeaders().put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                br.getHeaders().put("Origin", "https://" + br.getHost());
                String waitStr = br.getRegex("\"counter_value\"\\s*:\\s*\"(\\d+)\"").getMatch(0);
                if (waitStr == null) {
                    waitStr = br.getRegex(">\\s*Please Wait\\s*(\\d+)s\\s*<").getMatch(0);
                    if (waitStr == null) {
                        /* 2018-12-12: E.g. rawabbet.com[RIP 2019-02-21] */
                        waitStr = br.getRegex("class=\"timer\">\\s*(\\d+)\\s*<").getMatch(0);
                    }
                }
                if (!skipWait) {
                    int wait = 15;
                    if (waitStr != null) {
                        logger.info("Found waittime in html, waiting (seconds): " + waitStr);
                        wait = Integer.parseInt(waitStr) * +1;
                        this.sleep(wait * 1000, param);
                    } else {
                        logger.info("Failed to find waittime in html");
                    }
                } else {
                    if (waitStr != null) {
                        logger.info("Skipping waittime (seconds): " + waitStr);
                    } else {
                        logger.info("Skipping waittime");
                    }
                    /* 2021-03-26: Wait some extra time otherwise we might get error 400 bad request */
                    final int extraSeconds = 5;
                    logger.info("Waiting extra seconds: " + extraSeconds);
                    this.sleep(extraSeconds * 1000l, param);
                }
                submitForm(finalform);
            } else {
                logger.warning("Failed to find finalform -> This will most likely result in a plugin failure");
            }
            final String finallink = getFinallink();
            if (finallink == null) {
                if (br.containsHTML("<h1>Whoops, looks like something went wrong\\.</h1>")) {
                    logger.warning("Hoster has issue");
                    throw new DecrypterRetryException(RetryReason.HOST);
                } else {
                    logger.warning("Decrypter broken for link: " + contentURL);
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            ret.add(createDownloadlink(finallink));
        }
        return ret;
    }

    private boolean waittimeIsSkippable(final String source_host) {
        if (StringUtils.isEmpty(source_host)) {
            /* WTF */
            return false;
        }
        if (domains_waittime_skippable.contains(source_host)) {
            /* Waittime is skippable for this host */
            return true;
        } else if (domains_waittime_skippable.contains(this.br.getHost())) {
            return true;
        }
        /* Waittime is NOT skippable for all other hosts */
        return false;
    }

    private Form getCaptchaForm() {
        return br.getForm(0);
    }

    /**
     * true = captcha required, false = no captcha required <br />
     *
     * @param captchatype
     *            : Type of the captcha found in js/html - required in some rare cases.
     */
    private boolean evalulateCaptcha(final CaptchaType captchatype, final String url_source) {
        // if ("yes" !== app_vars.enable_captcha) return !0;
        final String source_host = Browser.getHost(url_source);
        boolean hasCaptcha;
        // String captchaIndicatorValue = getAppVarsResult("enable_captcha");
        final String captchaIndicator = getAppVarsResult("captcha_shortlink");
        if (captchaIndicator != null) {
            /* Most website will contain this boolean-like value telling us whether we need to solve a captcha or not. */
            logger.info("Found captchaIndicator");
            if ("yes".equals(captchaIndicator)) {
                logger.info("Positive captchaIndicator --> Captcha required(?)");
                hasCaptcha = true;
            } else {
                logger.info("Negative captchaIndicator --> No captcha required(?)");
                hasCaptcha = false;
            }
        } else {
            /*
             * In some cases, we have to check for the type of the captcha to find out whether there is a captcha or not (unsafe method,
             * only needed in rare cases, see example websites in header of this class!)
             */
            if (captchatype != null) {
                hasCaptcha = true;
            } else {
                hasCaptcha = false;
            }
        }
        if (hasCaptcha && domains_captcha_skippable.contains(source_host)) {
            logger.info("Captcha should be required but current host does not require captcha");
            hasCaptcha = false;
        }
        return hasCaptcha;
    }

    private CaptchaType getCaptchaType() {
        final String captchaTypeStr = getAppVarsResult("captcha_type");
        if (StringUtils.isEmpty(captchaTypeStr)) {
            /* 2018-12-11: Special case e.g. linkdrop.net */
            final String reCaptchaV2Key = getAppVarsResult("reCAPTCHA_site_key");
            if (reCaptchaV2Key != null) {
                return CaptchaType.reCaptchaV2;
            }
            /* No captcha or plugin broken */
            return null;
        }
        if (captchaTypeStr.equalsIgnoreCase("recaptcha")) {
            /*
             * 2018-07-18: For 'recaptcha', key is in "reCAPTCHA_site_key"; for 'invisible-recaptcha', key is in
             * "invisible_reCAPTCHA_site_key" --> We can usually use "reCAPTCHA_site_key" as well (tested with urle.co) ... but it is better
             * to use the correct one instead - especially because sometimes only that one is available.
             */
            return CaptchaType.reCaptchaV2;
        } else if (captchaTypeStr.equalsIgnoreCase("invisible-recaptcha")) {
            return CaptchaType.reCaptchaV2_invisible;
        } else if (captchaTypeStr.equalsIgnoreCase("solvemedia")) {
            return CaptchaType.solvemedia;
        } else {
            return CaptchaType.WTF;
        }
    }

    // private boolean evalulateRecaptchaV2(final Form form) {
    // final String captchaBtn = form.getRegex("<div [^>]*id=\"captchaShortlink\"[^>]*>").getMatch(-1);
    // if (captchaBtn != null) {
    // /*
    // * "recaptcha" === app_vars.captcha_type && ("" === app_vars.user_id && "1" === app_vars.captcha_short_anonymous &&
    // * $("#captchaShort").length && ($("#shorten .btn-captcha").attr("disabled", "disabled"), captchaShort =
    // * grecaptcha.render("captchaShort", {
    // */
    // /*
    // * yes" === app_vars.captcha_shortlink && $("#captchaShortlink").length && ($("#link-view
    // * .btn-captcha").attr("disabled", "disabled"), captchaShortlink = grecaptcha.render("captchaShortlink", {
    // */
    // final String captchaType = getAppVarsResult("captcha_type");
    // final String userId = getAppVarsResult("user_id");
    // if ("recaptcha".equals(captchaType) && "".equals(userId)) {
    // return true;
    // }
    // }
    // // fail over, some seem to be using this
    // if (form.containsHTML("(?:id|class)=(\"|')g-recaptcha\\1")) {
    // return true;
    // } else if (form.containsHTML("invisibleCaptchaShortlink")) {
    // /* 'Invisible' reCaptchaV2 */
    // return true;
    // }
    // return false;
    // }
    //
    // private boolean evalulateSolvemedia(final Form form) {
    // final String solvemediaChallengeKey =
    // br.getRegex("app_vars\\[\\'solvemedia_challenge_key\\'\\]\\s*?=\\s*?\\'([^<>\"\\']+)\\';").getMatch(0);
    // if (form.containsHTML("adcopy_response") && solvemediaChallengeKey != null) {
    // return true;
    // }
    // return false;
    // }
    private String getAppVarsResult(final String input) {
        String result = new Regex(this.appVars, "app_vars\\['" + Pattern.quote(input) + "'\\]\\s*=\\s*'([^']*)'").getMatch(0);
        if (result == null) {
            /* 2018-07-18: json e.g. adbilty.me */
            result = PluginJSonUtils.getJson(this.appVars, input);
        }
        return result;
    }

    private String getFinallink() throws Exception {
        String finallink = null;
        if (br.getRequest().getHtmlCode().startsWith("{")) {
            /* For >90%, this json-attempt should work! */
            finallink = PluginJSonUtils.getJsonValue(br, "url");
        }
        if (inValidate(finallink) || !finallink.startsWith("http")) {
            finallink = br.getRegex("<a href=(\"|')(.*?)\\1[^>]+>\\s*Get\\s+Link\\s*</a>").getMatch(1);
            if (inValidate(finallink)) {
                finallink = br.getRegex("<a\\s+[^>]*href=(\"|')(.*?)\\1[^>]*>Continue[^<]*</a>").getMatch(1);
            }
        }
        /* 2020-02-03: clk.in: p.clk.in/?n=bla */
        if (!this.inValidate(finallink) && finallink.matches("https?://p\\.[^/]+/\\?n=.+")) {
            logger.info("Special case: Finallink seems to lead to another step");
            this.getPage(finallink);
            finallink = br.getRegex("<div class=\"button\">\\s*?<center>\\s*?<a name=\"a\"\\s*?href=\"(http[^\"]+)\">").getMatch(0);
        }
        return finallink;
    }

    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        /* Most of all providers will require the user to solve a reCaptchaV2. */
        return true;
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.MightyScript_AdLinkFly;
    }
}