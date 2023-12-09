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
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.appwork.storage.TypeRef;
import org.appwork.utils.DebugMode;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.URLEncode;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.components.archiveorg.ArchiveOrgConfig;
import org.jdownloader.plugins.components.archiveorg.ArchiveOrgConfig.BookCrawlMode;
import org.jdownloader.plugins.components.archiveorg.ArchiveOrgConfig.PlaylistCrawlMode;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginJsonConfig;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.controlling.linkcrawler.CrawledLink;
import jd.controlling.linkcrawler.LinkCrawler;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.AccountRequiredException;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.download.HashInfo;
import jd.plugins.hoster.ArchiveOrg;

@DecrypterPlugin(revision = "$Revision: 48565 $", interfaceVersion = 2, names = { "archive.org", "subdomain.archive.org" }, urls = { "https?://(?:www\\.)?archive\\.org/((?:details|download|stream|embed)/.+|search\\?query=.+)", "https?://[^/]+\\.archive\\.org/view_archive\\.php\\?archive=[^\\&]+(?:\\&file=[^\\&]+)?" })
public class ArchiveOrgCrawler extends PluginForDecrypt {
    public ArchiveOrgCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    private boolean isArchiveURL(final String url) throws MalformedURLException {
        if (url == null) {
            return false;
        } else {
            final UrlQuery query = UrlQuery.parse(url);
            return url.contains("view_archive.php") && query.get("file") == null;
        }
    }

    private final String PATTERN_DOWNLOAD = "(?i)https?://[^/]+/download/([\\w\\-]+).*";
    private final String PATTERN_SEARCH   = "(?i)https?://[^/]+/search\\?query=.+";
    final Set<String>    dups             = new HashSet<String>();
    private ArchiveOrg   hostPlugin       = null;

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        br.setFollowRedirects(true);
        /* 2023-07-10: looks like some detail pages return 503 instead of 200 */
        br.setAllowedResponseCodes(503);
        final String contenturl = param.getCryptedUrl().replace("://www.", "://").replaceFirst("/(stream|embed)/", "/download/");
        if (new Regex(contenturl, PATTERN_DOWNLOAD).patternFind()) {
            return crawlPatternSlashDownload(contenturl);
        } else if (contenturl.matches(PATTERN_SEARCH)) {
            return this.crawlSearchQueryURL(br, param);
        } else {
            /*
             * 2020-08-26: Login might sometimes be required for book downloads.
             */
            ensureInitHosterplugin();
            final Account account = AccountController.getInstance().getValidAccount(hostPlugin.getHost());
            if (account != null) {
                hostPlugin.login(account, false);
            }
            URLConnectionAdapter con = null;
            boolean isArchiveContent = isArchiveURL(contenturl);
            if (isArchiveContent) {
                br.getPage(contenturl);
            } else {
                try {
                    /* Check if we have a direct URL --> Host plugin */
                    con = br.openGetConnection(contenturl);
                    isArchiveContent = isArchiveURL(con.getURL().toString());
                    /*
                     * 2020-03-04: E.g. directurls will redirect to subdomain e.g. ia800503.us.archive.org --> Sometimes the only way to
                     * differ between a file or expected html.
                     */
                    final String host = Browser.getHost(con.getURL(), true);
                    if (!isArchiveContent && (this.looksLikeDownloadableContent(con) || con.getLongContentLength() > br.getLoadLimit() || !host.equals("archive.org"))) {
                        // final DownloadLink fina = this.createDownloadlink(parameter.replace("archive.org", host_decrypted));
                        final DownloadLink dl = new DownloadLink(hostPlugin, null, hostPlugin.getHost(), contenturl, true);
                        if (this.looksLikeDownloadableContent(con)) {
                            if (con.getCompleteContentLength() > 0) {
                                dl.setVerifiedFileSize(con.getCompleteContentLength());
                            }
                            dl.setFinalFileName(getFileNameFromHeader(con));
                            dl.setAvailable(true);
                        } else {
                            /* 2021-02-05: Either offline or account-only. Assume offline for now. */
                            dl.setAvailable(false);
                        }
                        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
                        ret.add(dl);
                        return ret;
                    } else {
                        final int previousLoadLimit = br.getLoadLimit();
                        try {
                            br.setLoadLimit(Integer.MAX_VALUE);
                            br.followConnection();
                        } finally {
                            br.setLoadLimit(previousLoadLimit);
                        }
                    }
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                }
            }
            if (br.getHttpConnection().getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final boolean isBookPreviewAvailable = getBookReaderURL(br) != null;
            if (isBookPreviewAvailable) {
                /* Book is officially downloadable and loose book pages are also available -> Process as wished by the user-preferences. */
                final boolean isOfficiallyDownloadable = br.containsHTML("class=\"download-button\"") && !br.containsHTML("class=\"download-lending-message\"");
                final BookCrawlMode mode = PluginJsonConfig.get(ArchiveOrgConfig.class).getBookCrawlMode();
                if (isOfficiallyDownloadable) {
                    if (mode == BookCrawlMode.PREFER_ORIGINAL) {
                        try {
                            logger.info("Trying to crawl original files");
                            return crawlDetails(br.cloneBrowser(), param);
                        } catch (final Exception e) {
                            /* Rare case e.g.: https://archive.org/details/isbn_9789814585354 */
                            logger.info("Details crawler failed -> Fallback to loose book pages");
                            return crawlBook(br, param, account);
                        }
                    } else if (mode == BookCrawlMode.ORIGINAL_AND_LOOSE_PAGES) {
                        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
                        try {
                            ret.addAll(crawlDetails(br, param));
                        } catch (final Exception ignore) {
                            logger.log(ignore);
                            logger.info("Details crawler failed -> Fallback to returning loose book pages ONLY");
                        }
                        ret.addAll(crawlBook(br, param, account));
                        return ret;
                    } else {
                        /* Only loose book pages can be crawled. */
                        return crawlBook(br, param, account);
                    }
                } else {
                    return crawlBook(br, param, account);
                }
            } else if (isArchiveContent) {
                return crawlArchiveContent();
            } else if (StringUtils.containsIgnoreCase(contenturl, "/details/")) {
                return crawlDetails(br, param);
            } else {
                return crawlFiles(contenturl);
            }
        }
    }

