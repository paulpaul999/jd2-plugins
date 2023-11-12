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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

import org.appwork.utils.StringUtils;
import org.jdownloader.controlling.UniqueAlltimeID;
import org.jdownloader.plugins.components.hds.HDSContainer;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.config.SubConfiguration;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.AccountRequiredException;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.hoster.PornflipCom;
import jd.utils.JDUtilities;

@DecrypterPlugin(revision = "$Revision: 47708 $", interfaceVersion = 3, names = { "playvids.com", "pornflip.com" }, urls = { "https?://(?:www\\.)?playvids\\.com/(?:[a-z]{2}/)?v/[A-Za-z0-9\\-_]+|https?://(?:www\\.)?playvids\\.com/(?:[a-z]{2}/)?[A-Za-z0-9\\-_]+/[A-Za-z0-9\\-_]+", "https?://(?:www\\.)?pornflip\\.com/(?:[a-z]{2}/)?v/[A-Za-z0-9\\-_]+|https?://(?:www\\.)?pornflip\\.com/(?:[a-z]{2}/)?[A-Za-z0-9\\-_]+/[A-Za-z0-9\\-_]+" })
public class PornflipComCrawler extends PluginForDecrypt {
    public PornflipComCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    /* 2017-01-25: Limited this to 1 - on too many requests we get HTTP/1.1 429 Too Many Requests. */
    @Override
    public int getMaxConcurrentProcessingInstances() {
        return 1;
    }

    private LinkedHashMap<String, String> foundQualities = new LinkedHashMap<String, String>();
    private String                        title          = null;
    private String                        parameter      = null;
    private CryptedLink                   param          = null;
    private String                        videoID        = null;
    /** Settings stuff */
    private static final String           FASTLINKCHECK  = "FASTLINKCHECK";
    private static final String           ALLOW_BEST     = "ALLOW_BEST";

