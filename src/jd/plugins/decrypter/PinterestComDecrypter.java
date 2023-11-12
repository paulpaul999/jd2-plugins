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

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.uio.UIOManager;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.URLEncode;
import org.jdownloader.plugins.controller.LazyPlugin;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.hoster.PinterestCom;
import jd.utils.JDUtilities;

@DecrypterPlugin(revision = "$Revision: 48363 $", interfaceVersion = 3, names = {}, urls = {})
@PluginDependencies(dependencies = { PinterestCom.class })
public class PinterestComDecrypter extends PluginForDecrypt {
    public PinterestComDecrypter(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.IMAGE_GALLERY, LazyPlugin.FEATURE.IMAGE_HOST };
    }

    public static List<String[]> getPluginDomains() {
        return PinterestCom.getPluginDomains();
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
            ret.add("https?://(?:(?:www|[a-z]{2})\\.)?" + buildHostsPatternPart(domains) + "/(pin/[A-Za-z0-9\\-_]+/|[^/]+/[^/]+/(?:[^/]+/)?)");
        }
        return ret.toArray(new String[0]);
    }

    private static final boolean force_api_usage                     = true;
    // private ArrayList<DownloadLink> decryptedLinks = null;
    private ArrayList<String>    dupeList                            = new ArrayList<String>();
    private boolean              enable_description_inside_filenames = false;
    private boolean              enable_crawl_alternative_URL        = false;
    public static final String   TYPE_PIN                            = "(?i)https?://[^/]+/pin/([A-Za-z0-9\\-_]+)/?";
    private static final String  TYPE_BOARD                          = "(?i)https?://[^/]+/([^/]+)/([^/]+)/?";
    private static final String  TYPE_BOARD_SECTION                  = "(?i)https?://[^/]+/[^/]+/[^/]+/([^/]+)/?";

    @SuppressWarnings({ "deprecation" })
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final PluginForHost hostPlugin = this.getNewPluginForHostInstance(this.getHost());
        enable_description_inside_filenames = hostPlugin.getPluginConfig().getBooleanProperty(PinterestCom.ENABLE_DESCRIPTION_IN_FILENAMES, PinterestCom.defaultENABLE_DESCRIPTION_IN_FILENAMES);
        enable_crawl_alternative_URL = hostPlugin.getPluginConfig().getBooleanProperty(PinterestCom.ENABLE_CRAWL_ALTERNATIVE_SOURCE_URLS, PinterestCom.defaultENABLE_CRAWL_ALTERNATIVE_SOURCE_URLS);
        br.setFollowRedirects(true);
        final Regex singlepinregex = (new Regex(param.getCryptedUrl(), TYPE_PIN));
        if (singlepinregex.patternFind()) {
            return crawlSinglePIN(singlepinregex.getMatch(0));
        } else {
            return crawlBoardPINs(param.getCryptedUrl());
        }
    }

    private ArrayList<DownloadLink> crawlSinglePIN(final String pinID) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        String contenturl = "https://www." + this.getHost() + "/pin/" + pinID + "/";
        final DownloadLink singlePIN = this.createDownloadlink(contenturl);
        if (enable_crawl_alternative_URL) {
            /* The more complicated way (if wished by user). */
            /**
             * 2021-03-02: PINs may redirect to other PINs in very rare cases -> Handle that </br>
             * If that wasn't the case, we could rely on API-only!
             */
            br.getPage(contenturl);
            String redirect = br.getRegex("window\\.location\\s*=\\s*\"([^\"]+)\"").getMatch(0);
            if (redirect != null) {
                /* We want the full URL. */
                redirect = br.getURL(redirect).toExternalForm();
            }
            if (!br.getURL().matches(PinterestComDecrypter.TYPE_PIN)) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (redirect != null && redirect.matches(PinterestComDecrypter.TYPE_PIN) && !redirect.contains(pinID)) {
                final String newPinID = PinterestCom.getPinID(redirect);
                logger.info("Old pinID: " + pinID + " | New pinID: " + newPinID + " | New URL: " + redirect);
                contenturl = redirect;
            } else if (redirect != null && redirect.contains("show_error=true")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final Map<String, Object> pinMap = getPINMap(this.br, contenturl);
            setInfoOnDownloadLink(singlePIN, pinMap);
            final String externalURL = getAlternativeExternalURLInPINMap(pinMap);
            if (externalURL != null) {
                ret.add(this.createDownloadlink(externalURL));
            }
        }
        ret.add(singlePIN);
        return ret;
    }

    public static void setInfoOnDownloadLink(final DownloadLink dl, final Map<String, Object> pinMap) {
        final String pin_id = jd.plugins.hoster.PinterestCom.getPinID(dl.getPluginPatternMatcher());
        String filename = null;
        final Map<String, Object> data = pinMap.containsKey("data") ? (Map<String, Object>) pinMap.get("data") : pinMap;
        String directlink = getDirectlinkFromPINMap(data);
        if (StringUtils.isEmpty(filename)) {
            filename = (String) data.get("title");
        }
        if (StringUtils.isEmpty(filename)) {
            /* Fallback */
            filename = pin_id;
        } else {
            filename = Encoding.htmlDecode(filename).trim();
            filename = pin_id + "_" + filename;
        }
        final String description = (String) data.get("description");
        final String ext;
        if (!StringUtils.isEmpty(directlink)) {
            if (directlink.contains(".m3u8")) {
                /* HLS stream */
                ext = ".mp4";
            } else {
                ext = getFileNameExtensionFromString(directlink, ".jpg");
            }
        } else {
            ext = ".jpg";
        }
        final PluginForHost hostPlugin = JDUtilities.getPluginForHost(dl.getHost());
        if (hostPlugin.getPluginConfig().getBooleanProperty(jd.plugins.hoster.PinterestCom.ENABLE_DESCRIPTION_IN_FILENAMES, jd.plugins.hoster.PinterestCom.defaultENABLE_DESCRIPTION_IN_FILENAMES) && !StringUtils.isEmpty(description)) {
            filename += "_" + description;
        }
        if (!StringUtils.isEmpty(description) && dl.getComment() == null) {
            dl.setComment(description);
        }
        if (!filename.endsWith(ext)) {
            filename += ext;
        }
        if (directlink != null) {
            dl.setProperty(PinterestCom.PROPERTY_DIRECTURL, directlink);
        }
        dl.setFinalFileName(filename);
        dl.setLinkID(PinterestCom.getLinkidForInternalDuplicateCheck(dl.getPluginPatternMatcher(), directlink));
        dl.setAvailable(true);
    }

    /** Accesses pinterest API and retrn map of PIN. */
    public static Map<String, Object> getPINMap(final Browser br, final String pinURL) throws Exception {
        final String pinID = jd.plugins.hoster.PinterestCom.getPinID(pinURL);
        if (pinID == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        List<Object> resource_data_cache = null;
        final String pin_json_url = "https://www.pinterest.com/resource/PinResource/get/?source_url=%2Fpin%2F" + pinID + "%2F&data=%7B%22options%22%3A%7B%22field_set_key%22%3A%22detailed%22%2C%22ptrf%22%3Anull%2C%22fetch_visual_search_objects%22%3Atrue%2C%22id%22%3A%22" + pinID + "%22%7D%2C%22context%22%3A%7B%7D%7D&module_path=Pin(show_pinner%3Dtrue%2C+show_board%3Dtrue%2C+is_original_pin_in_related_pins_grid%3Dtrue)&_=" + System.currentTimeMillis();
        br.getPage(pin_json_url);
        final Map<String, Object> root = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
        if (root.containsKey("resource_data_cache")) {
            resource_data_cache = (List) root.get("resource_data_cache");
        } else {
            /* 2020-02-17 */
            final Object pinO = root.get("resource_response");
            if (pinO != null) {
                resource_data_cache = new ArrayList<Object>();
                resource_data_cache.add(pinO);
            }
        }
        if (resource_data_cache == null) {
            return null;
        }
        for (final Object resource_object : resource_data_cache) {
            final Map<String, Object> map = (Map<String, Object>) resource_object;
            final String this_pin_id = (String) JavaScriptEngineFactory.walkJson(map, "data/id");
            if (StringUtils.equals(this_pin_id, pinID) || resource_data_cache.size() == 1) {
                /* We've reached our goal */
                return map;
            }
        }
        return null;
    }

    /** 2020-11-16 */
    public static void followSpecialRedirect(final Browser br) throws IOException, PluginException {
        final String redirect = br.getRegex("window\\.location = \"([^<>\"]+)\"").getMatch(0);
        if (redirect != null) {
            if (redirect.contains("show_error=true")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            br.getPage(redirect);
        }
    }

    /** Returns highest resolution image URL inside given PIN Map. */
    public static String getDirectlinkFromPINMap(final Map<String, Object> pinMap) {
        if (pinMap == null) {
            return null;
        }
        /* First check if we have a video */
        final Map<String, Object> video_list = (Map<String, Object>) (JavaScriptEngineFactory.walkJson(pinMap, "videos/video_list"));
        if (video_list != null) {
            for (final String videoQuality : new String[] { "V_1080P", "V_720P", "V_480P" }) {
                final Map<String, Object> video = (Map<String, Object>) video_list.get(videoQuality);
                if (video != null && video.get("url") != null) {
                    return video.get("url").toString();
                }
            }
        }
        /* No video --> Look for photo link */
        final Map<String, Object> imagesO = (Map<String, Object>) pinMap.get("images");
        Map<String, Object> single_pinterest_images_original = null;
        if (imagesO != null) {
            single_pinterest_images_original = (Map<String, Object>) imagesO.get("orig");
        }
        // final Object pinner_nameo = single_pinterest_pinner != null ? single_pinterest_pinner.get("full_name") : null;
        Map<String, Object> tempmap = null;
        String directlink = null;
        if (single_pinterest_images_original != null) {
            /* Original image available --> Take that */
            directlink = (String) single_pinterest_images_original.get("url");
        } else {
            if (imagesO != null) {
                /* Original image NOT available --> Take the best we can find */
                final Iterator<Entry<String, Object>> it = imagesO.entrySet().iterator();
                while (it.hasNext()) {
                    final Entry<String, Object> ipentry = it.next();
                    tempmap = (Map<String, Object>) ipentry.getValue();
                    /* First image = highest (but original is somewhere 'in the middle') */
                    break;
                }
                directlink = tempmap != null ? (String) tempmap.get("url") : null;
            }
        }
        return directlink;
    }

    /** Returns e.g. an alternative, probably higher quality imgur.com URL to the same image which we have as Pinterest PIN here. */
    private String getAlternativeExternalURLInPINMap(final Map<String, Object> pinMap) {
        String externalURL = null;
        try {
            String path;
            if (pinMap.containsKey("data")) {
                path = "data/rich_metadata/url";
            } else {
                path = "rich_metadata/url";
            }
            externalURL = (String) JavaScriptEngineFactory.walkJson(pinMap, path);
        } catch (final Throwable e) {
        }
        return externalURL;
    }

    /**
     * @return: true: target section was found and only this will be crawler false: failed to find target section - in this case we should
     *          crawl everything we find </br>
     *          This can crawl A LOT of stuff! E.g. a board contains 1000 sections, each section contains 1000 PINs...
     */
    private ArrayList<DownloadLink> crawlSections(final String contenturl, final Browser ajax, final String boardID, final long totalInsideSectionsPinCount) throws Exception {
        final String username_and_boardname = new Regex(contenturl, "https?://[^/]+/(.+)/").getMatch(0).replace("/", " - ");
        final Map<String, Object> postDataOptions = new HashMap<String, Object>();
        final String source_url = new URL(contenturl).getPath();
        postDataOptions.put("isPrefetch", false);
        postDataOptions.put("board_id", boardID);
        postDataOptions.put("redux_normalize_feed", true);
        postDataOptions.put("no_fetch_context_on_resource", false);
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final Map<String, Object> postData = new HashMap<String, Object>();
        postData.put("options", postDataOptions);
        postData.put("context", new HashMap<String, Object>());
        int sectionPage = -1;
        ajax.getPage("/resource/BoardSectionsResource/get/?source_url=" + Encoding.urlEncode(source_url) + "&data=" + URLEncode.encodeURIComponent(JSonStorage.serializeToJson(postData)));
        final int maxSectionsPerPage = 25;
        sectionPagination: do {
            sectionPage += 1;
            logger.info("Crawling sections page: " + (sectionPage + 1));
            final Map<String, Object> sectionsData = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(ajax.toString());
            // Map<String, Object> json_root = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(ajax.toString());
            final List<Object> sections = (List) JavaScriptEngineFactory.walkJson(sectionsData, "resource_response/data");
            int sectionCounter = 1;
            for (final Object sectionO : sections) {
                final Map<String, Object> entries = (Map<String, Object>) sectionO;
                final String section_title = (String) entries.get("title");
                // final String sectionSlug = (String) entries.get("slug");
                final long section_total_pin_count = JavaScriptEngineFactory.toLong(entries.get("pin_count"), 0);
                final String sectionID = (String) entries.get("id");
                if (StringUtils.isEmpty(section_title) || sectionID == null || section_total_pin_count == 0) {
                    /* Skip invalid entries and empty sections */
                    continue;
                }
                logger.info("Crawling section " + sectionCounter + " of " + sections.size() + " --> ID = " + sectionID);
                final FilePackage fpSection = FilePackage.getInstance();
                fpSection.setName(username_and_boardname + " - " + section_title);
                ret.addAll(crawlSection(ajax, source_url, boardID, sectionID, fpSection));
                sectionCounter += 1;
                if (this.isAbort()) {
                    break sectionPagination;
                }
            }
            final String sectionsNextBookmark = (String) JavaScriptEngineFactory.walkJson(sectionsData, "resource_response/bookmark");
            if (StringUtils.isEmpty(sectionsNextBookmark) || sectionsNextBookmark.equalsIgnoreCase("-end-")) {
                logger.info("Stopping sections crawling because: Reached end");
                break sectionPagination;
            } else if (sections.size() < maxSectionsPerPage) {
                /* Fail safe */
                logger.info("Stopping because: Current page contains less than " + maxSectionsPerPage + " items");
                break sectionPagination;
            } else {
                postDataOptions.put("bookmarks", new String[] { sectionsNextBookmark });
                ajax.getPage("/resource/BoardSectionsResource/get/?source_url=" + Encoding.urlEncode(source_url) + "&data=" + URLEncode.encodeURIComponent(JSonStorage.serializeToJson(postData)) + "&_=" + System.currentTimeMillis());
            }
        } while (!this.isAbort());
        logger.info("Section crawler done");
        return ret;
    }

    private ArrayList<DownloadLink> crawlSection(final Browser ajax, final String source_url, final String boardID, final String sectionID, final FilePackage fp) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        int processedPINCounter = 0;
        int pageCounter = 1;
        /* Single section pagination */
        // final String url_section = "https://www.pinterest.com/" + source_url + section_title + "/";
        final int maxPINsPerRequest = 25;
        final Map<String, Object> pinPaginationPostDataOptions = new HashMap<String, Object>();
        pinPaginationPostDataOptions.put("isPrefetch", false);
        pinPaginationPostDataOptions.put("currentFilter", -1);
        pinPaginationPostDataOptions.put("field_set_key", "react_grid_pin");
        pinPaginationPostDataOptions.put("is_own_profile_pins", false);
        pinPaginationPostDataOptions.put("page_size", maxPINsPerRequest);
        pinPaginationPostDataOptions.put("redux_normalize_feed", true);
        pinPaginationPostDataOptions.put("section_id", sectionID);
        pinPaginationPostDataOptions.put("no_fetch_context_on_resource", false);
        final Map<String, Object> pinPaginationpostDataContext = new HashMap<String, Object>();
        Map<String, Object> pinPaginationPostData = new HashMap<String, Object>();
        pinPaginationPostData.put("options", pinPaginationPostDataOptions);
        pinPaginationPostData.put("context", pinPaginationpostDataContext);
        ajax.getPage("/resource/BoardSectionPinsResource/get/?source_url=" + URLEncode.encodeURIComponent(source_url) + "&data=" + URLEncode.encodeURIComponent(JSonStorage.serializeToJson(pinPaginationPostData)) + "&_=" + System.currentTimeMillis());
        do {
            logger.info("Crawling section " + sectionID + " page: " + pageCounter);
            final Map<String, Object> sectionPaginationInfo = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(ajax.toString());
            final Object bookmarksO = JavaScriptEngineFactory.walkJson(sectionPaginationInfo, "resource/options/bookmarks");
            final String bookmarks = (String) JavaScriptEngineFactory.walkJson(sectionPaginationInfo, "resource/options/bookmarks/{0}");
            final List<Object> pins = (List) JavaScriptEngineFactory.walkJson(sectionPaginationInfo, "resource_response/data");
            final int sizeBefore = ret.size();
            for (final Object pinO : pins) {
                final Map<String, Object> pinMap = (Map<String, Object>) pinO;
                final ArrayList<DownloadLink> thisRet = proccessMap(pinMap, boardID, fp);
                if (thisRet == null || thisRet.isEmpty()) {
                    logger.info("Stopping PIN pagination because: Found unprocessable PIN map");
                    break;
                } else {
                    ret.addAll(thisRet);
                    processedPINCounter++;
                }
            }
            pageCounter++;
            if (this.isAbort()) {
                logger.info("Crawler aborted by user");
                break;
            } else if (ret.size() <= sizeBefore) {
                logger.info("Stopping because: Failed to find any new items on current page");
                break;
            } else if (StringUtils.isEmpty(bookmarks) || bookmarks.equals("-end-") || bookmarksO == null) {
                /* Looks as if we've reached the end */
                logger.info("Stopping because: Reached end");
                break;
            } else {
                pinPaginationPostDataOptions.put("bookmarks", bookmarksO);
                ajax.getPage("/resource/BoardSectionPinsResource/get/?source_url=" + URLEncode.encodeURIComponent(source_url) + "&data=" + URLEncode.encodeURIComponent(JSonStorage.serializeToJson(pinPaginationPostData)) + "&_=" + System.currentTimeMillis());
            }
        } while (true);
        logger.info("Number of PINs in current section: " + processedPINCounter);
        return ret;
    }

    private ArrayList<DownloadLink> crawlBoardPINs(final String contenturl) throws Exception {
        /*
         * In case the user wants to add a specific section, we have to get to the section overview --> Find sectionID --> Finally crawl
         * section PINs
         */
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final boolean loggedIN = getUserLogin(false);
        String targetSectionSlug = null;
        final String username = URLEncode.decodeURIComponent(new Regex(contenturl, TYPE_BOARD).getMatch(0));
        final String boardSlug = URLEncode.decodeURIComponent(new Regex(contenturl, TYPE_BOARD).getMatch(1));
        // final String sourceURL;
        if (contenturl.matches(TYPE_BOARD_SECTION)) {
            /* Remove targetSection from URL as we cannot use it in this way. */
            targetSectionSlug = new Regex(contenturl, TYPE_BOARD_SECTION).getMatch(0);
        }
        final String sourceURL = URLEncode.decodeURIComponent(new URL(contenturl).getPath());
        prepAPIBRCrawler(this.br);
        /* Sometimes html can be very big */
        br.setLoadLimit(br.getLoadLimit() * 4);
        br.getPage("https://www." + this.getHost() + "/resource/BoardResource/get/?source_url=" + URLEncode.encodeURIComponent(sourceURL) + "style%2F&data=%7B%22options%22%3A%7B%22isPrefetch%22%3Afalse%2C%22username%22%3A%22" + URLEncode.encodeURIComponent(username) + "%22%2C%22slug%22%3A%22" + URLEncode.encodeURIComponent(boardSlug) + "%22%2C%22field_set_key%22%3A%22detailed%22%2C%22no_fetch_context_on_resource%22%3Afalse%7D%2C%22context%22%3A%7B%7D%7D&_=1614344870050");
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        Map<String, Object> jsonRoot = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(br.toString());
        final Map<String, Object> boardPageResource = (Map<String, Object>) JavaScriptEngineFactory.walkJson(jsonRoot, "resource_response/data");
        final String boardID = (String) boardPageResource.get("id");
        final long section_count = JavaScriptEngineFactory.toLong(boardPageResource.get("section_count"), 0);
        /* Find out how many PINs we have to crawl. */
        final long totalPinCount = JavaScriptEngineFactory.toLong(boardPageResource.get("pin_count"), 0);
        final long sectionlessPinCount = JavaScriptEngineFactory.toLong(boardPageResource.get("sectionless_pin_count"), 0);
        final long totalInsideSectionsPinCount = (totalPinCount > 0 && sectionlessPinCount < totalPinCount) ? totalPinCount - sectionlessPinCount : 0;
        logger.info("PINs total: " + totalPinCount + " | PINs inside sections: " + totalInsideSectionsPinCount + " | PINs outside sections: " + sectionlessPinCount);
        /*
         * Sections are like folders. Now find all the PINs that are not in any sections (it may happen that we already have everything at
         * this stage!) Only decrypt these leftover PINs if either the user did not want to have a specified section only or if he wanted to
         * have a specified section only but we failed to find that.
         */
        /*
         * 2018-12-11: Anonymous users officially cannot see sections even if they exist but we can crawl them the same way we do for
         * loggedIN users. Disable this if it is not possible anymore to crawl them.
         */
        final boolean enableSectionCrawlerForAnonymousUsers = true;
        final boolean allowSectionCrawling = (loggedIN || (!loggedIN && enableSectionCrawlerForAnonymousUsers));
        if (section_count > 0) {
            logger.info("Crawling section(s)");
            if (targetSectionSlug != null && allowSectionCrawling) {
                String targetSectionID = null;
                /* Small workaround to find sectionID (I've failed to find an API endpoint that returns this section only). */
                try {
                    br.getPage(contenturl);
                    final String json = this.getJsonSourceFromHTML(this.br, loggedIN);
                    final Map<String, Object> tmpMap = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(json);
                    targetSectionID = this.findSectionID(tmpMap, targetSectionSlug);
                } catch (final Throwable e) {
                    e.printStackTrace();
                    logger.warning("Exception occured during sectionID workaround");
                }
                if (targetSectionID == null) {
                    logger.info("Failed to crawl user desired section -> Crawling sectionless PINs only...");
                } else {
                    final FilePackage fpSection = FilePackage.getInstance();
                    fpSection.setName(username + " - " + boardSlug + " - " + targetSectionSlug);
                    ret.addAll(this.crawlSection(br.cloneBrowser(), sourceURL, boardID, targetSectionID, fpSection));
                    logger.info("Total number of PINs crawled in desired single section: " + ret.size());
                }
            } else {
                ret.addAll(this.crawlSections(contenturl, br.cloneBrowser(), boardID, totalInsideSectionsPinCount));
                logger.info("Total number of PINs crawled in sections: " + ret.size());
            }
        }
        if (sectionlessPinCount <= 0) {
            /* No items at all available */
            logger.info("This board doesn't contain any loose PINs");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* Find- and set PackageName (Board Name) */
        String boardName = (String) boardPageResource.get("name");
        if (StringUtils.isEmpty(boardName)) {
            /* Fallback */
            boardName = boardSlug;
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(boardName);
        if (loggedIN || force_api_usage) {
            final Map<String, Object> postDataOptions = new HashMap<String, Object>();
            final String source_url = new URL(contenturl).getPath();
            postDataOptions.put("isPrefetch", false);
            postDataOptions.put("board_id", boardID);
            postDataOptions.put("board_url", "/" + username + "/" + boardSlug);
            postDataOptions.put("currentFilter", -1);
            postDataOptions.put("field_set_key", "react_grid_pin");
            postDataOptions.put("filter_section_pins", true);
            postDataOptions.put("sort", "default");
            postDataOptions.put("layout", "default");
            postDataOptions.put("page_size", 25);
            postDataOptions.put("redux_normalize_feed", true);
            postDataOptions.put("no_fetch_context_on_resource", false);
            Map<String, Object> postData = new HashMap<String, Object>();
            postData.put("options", postDataOptions);
            postData.put("context", new HashMap<String, Object>());
            int page = 0;
            int crawledSectionlessPINs = 0;
            logger.info("Crawling all sectionless PINs: " + sectionlessPinCount);
            do {
                page += 1;
                logger.info("Crawling sectionless PINs page: " + page + " | " + crawledSectionlessPINs + " / " + sectionlessPinCount + " PINs crawled");
                br.getPage("/resource/BoardFeedResource/get/?source_url=" + Encoding.urlEncode(source_url) + "&data=" + URLEncode.encodeURIComponent(JSonStorage.serializeToJson(postData)) + "&_=" + System.currentTimeMillis());
                Map<String, Object> entries = restoreFromString(br.toString(), TypeRef.MAP);
                entries = (Map<String, Object>) entries.get("resource_response");
                final String bookmark = (String) entries.get("bookmark");
                final List<Object> pinList = (List<Object>) entries.get("data");
                for (final Object pint : pinList) {
                    final Map<String, Object> single_pinterest_data = (Map<String, Object>) pint;
                    proccessMap(single_pinterest_data, boardID, fp);
                }
                crawledSectionlessPINs += pinList.size();
                if (StringUtils.isEmpty(bookmark) || bookmark.equalsIgnoreCase("-end-")) {
                    logger.info("Stopping because: Reached end");
                    break;
                } else if (crawledSectionlessPINs >= sectionlessPinCount) {
                    /* Fail-safe */
                    logger.info("Stopping because: Found all items");
                    break;
                } else {
                    /* Continue to next page */
                    postDataOptions.put("bookmarks", new String[] { bookmark });
                }
            } while (!this.isAbort());
        } else {
            crawlBoardWebsite(contenturl);
            final int max_entries_per_page_free = 25;
            if (totalPinCount > max_entries_per_page_free) {
                UIOManager.I().showMessageDialog("Please add your pinterest.com account at Settings->Account manager to find more than " + max_entries_per_page_free + " images");
            }
        }
        return ret;
    }

    private String getJsonSourceFromHTML(final Browser br, final boolean loggedIN) {
        String json_source_from_html;
        if (loggedIN) {
            json_source_from_html = br.getRegex("id=\\'initial\\-state\\'>window\\.__INITIAL_STATE__ =(.*?)</script>").getMatch(0);
        } else {
            json_source_from_html = br.getRegex("P\\.main\\.start\\((\\{.*?\\})\\);[\t\n\r]+").getMatch(0);
            if (json_source_from_html == null) {
                json_source_from_html = br.getRegex("P\\.startArgs\\s*=\\s*(\\{.*?\\});[\t\n\r]+").getMatch(0);
            }
            /* 2018-12-11: Does not contain what we want! */
            // if (json_source_from_html == null) {
            // json_source_from_html = br.getRegex("id=\\'jsInit1\\'>(\\{.*?\\})</script>").getMatch(0);
            // }
        }
        if (json_source_from_html == null) {
            /* 2018-12-11: For loggedin- and loggedoff */
            json_source_from_html = br.getRegex("id=.initial-state.[^>]*?>(\\{.*?\\})</script>").getMatch(0);
        }
        return json_source_from_html;
    }

    /** Crawls single PIN from given Map. */
    private ArrayList<DownloadLink> proccessMap(final Map<String, Object> singlePINData, final String board_id, final FilePackage fp) throws PluginException {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String type = getStringFromJson(singlePINData, "type");
        if (type == null || !(type.equals("pin") || type.equals("interest"))) {
            /* Skip invalid objects! */
            return null;
        }
        final Map<String, Object> single_pinterest_pinner = (Map<String, Object>) singlePINData.get("pinner");
        final Object usernameo = single_pinterest_pinner != null ? single_pinterest_pinner.get("username") : null;
        final String pin_id = (String) singlePINData.get("id");
        final String username = usernameo != null ? (String) usernameo : null;
        final String directlink = getDirectlinkFromPINMap(singlePINData);
        // final String pinner_name = pinner_nameo != null ? (String) pinner_nameo : null;
        if (StringUtils.isEmpty(pin_id) || directlink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else if (dupeList.contains(pin_id)) {
            logger.info("Skipping duplicate: " + pin_id);
            return null;
        }
        dupeList.add(pin_id);
        final DownloadLink dl = this.createDownloadlink("https://www." + this.getHost() + "/pin/" + pin_id + "/");
        if (!StringUtils.isEmpty(board_id)) {
            dl.setProperty("boardid", board_id);
        }
        if (!StringUtils.isEmpty(username)) {
            dl.setProperty("username", username);
        }
        setInfoOnDownloadLink(dl, singlePINData);
        fp.add(dl);
        dl._setFilePackage(fp);
        ret.add(dl);
        distribute(dl);
        final String externalURL = getAlternativeExternalURLInPINMap(singlePINData);
        if (externalURL != null && this.enable_crawl_alternative_URL) {
            ret.add(this.createDownloadlink(externalURL));
        }
        return ret;
    }

    /* Wrapper which either returns object as String or (e.g. it is missing or different datatype), null. */
    private String getStringFromJson(final Map<String, Object> entries, final String key) {
        final String output;
        final Object jsono = entries.get(key);
        if (jsono != null && jsono instanceof String) {
            output = (String) jsono;
        } else {
            output = null;
        }
        return output;
    }

    private String getBoardID(String json_source) {
        if (json_source == null) {
            return null;
        }
        /* This board_id RegEx will usually only work when loggedOFF */
        json_source = json_source.replaceAll("\\\\", "");
        String board_id = PluginJSonUtils.getJsonValue(json_source, "board_id");
        if (board_id == null) {
            /* For LoggedIN and loggedOFF */
            board_id = this.br.getRegex("(\\d+)_board_thumbnail").getMatch(0);
            if (board_id == null) {
                board_id = new Regex(json_source, "\"board_id=\"(\\d+)\"").getMatch(0);
            }
        }
        return board_id;
    }

    /**
     * Recursive function to crawl all PINs --> Easiest way as they often change their json.
     *
     */
    @SuppressWarnings("unchecked")
    private void processPinsKamikaze(final Object jsono, final String board_id, final FilePackage fp) throws PluginException {
        Map<String, Object> test;
        if (jsono instanceof Map) {
            test = (Map<String, Object>) jsono;
            if (proccessMap(test, board_id, fp) == null) {
                final Iterator<Entry<String, Object>> it = test.entrySet().iterator();
                while (it.hasNext()) {
                    final Entry<String, Object> thisentry = it.next();
                    final Object mapObject = thisentry.getValue();
                    processPinsKamikaze(mapObject, board_id, fp);
                }
            }
        } else if (jsono instanceof ArrayList) {
            final List<Object> ressourcelist = (List<Object>) jsono;
            for (final Object listo : ressourcelist) {
                processPinsKamikaze(listo, board_id, fp);
            }
        }
    }

    /** Recursive function to find the ID of a sectionSlug. */
    private String findSectionID(final Object jsono, final String sectionSlug) throws PluginException {
        if (jsono instanceof Map) {
            final Map<String, Object> mapTmp = (Map<String, Object>) jsono;
            final Iterator<Entry<String, Object>> iterator = mapTmp.entrySet().iterator();
            while (iterator.hasNext()) {
                final Entry<String, Object> entry = iterator.next();
                final String key = entry.getKey();
                final Object value = entry.getValue();
                if (key.equals("slug") && value instanceof String && value.toString().equalsIgnoreCase(sectionSlug)) {
                    return (String) mapTmp.get("id");
                } else if (value instanceof List || value instanceof Map) {
                    final String result = findSectionID(value, sectionSlug);
                    if (result != null) {
                        return result;
                    }
                }
            }
        } else if (jsono instanceof ArrayList) {
            final List<Object> ressourcelist = (List<Object>) jsono;
            for (final Object arrayo : ressourcelist) {
                if (arrayo instanceof List || arrayo instanceof Map) {
                    final String result = findSectionID(arrayo, sectionSlug);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
        return null;
    }

    @Deprecated
    private ArrayList<DownloadLink> crawlBoardWebsite(final String contenturl) throws PluginException {
        /*
         * Also possible using json of P.start.start( to get the first 25 entries: resourceDataCache --> Last[] --> data --> Here we go --->
         * But I consider this as an unsafe method.
         */
        final String[] linkinfo = br.getRegex("<div class=\"bulkEditPinWrapper\">(.*?)class=\"creditTitle\"").getColumn(0);
        if (linkinfo == null || linkinfo.length == 0) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        for (final String sinfo : linkinfo) {
            String description = new Regex(sinfo, "title=\"([^<>\"]*?)\"").getMatch(0);
            if (description == null) {
                description = new Regex(sinfo, "<p class=\"pinDescription\">([^<>]*?)<").getMatch(0);
            }
            final String directlink = new Regex(sinfo, "\"(https?://[a-z0-9\\.\\-]+/originals/[^<>\"]*?)\"").getMatch(0);
            final String pin_id = new Regex(sinfo, "/pin/([A-Za-z0-9\\-_]+)/").getMatch(0);
            if (pin_id == null) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (dupeList.contains(pin_id)) {
                logger.info("Skipping duplicate: " + pin_id);
                continue;
            }
            dupeList.add(pin_id);
            String filename = pin_id;
            final String content_url = "http://www.pinterest.com/pin/" + pin_id + "/";
            final DownloadLink dl = createDownloadlink(content_url);
            dl.setContentUrl(content_url);
            dl.setLinkID(jd.plugins.hoster.PinterestCom.getLinkidForInternalDuplicateCheck(content_url, directlink));
            if (directlink != null) {
                dl.setProperty(PinterestCom.PROPERTY_DIRECTURL, directlink);
            }
            if (description != null) {
                dl.setComment(description);
                dl.setProperty("description", description);
                if (enable_description_inside_filenames) {
                    filename += "_" + description;
                }
            }
            dl.setProperty("decryptedfilename", filename);
            dl.setName(filename + ".jpg");
            dl.setAvailable(true);
            ret.add(dl);
            distribute(dl);
        }
        return ret;
    }

    /** Log in the account of the hostplugin */
    @SuppressWarnings({ "deprecation" })
    private boolean getUserLogin(final boolean force) throws Exception {
        final PluginForHost hostPlugin = this.getNewPluginForHostInstance(this.getHost());
        final Account aa = AccountController.getInstance().getValidAccount(hostPlugin);
        if (aa == null) {
            logger.warning("There is no account available, stopping...");
            return false;
        }
        try {
            ((jd.plugins.hoster.PinterestCom) hostPlugin).login(aa, force);
        } catch (final PluginException e) {
            return false;
        }
        return true;
    }

    private void prepAPIBRCrawler(final Browser br) throws PluginException {
        /* 2021-03-01: Not needed anymore */
        // jd.plugins.hoster.PinterestCom.prepAPIBR(br);
        br.setAllowedResponseCodes(new int[] { 503, 504 });
    }
}
