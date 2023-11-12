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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.components.config.BangComConfig;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.HTMLParser;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.BangCom;

@DecrypterPlugin(revision = "$Revision: 48108 $", interfaceVersion = 3, names = {}, urls = {})
@PluginDependencies(dependencies = { BangCom.class })
public class BangComCrawler extends PluginForDecrypt {
    public BangComCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    private static List<String[]> getPluginDomains() {
        return BangCom.getPluginDomains();
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(?:dvd|video)/[\\w\\-]+/[a-z0-9\\-]+");
        }
        return ret.toArray(new String[0]);
    }

    private final String PATTERN_VIDEO          = "(?i)https?://[^/]+/video/([\\w\\-]+)/([a-z0-9\\-]+)";
    private final String PATTERN_VIDEO_DOWNLOAD = "(?i)https?://[^/]+/video/download/([a-z0-9\\-]+)";
    private final String PATTERN_SET            = "(?i)https?://[^/]+/dvd/([\\w\\-]+)/([a-z0-9\\-]+)";

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        if (param.getCryptedUrl().matches(PATTERN_SET)) {
            return crawlSet(param.getCryptedUrl(), account);
        } else {
            return crawlVideo(param.getCryptedUrl(), account, PluginJsonConfig.get(BangComConfig.class));
        }
    }

    /** Crawls set of multiple videos. */
    public <QualitySelectionMode> ArrayList<DownloadLink> crawlSet(final String contenturl, final Account account) throws Exception {
        final Regex seturl = new Regex(contenturl, PATTERN_SET);
        final String setSlug = seturl.getMatch(1);
        final BangComConfig cfg = PluginJsonConfig.get(BangComConfig.class);
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        if (userHasDisabledCrawler(account, cfg)) {
            logger.info("Returning nothing because user has deselected all qualities -> Disabled crawler");
            return ret;
        }
        if (account != null) {
            final BangCom plg = (BangCom) this.getNewPluginForHostInstance(this.getHost());
            plg.login(account, true, contenturl);
        } else {
            br.getPage(contenturl);
        }
        final String setTitle = br.getRegex("property=\"og:title\" content=\"([^\"]+)").getMatch(0);
        final FilePackage fp = FilePackage.getInstance();
        if (setTitle != null) {
            fp.setName(Encoding.htmlDecode(setTitle).trim());
        } else {
            /* Fallback */
            fp.setName(setSlug);
        }
        /* Allow all items of this set to go into one package. */
        fp.setAllowInheritance(true);
        final HashSet<String> dupes = new HashSet<String>();
        final String[] allURLs = HTMLParser.getHttpLinks(br.getRequest().getHtmlCode(), br.getURL());
        for (final String url : allURLs) {
            if (url.matches(PATTERN_VIDEO) && dupes.add(url)) {
                final DownloadLink video = this.createDownloadlink(url);
                video._setFilePackage(fp);
                ret.add(video);
            }
        }
        logger.info("Number of items found in set: " + ret.size());
        return ret;
    }

    public <QualitySelectionMode> ArrayList<DownloadLink> crawlVideo(String url, final Account account, final BangComConfig cfg) throws Exception {
        if (StringUtils.isEmpty(url)) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /* Small workaround */
        final String videoidIfURLMatchesSpecialPattern = new Regex(url, PATTERN_VIDEO_DOWNLOAD).getMatch(0);
        if (videoidIfURLMatchesSpecialPattern != null) {
            url = "https://www." + this.getHost() + "/video/" + videoidIfURLMatchesSpecialPattern;
        }
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final List<String> knownVideoQualities = Arrays.asList(new String[] { "2160p", "1080p", "720p", "540p", "480p", "360p" });
        final List<String> selectedVideoQualities = new ArrayList<String>();
        if (cfg == null) {
            selectedVideoQualities.addAll(knownVideoQualities);
        } else {
            if (userHasDisabledCrawler(account, cfg)) {
                logger.info("Returning nothing because user has deselected all qualities -> Disabled crawler");
                return ret;
            }
            if (cfg.isCrawl2160p()) {
                selectedVideoQualities.add(knownVideoQualities.get(0));
            }
            if (cfg.isCrawl1080p()) {
                selectedVideoQualities.add(knownVideoQualities.get(1));
            }
            if (cfg.isCrawl720p()) {
                selectedVideoQualities.add(knownVideoQualities.get(2));
            }
            if (cfg.isCrawl540p()) {
                selectedVideoQualities.add(knownVideoQualities.get(3));
            }
            if (cfg.isCrawl480p()) {
                selectedVideoQualities.add(knownVideoQualities.get(4));
            }
            if (cfg.isCrawl360p()) {
                selectedVideoQualities.add(knownVideoQualities.get(5));
            }
        }
        br.setFollowRedirects(true);
        final BangCom plg = (BangCom) this.getNewPluginForHostInstance(this.getHost());
        if (account != null) {
            plg.login(account, true, url);
        } else {
            br.getPage(url);
        }
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        Map<String, Object> videoObject = null;
        final String[] jsSnippets = br.getRegex("<script type=\"application/ld\\+json\">(.*?)</script>").getColumn(0);
        for (final String jsSnippet : jsSnippets) {
            final Map<String, Object> entries = restoreFromString(jsSnippet, TypeRef.MAP);
            final String type = (String) entries.get("@type");
            if (StringUtils.equalsIgnoreCase(type, "VideoObject")) {
                videoObject = entries;
                break;
            }
        }
        if (videoObject == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String contentID = videoObject.get("@id").toString();
        String title = videoObject.get("name").toString();
        title = Encoding.htmlDecode(title).trim();
        final String thumbnailUrl = videoObject.get("thumbnailUrl").toString(); // always available
        final String previewURL = videoObject.get("contentUrl").toString(); // always available
        final String description = (String) videoObject.get("description");
        final String photosAsZipURL = br.getRegex("\"(https?://photos\\.[^/]+/[^\"]*\\.zip[^\"]+)\"").getMatch(0); // not always available
        if (StringUtils.isEmpty(previewURL) || StringUtils.isEmpty(thumbnailUrl)) {
            /* Both thumbnail and preview-video should always be available. */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (cfg == null || cfg.isGrabThumbnail()) {
            final String finalThumbnailURL;
            final String thumbnailURLWithQualityParam = new Regex(thumbnailUrl, "(http.+)\\?p=\\d+p$").getMatch(0);
            if (thumbnailURLWithQualityParam != null) {
                logger.info("Trick to get higher thumbnail quality was successful");
                finalThumbnailURL = thumbnailURLWithQualityParam;
            } else {
                logger.warning("Trick to get higher thumbnail quality was unsuccessful");
                finalThumbnailURL = thumbnailUrl;
            }
            final String filename = Plugin.getFileNameFromURL(finalThumbnailURL);
            final DownloadLink thumb = new DownloadLink(plg, null, this.getHost(), finalThumbnailURL, true);
            if (filename != null) {
                thumb.setName(filename);
            }
            thumb.setProperty(BangCom.PROPERTY_QUALITY_IDENTIFIER, "THUMBNAIL");
            ret.add(thumb);
        }
        /*
         * Enforce preview video download if no account is given as without account we will not be able to download any full length streams.
         */
        if (cfg == null || cfg.isGrabPreviewVideo() || account == null) {
            final DownloadLink preview = new DownloadLink(plg, null, this.getHost(), previewURL, true);
            preview.setProperty(BangCom.PROPERTY_QUALITY_IDENTIFIER, "PREVIEW");
            ret.add(preview);
        }
        if (photosAsZipURL != null && (cfg == null || cfg.isGrabPhotosZipArchive())) {
            final DownloadLink zip = new DownloadLink(plg, null, this.getHost(), Encoding.htmlDecode(photosAsZipURL), true);
            zip.setProperty(BangCom.PROPERTY_QUALITY_IDENTIFIER, "ZIP");
            ret.add(zip);
        }
        String videodownloadsJson = null;
        final String videodownloadsSource = br.getRegex("data-videoplayer-video-value=\"([^\"]+)").getMatch(0);
        if (videodownloadsSource != null) {
            videodownloadsJson = Encoding.htmlDecode(videodownloadsSource);
        } else {
            if (account != null && account.getType() == AccountType.PREMIUM) {
                /* We expect this html snippet to be given whenever the user owns a premium account. */
                logger.warning("Failed videodownloadsSource --> Possible plugin failure");
            }
        }
        videoCrawler: if (videodownloadsJson != null) {
            /* Video streams are only available for premium users */
            final ArrayList<DownloadLink> allVideoItems = new ArrayList<DownloadLink>();
            final ArrayList<DownloadLink> selectedVideoItems = new ArrayList<DownloadLink>();
            DownloadLink bestVideo = null;
            int pixelHeightBest = -1;
            DownloadLink bestVideoOfSelection = null;
            int pixelHeightBestOfSelection = -1;
            final Map<String, Object> entries = restoreFromString(videodownloadsJson, TypeRef.MAP);
            final List<Map<String, Object>> files = (List<Map<String, Object>>) entries.get("files");
            // final String[] videoQualityLabels = br.getRegex("</svg>([^<]+)</span>").getColumn(0);
            for (final Map<String, Object> file : files) {
                final String videoDownloadurl = file.get("url").toString();
                final Number fileSizeO = (Number) file.get("fileSize");
                String pixelHeightStr = new Regex(videoDownloadurl, "(\\d+)p\\.mp4").getMatch(0);
                if (pixelHeightStr == null) {
                    pixelHeightStr = new Regex(videoDownloadurl, "\\d+x(\\d+)-\\d+k\\.").getMatch(0);
                }
                final int pixelHeight;
                if (pixelHeightStr != null) {
                    pixelHeight = Integer.parseInt(pixelHeightStr);
                } else {
                    pixelHeight = -1;
                }
                final DownloadLink video = new DownloadLink(plg, null, this.getHost(), videoDownloadurl, true);
                if (fileSizeO != null) {
                    video.setDownloadSize(fileSizeO.longValue());
                }
                if (files.size() == 1) {
                    video.setProperty(BangCom.PROPERTY_QUALITY_IDENTIFIER, BangCom.QUALITY_IDENTIFIER_SINGLEVIDEO);
                } else {
                    video.setProperty(BangCom.PROPERTY_QUALITY_IDENTIFIER, pixelHeightStr + "p");
                }
                if (selectedVideoQualities.contains(pixelHeightStr + "p")) {
                    selectedVideoItems.add(video);
                    /* Determine best quality within selected qualities */
                    if (bestVideoOfSelection == null || pixelHeight > pixelHeightBestOfSelection) {
                        pixelHeightBestOfSelection = pixelHeight;
                        bestVideoOfSelection = video;
                    }
                }
                /* Determine best overall quality */
                if (bestVideo == null || pixelHeight > pixelHeightBest) {
                    pixelHeightBest = pixelHeight;
                    bestVideo = video;
                }
                allVideoItems.add(video);
            }
            if (allVideoItems.isEmpty()) {
                logger.warning("Failed to find any video items");
                break videoCrawler;
            }
            if (cfg != null) {
                final BangComConfig.QualitySelectionMode mode = cfg.getQualitySelectionMode();
                if (mode == BangComConfig.QualitySelectionMode.BEST && bestVideo != null) {
                    ret.add(bestVideo);
                } else if (mode == BangComConfig.QualitySelectionMode.BEST_OF_SELECTED && bestVideoOfSelection != null) {
                    ret.add(bestVideoOfSelection);
                } else if (mode == BangComConfig.QualitySelectionMode.ALL_SELECTED && selectedVideoItems.size() > 0) {
                    ret.addAll(selectedVideoItems);
                } else {
                    /* Fallback */
                    logger.info("No results according to user quality selection -> Adding all video qualities");
                    ret.addAll(allVideoItems);
                }
            } else {
                ret.addAll(allVideoItems);
            }
        } else {
            if (account != null) {
                logger.warning("Failed to find any video streams although account is available -> Possible plugin failure!");
            }
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(title);
        if (!StringUtils.isEmpty(description)) {
            fp.setComment(description);
        }
        /* Set additional properties. */
        for (final DownloadLink result : ret) {
            result._setFilePackage(fp);
            result.setContainerUrl(br.getURL());
            result.setProperty(BangCom.PROPERTY_MAINLINK, br.getURL());
            result.setProperty(BangCom.PROPERTY_CONTENT_ID, contentID);
            result.setProperty(BangCom.PROPERTY_TITLE, title);
            result.setAvailable(true);
        }
        return ret;
    }

    private boolean userHasDisabledCrawler(final Account account, final BangComConfig cfg) {
        if (cfg == null) {
            return false;
        }
        if (account != null) {
            int numberofSelectedVideoQualities = 0;
            if (cfg.isCrawl2160p()) {
                numberofSelectedVideoQualities++;
            }
            if (cfg.isCrawl1080p()) {
                numberofSelectedVideoQualities++;
            }
            if (cfg.isCrawl720p()) {
                numberofSelectedVideoQualities++;
            }
            if (cfg.isCrawl540p()) {
                numberofSelectedVideoQualities++;
            }
            if (cfg.isCrawl480p()) {
                numberofSelectedVideoQualities++;
            }
            if (cfg.isCrawl360p()) {
                numberofSelectedVideoQualities++;
            }
            if (!cfg.isGrabPreviewVideo() && !cfg.isGrabThumbnail() && !cfg.isGrabPhotosZipArchive() && numberofSelectedVideoQualities == 0) {
                return true;
            }
        } else {
            /* No account available -> Ignore selected video qualities because users can only crawl preview/thumbnail anyways. */
            if (!cfg.isGrabPreviewVideo() && !cfg.isGrabThumbnail()) {
                return true;
            }
        }
        return false;
    }
}