    @SuppressWarnings({ "static-access", "deprecation" })
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        videoID = new Regex(param.getCryptedUrl(), "(?:watch(?:\\?v=|/)|embed/|v/)([A-Za-z0-9\\-_]+)").getMatch(0);
        if (videoID == null) {
            videoID = new Regex(param.getCryptedUrl(), "https?://[^/]+/(?:[a-z]{2})?([A-Za-z0-9\\-_]+)").getMatch(0);
        }
        parameter = param.getCryptedUrl();
        /* 2017-05-10: Changed from http to https */
        parameter = parameter.replace("http://", "https://");
        this.param = param;
        br.setFollowRedirects(true);
        if (plugin == null) {
            plugin = this.getNewPluginForHostInstance(this.getHost());
        }
        ((PornflipCom) plugin).prepBrowser(br);
        // Log in if possible to get 720p quality
        getUserLogin(false);
        getPage(parameter);
        if (PornflipCom.isOffline(this.br)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("class=\"title-hide-user\"")) {
            /* 2020-09-01: pornflip.com */
            logger.info("Private video --> Account with permission required!");
            throw new AccountRequiredException();
        }
        /* Decrypt start */
        title = PluginJSonUtils.getJson(br, "name");
        if (title == null) {
            /* 2020-03-18 */
            title = br.getRegex("<meta itemprop=\"name\" content=\"([^<>\"]+)\" />").getMatch(0);
        }
        if (title == null) {
            /* 2022-05-23: playvids.com embed URLs */
            title = br.getRegex("id=\"mediaPlayerTitleLink\"[^>]*target=\"_blank\" href=\"[^\"]+\"[^>]*>([^<]+)</a>").getMatch(0);
        }
        if (title == null) {
            title = br.getRegex(this.videoID + "/" + "([a-z0-9\\-]+)").getMatch(0);
            if (title != null) {
                title = title.replace("-", " ").trim();
            }
        }
        if (title == null) {
            /* Final fallback */
            title = new Regex(parameter, "/([^/]+)$").getMatch(0);
            if (title != null) {
                title = title.replace("-", " ").trim();
            }
        }
        if (title == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(title);
        /** Decrypt qualities START */
        foundQualities = ((PornflipCom) plugin).getQualities(this.br);
        if (foundQualities == null || foundQualities.isEmpty()) {
            /*
             * 2020-09-15: Assume that content is offline as they got a lot of different URLs all with a pattern matching the one of single
             * videos.
             */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /** Decrypt qualities END */
        /** Decrypt qualities, selected by the user */
        final SubConfiguration cfg = SubConfiguration.getConfig(this.getHost());
        final boolean best = cfg.getBooleanProperty(ALLOW_BEST, false);// currently the help text to best doesn't imply that it works on
        // selected resolutions only, maybe add another option for this
        final boolean q360p = cfg.getBooleanProperty(PornflipCom.ALLOW_360P, true);
        final boolean q480p = cfg.getBooleanProperty(PornflipCom.ALLOW_480P, true);
        final boolean q720p = cfg.getBooleanProperty(PornflipCom.ALLOW_720P, true);
        final boolean q1080p = cfg.getBooleanProperty(PornflipCom.ALLOW_1080, true);
        final boolean q2160p = cfg.getBooleanProperty(PornflipCom.ALLOW_2160, true);
        final boolean all = best || (q360p == false && q480p == false && q720p == false && q1080p == false && q2160p == false);
        final ArrayList<String> selectedQualities = new ArrayList<String>();
        final HashMap<String, List<DownloadLink>> results = new HashMap<String, List<DownloadLink>>();
        if (q2160p || all) {
            selectedQualities.add("2160");
        }
        if (q1080p || all) {
            selectedQualities.add("1080");
        }
        if (q720p || all) {
            selectedQualities.add("720");
        }
        if (q480p || all) {
            selectedQualities.add("480");
        }
        if (q360p || all) {
            selectedQualities.add("360");
        }
        for (final String selectedQuality : selectedQualities) {
            final Iterator<Entry<String, String>> iterator = foundQualities.entrySet().iterator();
            while (iterator.hasNext()) {
                final Entry<String, String> entry = iterator.next();
                final String qualityKey = entry.getKey();
                if (qualityKey.contains(selectedQuality)) {
                    final List<DownloadLink> ret = getVideoDownloadlinks(entry.getKey());
                    if (ret != null) {
                        // tempList = new ArrayList<DownloadLink>();
                        results.put(entry.getKey(), ret);
                    }
                }
            }
        }
        if (results.size() == 0) {
            logger.info("None of the selected qualities were found --> Adding all instead");
            final Iterator<Entry<String, String>> iterator = foundQualities.entrySet().iterator();
            while (iterator.hasNext()) {
                final Entry<String, String> entry = iterator.next();
                final List<DownloadLink> ret = getVideoDownloadlinks(entry.getKey());
                if (ret != null) {
                    // tempList = new ArrayList<DownloadLink>();
                    results.put(entry.getKey(), ret);
                }
            }
        }
        int bestQuality = 0;
        DownloadLink bestDownloadurl = null;
        for (List<DownloadLink> list : results.values()) {
            for (DownloadLink link : list) {
                fp.add(link);
                decryptedLinks.add(link);
                final String qualityStr = new Regex(link.getFinalFileName(), "(\\d+)\\.mp4").getMatch(0);
                if (qualityStr != null) {
                    final int qualityTmp = Integer.parseInt(qualityStr);
                    if (qualityTmp > bestQuality) {
                        bestQuality = qualityTmp;
                        bestDownloadurl = link;
                    }
                }
            }
        }
        if (best && bestDownloadurl != null) {
            decryptedLinks.clear();
            decryptedLinks.add(bestDownloadurl);
        }
        return decryptedLinks;
    }

    @SuppressWarnings("deprecation")
    private List<DownloadLink> getVideoDownloadlinks(final String qualityValue) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String directlink = foundQualities.get(qualityValue);
        if (directlink != null) {
            if (StringUtils.containsIgnoreCase(qualityValue, "hds_")) {
                final Browser brc = br.cloneBrowser();
                brc.getPage(directlink);
                brc.followRedirect();
                final List<HDSContainer> containers = HDSContainer.getHDSQualities(brc);
                if (containers != null) {
                    for (final HDSContainer container : containers) {
                        String fname = title + "_" + qualityValue;
                        final DownloadLink link = new DownloadLink(JDUtilities.getPluginForHost(this.getHost()), null, this.getHost(), "http://playviddecrypted.com/" + UniqueAlltimeID.create(), true);
                        link.setProperty("directlink", directlink);
                        link.setProperty("qualityvalue", qualityValue);
                        link.setProperty("mainlink", parameter);
                        if (container.getHeight() != -1) {
                            fname += "_" + container.getHeight() + "p";
                        }
                        fname += ".mp4";
                        link.setProperty("directname", fname);
                        link.setFinalFileName(fname);
                        link.setContentUrl(parameter);
                        container.write(link);
                        link.setAvailable(true);
                        if (container.getEstimatedFileSize() > 0) {
                            link.setDownloadSize(container.getEstimatedFileSize());
                        }
                        if (videoID != null) {
                            link.setLinkID(getHost() + "//" + videoID + "/" + qualityValue + "/" + container.getInternalID());
                        }
                        ret.add(link);
                    }
                }
            } else {
                /* Small workaround */
                final String qualityIndicatorForFilename = qualityValue.replace("data-hls-src", "hls_");
                final String fname = title + "_" + qualityIndicatorForFilename + ".mp4";
                final DownloadLink dl = new DownloadLink(JDUtilities.getPluginForHost(this.getHost()), null, this.getHost(), "http://playviddecrypted.com/" + UniqueAlltimeID.create(), true);
                dl.setProperty("directlink", directlink);
                dl.setProperty("qualityvalue", qualityValue);
                dl.setProperty("mainlink", parameter);
                dl.setProperty("directname", fname);
                dl.setLinkID(fname);
                dl.setFinalFileName(fname);
                dl.setContentUrl(parameter);
                if (videoID != null) {
                    dl.setLinkID(getHost() + "//" + videoID + "/" + qualityValue);
                }
                if (SubConfiguration.getConfig(this.getHost()).getBooleanProperty(FASTLINKCHECK, false)) {
                    dl.setAvailable(true);
                }
                ret.add(dl);
            }
        }
        if (ret.size() > 0) {
            return ret;
        } else {
            return null;
        }
    }