    private void ensureInitHosterplugin() throws PluginException {
        if (this.hostPlugin == null) {
            this.hostPlugin = (ArchiveOrg) getNewPluginForHostInstance("archive.org");
        }
    }

    private ArrayList<DownloadLink> crawlPatternSlashDownload(final String url) throws Exception {
        if (!new Regex(url, PATTERN_DOWNLOAD).patternFind()) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        String path = new URL(url).getPath();
        path = path.replaceFirst("^/download/", "/");
        final boolean allowCheckForDirecturl = true;
        if (path.contains("/") && allowCheckForDirecturl) {
            /**
             * 2023-05-30: Especially important when user adds a like to a file inside a .zip file as that will not be contained in the XML
             * which we are crawling below. </br>
             * Reference: https://board.jdownloader.org/showthread.php?t=89368
             */
            logger.info("Path contains subpath -> Checking for single directurl");
            URLConnectionAdapter con = null;
            try {
                con = br.openHeadConnection(url);
                ensureInitHosterplugin();
                if (this.looksLikeDownloadableContent(con)) {
                    logger.info("URL is directurl");
                    final DownloadLink dl = new DownloadLink(hostPlugin, null, hostPlugin.getHost(), url, true);
                    if (con.getCompleteContentLength() > 0) {
                        dl.setVerifiedFileSize(con.getCompleteContentLength());
                    }
                    final String filenameFromHeader = getFileNameFromHeader(con);
                    if (filenameFromHeader != null) {
                        dl.setFinalFileName(Encoding.htmlDecode(filenameFromHeader));
                    }
                    dl.setAvailable(true);
                    final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
                    ret.add(dl);
                    return ret;
                } else {
                    logger.info("URL is not a directurl");
                    switch (con.getResponseCode()) {
                    case 404:
                        throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                    case 400:
                        throw new DecrypterRetryException(RetryReason.HOST);
                    default:
                        break;
                    }
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return crawlXML(url, br, path);
    }

    /** Crawls all files from "/download/..." URLs. */
    @Deprecated
    private ArrayList<DownloadLink> crawlFiles(final String contenturl) throws Exception {
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("(?i)>\\s*The item is not available")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (!br.containsHTML("\"/download/")) {
            logger.info("Maybe invalid link or nothing there to download: " + contenturl);
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String subfolderPathURLEncoded = new Regex(contenturl, "(?i)https?://[^/]+/(?:download|details)/(.*?)/?$").getMatch(0);
        final String titleSlug = new Regex(contenturl, "(?i)https?://[^/]+/(?:download|details)/([^/]+)").getMatch(0);
        if (titleSlug == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        return crawlXML(br.getURL(), br, subfolderPathURLEncoded);
    }

    private ArrayList<DownloadLink> crawlDetails(final Browser br, final CryptedLink param) throws Exception {
        final String urlWithoutParams = br._getURL().getPath();
        final String titleSlug = new Regex(urlWithoutParams, "/details/([^/]+)").getMatch(0);
        if (titleSlug == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String downloadurl = br.getURL("/download/" + titleSlug).toString();
        if (br.containsHTML("id=\"gamepadtext\"")) {
            /* 2020-09-29: Rare case: Download browser emulated games */
            return this.crawlXML(br.getURL(), br, titleSlug);
        }
        final ArrayList<DownloadLink> playlistStreams = new ArrayList<DownloadLink>();
        final ArchiveOrgConfig cfg = PluginJsonConfig.get(ArchiveOrgConfig.class);
        final String videoJson = br.getRegex("class=\"js-tv3-init\"[^>]*value='(\\{.*?\\})").getMatch(0);
        if (videoJson != null) {
            /* 2022-10-31: Example: https://archive.org/details/MSNBCW_20211108_030000_Four_Seasons_Total_Documentary */
            final Map<String, Object> entries = restoreFromString(videoJson, TypeRef.MAP);
            final String slug = entries.get("TV3.identifier").toString();
            final List<String> urls = (List<String>) entries.get("TV3.clipstream_clips");
            int position = 1;
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(slug);
            for (final String url : urls) {
                final DownloadLink video = this.createDownloadlink(url);
                video.setProperty(ArchiveOrg.PROPERTY_PLAYLIST_POSITION, position);
                video.setProperty(ArchiveOrg.PROPERTY_PLAYLIST_SIZE, urls.size());
                ArchiveOrg.setFinalFilename(video, slug + ".mp4");
                video.setAvailable(true);
                video._setFilePackage(fp);
                playlistStreams.add(video);
                position++;
            }
        }
        final String audioPlaylistJson = br.getRegex("class=\"js-play8-playlist\"[^>]*value='(\\[.*?\\])'/>").getMatch(0);
        final String metadataJson = br.getRegex("class=\"js-ia-metadata\"[^>]*value='(\\{.*?\\})'/>").getMatch(0);
        if (audioPlaylistJson != null) {
            final ArrayList<DownloadLink> audioPlaylistItemsSimple = new ArrayList<DownloadLink>();
            final Map<String, Integer> filenameToTrackPositionMapping = new HashMap<String, Integer>();
            final List<Map<String, Object>> ressourcelist = (List<Map<String, Object>>) restoreFromString(audioPlaylistJson, TypeRef.OBJECT);
            if (ressourcelist.isEmpty()) {
                /* This should never happen. */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            int position = 1;
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(titleSlug);
            for (final Map<String, Object> audiomap : ressourcelist) {
                final List<Map<String, Object>> sources = (List<Map<String, Object>>) audiomap.get("sources");
                if (sources.size() > 1) {
                    logger.info("Found item with multiple sources: " + audiomap);
                }
                final Map<String, Object> source0 = sources.get(0);
                final DownloadLink audio = this.createDownloadlink(br.getURL(source0.get("file").toString()).toString());
                audio.setProperty(ArchiveOrg.PROPERTY_PLAYLIST_POSITION, position);
                audio.setProperty(ArchiveOrg.PROPERTY_PLAYLIST_SIZE, ressourcelist.size());
                audio.setProperty(ArchiveOrg.PROPERTY_ARTIST, audiomap.get("artist")); // optional field
                audio.setProperty(ArchiveOrg.PROPERTY_TITLE, audiomap.get("title"));
                String filename = (String) audiomap.get("orig");
                if (StringUtils.isEmpty(filename)) {
                    filename = audiomap.get("title").toString();
                }
                ArchiveOrg.setFinalFilename(audio, filename);
                audio.setAvailable(true);
                audio._setFilePackage(fp);
                audioPlaylistItemsSimple.add(audio);
                filenameToTrackPositionMapping.put(filename, position);
                position++;
            }
            final ArrayList<DownloadLink> audioPlaylistItemsDetailed = new ArrayList<DownloadLink>();
            boolean returnDetailedItems = false;
            if (metadataJson != null) {
                /**
                 * Try to find more metadata to the results we already have and combine them with the track-position-data we know. </br>
                 * In the end we should get the best of both worlds: All tracks with track numbers, metadata and file hashes for CRC
                 * checking.
                 */
                logger.info("Looking for more detailed audio metadata");
                audioPlaylistItemsDetailed.addAll(this.crawlMetadataJson(metadataJson, filenameToTrackPositionMapping));
                if (audioPlaylistItemsDetailed.size() == ressourcelist.size()) {
                    returnDetailedItems = true;
                } else {
                    /*
                     * Most likely we found less items than in our playlist. Prefer returning the full playlist with less information vs.
                     * incomplete number of items with more metadata.
                     */
                    logger.warning("Failed to find all audio items in detailed handling - can't make use of detailed items!");
                }
            }
            if (returnDetailedItems) {
                logger.info("Found valid detailed audio information");
                playlistStreams.addAll(audioPlaylistItemsDetailed);
            } else {
                logger.info("Failed to obtain detailed audio information");
                playlistStreams.addAll(audioPlaylistItemsSimple);
            }
        }
        final String downloadlinkToAllFilesDownload = br.getRegex("(?i)href=\"(/download/[^\"]*?)\">SHOW ALL").getMatch(0);
        final PlaylistCrawlMode playlistCrawlMode = cfg.getPlaylistCrawlMode();
        if (playlistStreams.size() > 0 && playlistCrawlMode == PlaylistCrawlMode.PLAYLIST_ONLY) {
            /* Check whether user only wants to have playlist items only or more/all. */
            logger.info("Returning streaming items ONLY");
            return playlistStreams;
        }
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        if (downloadlinkToAllFilesDownload == null || playlistCrawlMode == PlaylistCrawlMode.PLAYLIST_AND_FILES) {
            ret.addAll(playlistStreams);
        }
        if (downloadlinkToAllFilesDownload != null) {
            /* This link will go back into this crawler to find all individual downloadlinks. */
            ret.add(createDownloadlink(downloadurl));
            return ret;
        } else if (br.containsHTML("(?i)>\\s*You must log in to view this content") || br.containsHTML("(?i)>\\s*Item not available|>\\s*The item is not available due to issues with the item's content")) {
            /* 2021-02-24: <p class="theatre-title">You must log in to view this content</p> */
            if (br.containsHTML("/download/" + titleSlug)) {
                /* Account is still required but we can go ahead and crawl all individual file URLs via XML. */
                ret.add(createDownloadlink(downloadurl));
                return ret;
            } else {
                throw new AccountRequiredException();
            }
        }
        if (ret.isEmpty()) {
            final boolean useScrapingAPIForCollections = !titleSlug.startsWith("@");
            if (useScrapingAPIForCollections) {
                logger.info("Crawling collections...");
                final ArrayList<DownloadLink> collectionResults = searchViaScrapeAPI(br, "collection:" + titleSlug, -1);
                if (collectionResults.isEmpty()) {
                    throw new DecrypterRetryException(RetryReason.EMPTY_FOLDER, "EMPTY_COLLECTION_" + titleSlug);
                }
                ret.addAll(collectionResults);
            } else {
                // 2023-06-05: user cannot be scraped via api yet
                logger.info("Crawling user...");
                /* Website */
                final UrlQuery query = UrlQuery.parse(br.getURL());
                final String startPageStr = query.get("page");
                final int startPage;
                if (startPageStr != null && startPageStr.matches("\\d+")) {
                    logger.info("Starting from user defined page: " + startPageStr);
                    startPage = Integer.parseInt(startPageStr);
                } else {
                    logger.info("Starting from page 1");
                    startPage = 1;
                }
                int page = startPage;
                logger.info("Starting from page " + startPage);
                final HashSet<String> dupes = new HashSet<String>();
                boolean stopBecauseOfPaginationLimitation = false;
                do {
                    /* Check for serverside pagination limitation. Typically around page 136 */
                    if (br.containsHTML("(?i)<div[^>]*class\\s*=\\s*\"no-results\"[^>]*>\\s*No results matched your criteria")) {
                        logger.info("Stopping because: Reached serverside pagination limitation: Error 'No results matched your criteria': " + br.getURL());
                        stopBecauseOfPaginationLimitation = true;
                        break;
                    }
                    final String[] details = br.getRegex("<div class=\"item-ia\".*? <a href=\"(/details/[^\"]*?)\" title").getColumn(0);
                    if (details == null || details.length == 0) {
                        logger.info("Stopping because: Failed to find any results on current page: " + br.getURL());
                        break;
                    }
                    int numberofNewItemsOnThisPage = 0;
                    for (final String detail : details) {
                        if (!dupes.add(detail)) {
                            continue;
                        }
                        final DownloadLink link = createDownloadlink(br.getURL(detail).toString());
                        ret.add(link);
                        /* The following statement makes debugging easier. */
                        if (!DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
                            distribute(link);
                        }
                        numberofNewItemsOnThisPage++;
                    }
                    logger.info("Crawled page " + page + " | New items on this page: " + numberofNewItemsOnThisPage + " | Results so far: " + ret.size());
                    if (this.isAbort()) {
                        logger.info("Stopping because: Aborted by user");
                        break;
                    } else if (numberofNewItemsOnThisPage == 0) {
                        /* Additional fail-safe */
                        logger.info("Stopping because: Failed to find any new items on current page: " + page);
                        break;
                    } else if (!br.containsHTML("page=" + (page + 1))) {
                        /* Next page not found -> We should've reached the end */
                        logger.info("Stopping because: Reached last page: " + page);
                        break;
                    } else {
                        page++;
                        query.addAndReplace("page", Integer.toString(page));
                        final String nextPageURL = urlWithoutParams + "?" + query.toString();
                        br.getPage(nextPageURL);
                    }
                } while (!this.isAbort());
                if (ret.isEmpty()) {
                    if (stopBecauseOfPaginationLimitation && startPage > 1) {
                        /*
                         * Problem most likely caused by user adding link with page number that cannot be displayed [extremely rare case].
                         */
                        throw new DecrypterRetryException(RetryReason.EMPTY_FOLDER, "EMPTY_COLLECTION_DUE_TO_PAGINATION_LIMITATION_START_PAGE_DEFINED_BY_USER_" + startPage + "_" + titleSlug);
                    } else {
                        /* Also a very unlikely case. */
                        throw new DecrypterRetryException(RetryReason.EMPTY_FOLDER, "EMPTY_COLLECTION_" + titleSlug);
                    }
                }
            }
        }
        return ret;
    }

    private ArrayList<DownloadLink> crawlSearchQueryURL(final Browser br, final CryptedLink param) throws Exception {
        final ArchiveOrgConfig cfg = PluginJsonConfig.get(ArchiveOrgConfig.class);
        final int maxResults = cfg.getSearchTermCrawlerMaxResultsLimit();
        if (maxResults == 0) {
            logger.info("User disabled search term crawler -> Returning empty array");
            return new ArrayList<DownloadLink>();
        }
        final UrlQuery query = UrlQuery.parse(param.getCryptedUrl());
        String searchQuery = query.get("query");
        if (searchQuery != null) {
            searchQuery = Encoding.htmlDecode(searchQuery).trim();
        }
        if (StringUtils.isEmpty(searchQuery)) {
            /* User supplied invalid URL. */
            throw new DecrypterRetryException(RetryReason.FILE_NOT_FOUND, "INVALID_SEARCH_QUERY");
        }
        final ArrayList<DownloadLink> searchResults = searchViaScrapeAPI(br, searchQuery, maxResults);
        if (searchResults.isEmpty()) {
            throw new DecrypterRetryException(RetryReason.EMPTY_FOLDER, "NO_SEARCH_RESULTS_FOR_QUERY_" + searchQuery);
        }
        return searchResults;
    }

    /** API: Docs: https://archive.org/help/aboutsearch.htm */
    private ArrayList<DownloadLink> searchViaScrapeAPI(final Browser br, final String searchTerm, final int maxResultsLimit) throws Exception {
        if (StringUtils.isEmpty(searchTerm)) {
            throw new IllegalArgumentException();
        } else if (maxResultsLimit == 0) {
            throw new IllegalArgumentException();
        }
        logger.info("Searching for: " + searchTerm + " | maxResultsLimit = " + maxResultsLimit);
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final int maxNumberofItemsPerPage = 10000;
        final int minNumberofItemsPerPage = 100;
        final UrlQuery query = new UrlQuery();
        query.add("fields", "identifier");
        query.add("q", Encoding.urlEncode(searchTerm));
        final int maxNumberofItemsPerPageForThisRun;
        if (maxResultsLimit == -1) {
            maxNumberofItemsPerPageForThisRun = maxNumberofItemsPerPage;
        } else if (maxResultsLimit <= minNumberofItemsPerPage) {
            maxNumberofItemsPerPageForThisRun = minNumberofItemsPerPage;
        } else if (maxResultsLimit < maxNumberofItemsPerPage) {
            maxNumberofItemsPerPageForThisRun = maxResultsLimit;
        } else {
            maxNumberofItemsPerPageForThisRun = maxNumberofItemsPerPage;
        }
        query.add("count", Integer.toString(maxNumberofItemsPerPageForThisRun));
        String cursor = null;
        int page = 1;
        do {
            br.getPage("https://" + this.getHost() + "/services/search/v1/scrape?" + query.toString());
            final Map<String, Object> entries = restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
            final int maxItems = ((Number) entries.get("total")).intValue();
            if (maxItems == 0) {
                logger.info("Search returned zero results");
                return ret;
            }
            final List<Map<String, Object>> items = (List<Map<String, Object>>) entries.get("items");
            boolean stopDueToCrawlLimitReached = false;
            for (final Map<String, Object> item : items) {
                final DownloadLink link = this.createDownloadlink("https://archive.org/details/" + item.get("identifier").toString());
                ret.add(link);
                /* The following statement makes debugging easier. */
                if (!DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
                    distribute(link);
                }
                if (ret.size() == maxResultsLimit) {
                    /* Do not step out of main loop yet so we can get the log output down below one last time. */
                    stopDueToCrawlLimitReached = true;
                    break;
                }
            }
            final String lastCursor = cursor;
            cursor = (String) entries.get("cursor");
            logger.info("Crawled page " + page + " | Found items so far: " + ret.size() + "/" + maxItems + " | maxResultsLimit: " + maxResultsLimit + " | Cursor: " + lastCursor + " | Next cursor: " + cursor);
            if (stopDueToCrawlLimitReached) {
                logger.info("Stopping because: Reached max allowed results: " + maxResultsLimit);
                break;
            } else if (this.isAbort()) {
                logger.info("Stopping because: Aborted by user");
                break;
            } else if (StringUtils.isEmpty(cursor)) {
                logger.info("Stopping because: Reached last page: " + lastCursor);
                break;
            } else if (ret.size() >= maxItems) {
                /* Additional fail-safe */
                logger.info("Stopping because: Found all items: " + maxItems);
                break;
            } else if (items.size() < maxNumberofItemsPerPageForThisRun) {
                /* Additional fail-safe */
                logger.info("Stopping because: Current page contains less items than max allowed per page for this run: " + maxNumberofItemsPerPageForThisRun);
                break;
            } else {
                query.add("cursor", Encoding.urlEncode(cursor));
                page++;
            }
        } while (true);
        return ret;
    }

    /** Crawls json which can sometimes be found in html of such URLs: "/details/<identifier>" */
    private ArrayList<DownloadLink> crawlMetadataJson(final String json, final Map<String, Integer> filenameToTrackPositionMapping) throws Exception {
        final Map<String, Object> metamap_root = restoreFromString(json, TypeRef.MAP);
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final ArrayList<Map<String, Object>> skippedItems = new ArrayList<Map<String, Object>>();
        final Map<String, Object> metadata = (Map<String, Object>) metamap_root.get("metadata");
        String title = (String) metadata.get("title");
        if (title == null) {
            /* Fallback */
            title = metadata.get("identifier").toString();
        }
        /* There is different servers to choose from e.g. see also fields "d1", "d2" and "workable_servers". */
        final String server = metamap_root.get("server").toString();
        final String dir = metamap_root.get("dir").toString();
        final List<Map<String, Object>> filemaps = (List<Map<String, Object>>) metamap_root.get("files");
        if (filemaps == null || filemaps.isEmpty()) {
            /* This should never happen. */
            throw new DecrypterRetryException(RetryReason.EMPTY_FOLDER, title);
        }
        final HashSet<Integer> usedTrackPositions = new HashSet<Integer>();
        String filenameHelperProperty = "crawlerFilenameTmp";
        int playlistSize = filenameToTrackPositionMapping != null ? filenameToTrackPositionMapping.size() : null;
        for (final Map<String, Object> filemap : filemaps) {
            final String source = filemap.get("source").toString(); // "original" or "derivative"
            final String audioTrackPositionStr = (String) filemap.get("track");
            String filename = (String) filemap.get("orig");
            final Object sizeO = filemap.get("size");
            if (StringUtils.isEmpty(filename)) {
                filename = (String) filemap.get("original");
                if (StringUtils.isEmpty(filename)) {
                    filename = (String) filemap.get("name");
                }
            }
            if (StringUtils.isEmpty(filename)) {
                /* This should never happen! */
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            int audioTrackPosition = -1;
            if (filenameToTrackPositionMapping != null && filenameToTrackPositionMapping.containsKey(filename)) {
                /* Get track position from mapping. Trust this mapping more than "track" field in metadata. */
                audioTrackPosition = filenameToTrackPositionMapping.get(filename);
            } else if (audioTrackPositionStr != null) {
                audioTrackPosition = Integer.parseInt(audioTrackPositionStr);
            }
            if (filenameToTrackPositionMapping != null && (audioTrackPosition == -1 || !source.equalsIgnoreCase("original"))) {
                /* Skip non audio and non-original files if a filenameToTrackPositionMapping is available. */
                skippedItems.add(filemap);
                continue;
            } else if (filenameToTrackPositionMapping != null && !usedTrackPositions.add(audioTrackPosition)) {
                /*
                 * Avoid assigning a track position twice. This can theoretically happen if a playlist contains two different songs with
                 * identical filenames.
                 */
                skippedItems.add(filemap);
                // continue;
            }
            final String directurl = "https://" + server + dir + "/" + URLEncode.encodeURIComponent(filename);
            final DownloadLink file = new DownloadLink(hostPlugin, null, "archive.org", directurl, true);
            file.setProperty(filenameHelperProperty, filename);
            if (audioTrackPosition != -1) {
                if (audioTrackPosition > playlistSize) {
                    /* Determine size of playlist as it must not be pre-given. */
                    playlistSize = audioTrackPosition;
                }
                file.setProperty(ArchiveOrg.PROPERTY_PLAYLIST_POSITION, audioTrackPosition);
            }
            file.setProperty(ArchiveOrg.PROPERTY_ARTIST, filemap.get("artist")); // Optional field
            file.setProperty(ArchiveOrg.PROPERTY_TITLE, filemap.get("title")); // Optional field
            if (sizeO != null) {
                if (sizeO instanceof Number) {
                    file.setVerifiedFileSize(((Number) sizeO).longValue());
                } else {
                    file.setVerifiedFileSize(Long.parseLong(sizeO.toString()));
                }
            }
            final String crc32 = (String) filemap.get("crc32");
            if (crc32 != null) {
                file.setHashInfo(HashInfo.parse(crc32));
            }
            final String md5 = (String) filemap.get("md5");
            if (md5 != null) {
                file.setMD5Hash(md5);
            }
            final String sha1 = (String) filemap.get("sha1");
            if (sha1 != null) {
                file.setSha1Hash(sha1);
            }
            file.setProperty(ArchiveOrg.PROPERTY_TIMESTAMP_FROM_API_LAST_MODIFIED, filemap.get("mtime"));
            ret.add(file);
        }
        /* Add some properties. */
        final String description = (String) metadata.get("description");
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(title);
        if (!StringUtils.isEmpty(description)) {
            fp.setComment(description);
        }
        for (final DownloadLink link : ret) {
            link._setFilePackage(fp);
            link.setAvailable(true);
            if (playlistSize != -1) {
                link.setProperty(ArchiveOrg.PROPERTY_PLAYLIST_SIZE, playlistSize);
            }
            final String filenameTmp = link.getStringProperty(filenameHelperProperty);
            ArchiveOrg.setFinalFilename(link, filenameTmp);
            link.removeProperty(filenameHelperProperty);
        }
        if (ret.isEmpty() && skippedItems.size() > 0) {
            logger.warning("Returning no results because all possible results were skipped | Number of skipped items: " + skippedItems.size());
        }
        return ret;
    }

    private ArrayList<DownloadLink> crawlArchiveContent() throws Exception {
        /* 2020-09-07: Contents of a .zip/.rar file are also accessible and downloadable separately. */
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String archiveName = new Regex(br.getURL(), ".*/([^/]+)$").getMatch(0);
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(Encoding.htmlDecode(archiveName).trim());
        final String[] htmls = br.getRegex("<tr><td>(.*?)</tr>").getColumn(0);
        for (final String html : htmls) {
            String url = new Regex(html, "(/download/[^\"\\']+)").getMatch(0);
            final String filesizeBytesStr = new Regex(html, "id=\"size\">(\\d+)").getMatch(0);
            if (StringUtils.isEmpty(url)) {
                /* Skip invalid items */
                continue;
            }
            url = "https://archive.org" + url;
            final DownloadLink dl = this.createDownloadlink(url);
            if (filesizeBytesStr != null) {
                dl.setDownloadSize(Long.parseLong(filesizeBytesStr));
            }
            dl.setAvailable(true);
            dl._setFilePackage(fp);
            ret.add(dl);
        }
        return ret;
    }

    /** Crawls desired book. Given browser instance needs to access URL to book in beforehand! */
    public ArrayList<DownloadLink> crawlBook(final Browser br, final CryptedLink param, final Account account) throws Exception {
        /* Crawl all pages of a book */
        final String bookAjaxURL = getBookReaderURL(br);
        if (bookAjaxURL == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String bookID = UrlQuery.parse(bookAjaxURL).get("id");
        if (StringUtils.isEmpty(bookID)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        long loanedSecondsLeft = 0;
        br.getPage(bookAjaxURL);
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Map<String, Object> root = restoreFromString(br.toString(), TypeRef.MAP);
        final Map<String, Object> data = (Map<String, Object>) root.get("data");
        final Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");
        final Object descriptionObject = metadata.get("description");
        final String description;
        if (descriptionObject instanceof String) {
            description = (String) descriptionObject;
        } else if (descriptionObject instanceof List) {
            description = StringUtils.join((List) descriptionObject, ";");
        } else {
            description = null;
        }
        final Map<String, Object> lendingInfo = (Map<String, Object>) data.get("lendingInfo");
        // final Map<String, Object> lendingStatus = (Map<String, Object>) lendingInfo.get("lendingStatus");
        final long daysLeftOnLoan = ((Number) lendingInfo.get("daysLeftOnLoan")).longValue();
        final long secondsLeftOnLoan = ((Number) lendingInfo.get("secondsLeftOnLoan")).longValue();
        if (daysLeftOnLoan > 0) {
            loanedSecondsLeft += daysLeftOnLoan * 24 * 60 * 60;
        }
        if (secondsLeftOnLoan > 0) {
            loanedSecondsLeft += secondsLeftOnLoan;
        }
        final Map<String, Object> brOptions = (Map<String, Object>) data.get("brOptions");
        final boolean isLendingRequired = (Boolean) lendingInfo.get("isLendingRequired") == Boolean.TRUE;
        String contentURLFormat = generateBookContentURL(bookID);
        final String bookId = brOptions.get("bookId").toString();
        String title = ((String) brOptions.get("bookTitle")).trim();
        final String subPrefix = (String) brOptions.get("subPrefix");
        final boolean isMultiVolumeBook;
        if (subPrefix != null && !subPrefix.equals(bookId)) {
            /**
             * Books can have multiple volumes. In this case lending the main book will basically lend all volumes alltogether. </br>
             * Problem: Title is the same for all items --> Append this subPrefix to the title to fix that.
             */
            title += " - " + subPrefix;
            contentURLFormat += "/" + subPrefix;
            isMultiVolumeBook = true;
        } else {
            isMultiVolumeBook = false;
        }
        final String pageFormat;
        if (bookAjaxURL.matches(".*/page/n\\d+.*")) {
            pageFormat = "/page/n%d";
        } else {
            pageFormat = "/page/%d";
        }
        /*
         * Defines how book pages will be arranged on the archive.org website. User can open single pages faster in browser if we get this
         * right.
         */
        final String bookDisplayMode = new Regex(bookAjaxURL, "/mode/([^/]+)").getMatch(0);
        final List<Object> imagesO = (List<Object>) brOptions.get("data");
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        int imagecount = -1;
        try {
            imagecount = Integer.parseInt(metadata.get("imagecount").toString());
        } catch (final Exception ignore) {
        }
        final int padLength = StringUtils.getPadLength(imagecount);
        int internalPageIndex = 0;
        for (final Object imageO : imagesO) {
            /*
             * Most of all objects will contain an array with 2 items --> Books always have two viewable pages. Exception = First page -->
             * Cover
             */
            final List<Object> pagesO = (List<Object>) imageO;
            for (final Object pageO : pagesO) {
                final Map<String, Object> bookpage = (Map<String, Object>) pageO;
                /* When this starts at 0 this means the book has a cover else this will start at 1 -> No cover. */
                final int archiveOrgPageIndex = ((Number) bookpage.get("leafNum")).intValue();
                final String url = bookpage.get("uri").toString();
                final DownloadLink dl = new DownloadLink(hostPlugin, null, "archive.org", url, true);
                String contentURL = contentURLFormat;
                if (archiveOrgPageIndex > 1) {
                    contentURL += String.format(pageFormat, archiveOrgPageIndex + 1);
                }
                if (bookDisplayMode != null) {
                    contentURL += "/mode/" + bookDisplayMode;
                }
                dl.setContentUrl(contentURL);
                dl.setFinalFileName(StringUtils.formatByPadLength(padLength, archiveOrgPageIndex) + "_" + title + ".jpg");
                dl.setProperty(ArchiveOrg.PROPERTY_BOOK_ID, bookID);
                dl.setProperty(ArchiveOrg.PROPERTY_BOOK_PAGE, archiveOrgPageIndex);
                dl.setProperty(ArchiveOrg.PROPERTY_BOOK_PAGE_INTERNAL_INDEX, internalPageIndex);
                if (isMultiVolumeBook) {
                    dl.setProperty(ArchiveOrg.PROPERTY_BOOK_SUB_PREFIX, subPrefix);
                }
                if (Boolean.TRUE.equals(isLendingRequired)) {
                    dl.setProperty(ArchiveOrg.PROPERTY_IS_LENDING_REQUIRED, true);
                }
                if (loanedSecondsLeft > 0) {
                    dl.setProperty(ArchiveOrg.PROPERTY_IS_BORROWED_UNTIL_TIMESTAMP, System.currentTimeMillis() + loanedSecondsLeft * 1000);
                }
                /**
                 * Mark pages that are not viewable in browser as offline. </br>
                 * If we have borrowed this book, this field will not exist at all.
                 */
                final Object viewable = bookpage.get("viewable");
                if (Boolean.FALSE.equals(viewable)) {
                    /* Only downloadable with account */
                    if (PluginJsonConfig.get(ArchiveOrgConfig.class).isMarkNonViewableBookPagesAsOfflineIfNoAccountIsAvailable() && account == null) {
                        dl.setAvailable(false);
                    } else {
                        /* Always mark all pages as online. Non-viewable pages can only be downloaded when an account is present. */
                        dl.setAvailable(true);
                    }
                } else {
                    dl.setAvailable(true);
                    if (account == null || loanedSecondsLeft == 0) {
                        dl.setProperty(ArchiveOrg.PROPERTY_IS_FREE_DOWNLOADABLE_BOOK_PREVIEW_PAGE, true);
                    }
                }
                ret.add(dl);
                internalPageIndex++;
            }
        }
        if (account != null) {
            account.saveCookies(br.getCookies(br.getHost()), "");
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(title);
        if (!StringUtils.isEmpty(description)) {
            fp.setComment(description);
        }
        fp.addLinks(ret);
        return ret;
    }

    private String getBookReaderURL(final Browser br) {
        return br.getRegex("(?i)(?:\\'|\")([^\\'\"]+BookReaderJSIA\\.php\\?[^\\'\"]+)").getMatch(0);
    }

    private static HashMap<String, AtomicInteger> LOCKS = new HashMap<String, AtomicInteger>();

    private Object requestLock(String name) {
        synchronized (LOCKS) {
            AtomicInteger lock = LOCKS.get(name);
            if (lock == null) {
                lock = new AtomicInteger(0);
                LOCKS.put(name, lock);
            }
            lock.incrementAndGet();
            return lock;
        }
    }

    private synchronized void unLock(String name) {
        synchronized (LOCKS) {
            final AtomicInteger lock = LOCKS.get(name);
            if (lock != null) {
                if (lock.decrementAndGet() == 0) {
                    LOCKS.remove(name);
                }
            }
        }
    }

    private ArrayList<DownloadLink> crawlXML(final String contenturl, final Browser xmlbr, final String path) throws IOException, PluginException, DecrypterRetryException {
        if (xmlbr == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else if (StringUtils.isEmpty(path)) {
            /* Developer mistake */
            throw new IllegalArgumentException();
        }
        /*
         * 2020-03-04: Prefer crawling xml if possible as we then get all contents of that folder including contents of subfolders via only
         * one request!
         */
        String titleSlug = null;
        String desiredSubpathDecoded = null;
        if (path.contains("/")) {
            /* XML will always contain all files but in this case we only want to get all files in a specific subfolder. */
            final String[] urlParts = path.split("/");
            boolean buildSubpathNow = false;
            for (final String urlPart : urlParts) {
                if (!urlPart.isEmpty() && titleSlug == null) {
                    /* First non-empty segment = Root = Slug of element-title */
                    titleSlug = urlPart;
                    buildSubpathNow = true;
                } else if (buildSubpathNow) {
                    if (desiredSubpathDecoded == null) {
                        desiredSubpathDecoded = Encoding.htmlDecode(urlPart);
                    } else {
                        desiredSubpathDecoded += "/" + Encoding.htmlDecode(urlPart);
                    }
                }
            }
        } else {
            titleSlug = path;
        }
        if (StringUtils.isEmpty(titleSlug)) {
            /* Developer mistake */
            throw new IllegalArgumentException();
        }
        String xmlResponse = null;
        final String xmlurl = "https://archive.org/download/" + titleSlug + "/" + titleSlug + "_files.xml";
        final String cacheKey = xmlurl;
        final Object lock = requestLock(cacheKey);
        try {
            synchronized (lock) {
                LinkCrawler crawler = getCrawler();
                if (crawler != null) {
                    crawler = crawler.getRoot();
                }
                if (crawler != null) {
                    final Reference<String> reference = (Reference<String>) crawler.getCrawlerCache(cacheKey);
                    xmlResponse = reference != null ? reference.get() : null;
                }
                if (StringUtils.isEmpty(xmlResponse)) {
                    xmlbr.setFollowRedirects(true);
                    final int previousLoadLimit = xmlbr.getLoadLimit();
                    try {
                        xmlbr.setLoadLimit(Integer.MAX_VALUE);
                        xmlbr.getPage(xmlurl);
                        if (xmlbr.getHttpConnection().getResponseCode() == 404) {
                            /* Should be a super rare case. */
                            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                        }
                        xmlResponse = xmlbr.getRequest().getHtmlCode();
                        if (crawler != null && StringUtils.isNotEmpty(xmlResponse)) {
                            crawler.putCrawlerCache(cacheKey, new SoftReference<String>(xmlResponse));
                        }
                    } finally {
                        xmlbr.setLoadLimit(previousLoadLimit);
                    }
                }
            }
        } finally {
            unLock(cacheKey);
        }
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final ArchiveOrgConfig cfg = PluginJsonConfig.get(ArchiveOrgConfig.class);
        final boolean crawlOriginalFilesOnly = cfg.isFileCrawlerCrawlOnlyOriginalVersions();
        final boolean crawlArchiveView = cfg.isFileCrawlerCrawlArchiveView();
        final boolean crawlMetadataFiles = cfg.isFileCrawlerCrawlMetadataFiles();
        final String[] items = new Regex(xmlResponse, "<file\\s*(.*?)\\s*</file>").getColumn(0);
        if (items == null || items.length == 0) {
            throw new DecrypterRetryException(RetryReason.EMPTY_FOLDER, path);
        }
        logger.info("Crawling all files below path: " + path);
        final String basePath = "https://archive.org/download/" + titleSlug;
        final List<String> skippedItems = new ArrayList<String>();
        for (final String item : items) {
            /* <old_version>true</old_version> */
            final boolean isOldVersion = item.contains("old_version");
            final boolean isOriginal = item.contains("source=\"original\"");
            final boolean isMetadata = item.contains("<format>Metadata</format>");
            final boolean isArchiveViewSupported = item.matches("(?i)(?s).*<format>\\s*(RAR|ZIP)\\s*</format>.*");
            final boolean isAccountRequiredForDownload = item.contains("<private>true</private>");
            String pathWithFilename = new Regex(item, "name=\"([^\"]+)").getMatch(0);
            final String filesizeBytesStr = new Regex(item, "<size>(\\d+)</size>").getMatch(0);
            final String sha1hash = new Regex(item, "<sha1>([a-f0-9]+)</sha1>").getMatch(0);
            final String lastModifiedTimestamp = new Regex(item, "<mtime>(\\d+)</mtime>").getMatch(0);
            // final String md5hash = new Regex(item, "<md5>([a-f0-9]+)</md5>").getMatch(0);
            // final String crc32hash = new Regex(item, "<crc32>([a-f0-9]+)</crc32>").getMatch(0);
            if (pathWithFilename == null) {
                /* This should never happen */
                continue;
            } else if (isOldVersion) {
                /* Skip old elements. */
                skippedItems.add(pathWithFilename);
                continue;
            } else if (isMetadata && !crawlMetadataFiles) {
                /* Only include metadata in downloads if wished by the user. */
                skippedItems.add(pathWithFilename);
                continue;
            } else if (crawlOriginalFilesOnly && !isOriginal) {
                /* Skip non-original content if user only wants original content. */
                skippedItems.add(pathWithFilename);
                continue;
            }
            if (Encoding.isHtmlEntityCoded(pathWithFilename)) {
                /* Will sometimes contain "&amp;" */
                pathWithFilename = Encoding.htmlOnlyDecode(pathWithFilename);
            }
            if (desiredSubpathDecoded != null && !pathWithFilename.startsWith(desiredSubpathDecoded)) {
                /** Skip elements which do not match the sub-path we're trying to find items in or single file desired by user. */
                skippedItems.add(pathWithFilename);
                continue;
            }
            String relativePathEncoded;
            String filename = null;
            /* Search filename and properly encode relative URL to file. */
            if (pathWithFilename.contains("/")) {
                final String[] urlParts = pathWithFilename.split("/");
                relativePathEncoded = "";
                int index = 0;
                for (final String urlPart : urlParts) {
                    final boolean isLastSegment = index == urlParts.length - 1;
                    relativePathEncoded += URLEncode.encodeURIComponent(urlPart);
                    if (isLastSegment) {
                        filename = urlPart;
                    } else {
                        relativePathEncoded += "/";
                    }
                    index++;
                }
            } else {
                relativePathEncoded = URLEncode.encodeURIComponent(pathWithFilename);
                filename = pathWithFilename;
            }
            final String url = basePath + "/" + relativePathEncoded;
            if (!dups.add(url)) {
                /* Skip duplicates */
                continue;
            }
            final DownloadLink dlitem = createDownloadlink(url);
            dlitem.setVerifiedFileSize(SizeFormatter.getSize(filesizeBytesStr));
            dlitem.setAvailable(true);
            ArchiveOrg.setFinalFilename(dlitem, filename);
            String thisPath = new Regex(url, "download/(.+)/[^/]+$").getMatch(0);
            if (Encoding.isUrlCoded(thisPath)) {
                thisPath = Encoding.htmlDecode(thisPath);
            }
            dlitem.setRelativeDownloadFolderPath(thisPath);
            if (isAccountRequiredForDownload) {
                dlitem.setProperty(ArchiveOrg.PROPERTY_IS_ACCOUNT_REQUIRED, true);
            }
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(thisPath);
            dlitem._setFilePackage(fp);
            if (sha1hash != null) {
                dlitem.setSha1Hash(sha1hash);
            }
            if (lastModifiedTimestamp != null) {
                dlitem.setProperty(ArchiveOrg.PROPERTY_TIMESTAMP_FROM_API_LAST_MODIFIED, Long.parseLong(lastModifiedTimestamp));
            }
            ret.add(dlitem);
            if (crawlArchiveView && isArchiveViewSupported) {
                final DownloadLink archiveViewURL = createDownloadlink(url + "/");
                ret.add(archiveViewURL);
            }
        }
        if (desiredSubpathDecoded != null && ret.isEmpty()) {
            /* Users' desired subfolder or file was not found -> Throw exception to provide feedback to user. */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (skippedItems.size() > 0) {
            logger.info("Skipped items: " + skippedItems.size());
            logger.info(skippedItems.toString());
        }
        if (desiredSubpathDecoded != null && ret.size() == 1) {
            /**
             * Force auto package handling for single items e.g. if user only added a single file which is part of a huge folder. </br>
             * Reference: https://board.jdownloader.org/showthread.php?t=92666&page=2
             */
            final Regex typeDownload = new Regex(contenturl, PATTERN_DOWNLOAD);
            if (typeDownload.patternFind() && StringUtils.equalsIgnoreCase(ret.get(0).getPluginPatternMatcher(), contenturl)) {
                CrawledLink source = getCurrentLink().getSourceLink();
                boolean crawlerSource = false;
                while (source != null) {
                    if (canHandle(source.getURL())) {
                        crawlerSource = true;
                        break;
                    } else {
                        source = source.getSourceLink();
                    }
                }
                if (!crawlerSource) {
                    logger.info("remove filePackage from direct added download link:" + path);
                    ret.get(0)._setFilePackage(null);
                }
            }
        }
        return ret;
    }

    @Override
    protected boolean looksLikeDownloadableContent(final URLConnectionAdapter urlConnection) {
        return hostPlugin.looksLikeDownloadableContent(urlConnection);
    }

    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return ArchiveOrgConfig.class;
    }

    public static String generateBookContentURL(final String bookID) {
        return "https://archive.org/details/" + bookID;
    }
}