    /* Go through bot-protection. */
    private void getPage(final String url) throws Exception {
        br.getPage(url);
        if (br.getHttpConnection().getResponseCode() == 429) {
            boolean failed = true;
            for (int i = 0; i <= 2; i++) {
                final Form captchaform = br.getFormbyKey("secimgkey");
                final String secimgkey = captchaform.getInputField("secimgkey").getValue();
                final String captchaurl = "http://www." + br.getHost() + "/ccapimg?key=" + secimgkey;
                final String code = this.getCaptchaCode(captchaurl, this.param);
                captchaform.put("secimginp", code);
                br.submitForm(captchaform);
                failed = br.getHttpConnection().getResponseCode() == 429;
                if (failed) {
                    continue;
                }
                break;
            }
            if (failed) {
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
        }
    }

    /**
     * JD2 CODE: DO NOIT USE OVERRIDE FÒR COMPATIBILITY REASONS!!!!!
     */
    public boolean isProxyRotationEnabledForLinkCrawler() {
        return false;
    }

    private PluginForHost plugin = null;

    private boolean getUserLogin(final boolean force) throws Exception {
        final Account aa = AccountController.getInstance().getValidAccount(this.getHost());
        if (aa == null) {
            logger.warning("There is no account available...");
            return false;
        }
        try {
            ((jd.plugins.hoster.PornflipCom) JDUtilities.getPluginForHost(this.getHost())).login(this.br, aa, force);
        } catch (final PluginException e) {
            return false;
        }
        return true;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}