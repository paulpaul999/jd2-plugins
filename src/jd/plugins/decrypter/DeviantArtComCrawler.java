//    jDownloader - Downloadmanager
//    Copyright (C) 2011  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.decrypter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.parser.UrlQuery;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
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
import jd.plugins.hoster.DeviantArtCom;

@DecrypterPlugin(revision = "$Revision: 48324 $", interfaceVersion = 3, names = { "deviantart.com" }, urls = { "https?://[\\w\\.\\-]*?deviantart\\.com/(?!core-membership|search|developers|join|users)[\\w\\-]+($|/favourites(/\\d+/[\\w\\-]+)?|/gallery/\\d+/[\\w\\-]+|/gallery/(all|scraps))" })
public class DeviantArtComCrawler extends PluginForDecrypt {
    /**
     * @author raztoki, pspzockerscene
     */
    public DeviantArtComCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.IMAGE_GALLERY };
    }

    private final String PATTERN_USER                   = "(?i)^https?://[^/]+/([\\w\\-]+)$";
    private final String PATTERN_USER_FAVORITES         = "(?i)^https?://[^/]+/([\\w\\-]+)/favourites$";
    private final String PATTERN_USER_FAVORITES_GALLERY = "(?i)^https?://[^/]+/([\\w\\-]+)/favourites/(\\d+)/([\\w\\-]+).*";
    private final String PATTERN_GALLERY                = "(?i)^https?://[^/]+/([\\w\\-]+)/gallery/(\\d+)/([\\w\\-]+)$";

    @SuppressWarnings("deprecation")
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        DeviantArtCom.prepBR(this.br);
        final String addedurl = param.getCryptedUrl();
        br.setFollowRedirects(true);
        br.setCookiesExclusive(true);
        /* Login if possible. Sometimes not all items of a gallery are visible without being logged in. */
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        if (account != null) {
            final DeviantArtCom plg = (DeviantArtCom) this.getNewPluginForHostInstance(this.getHost());
            plg.login(account, false);
        }
        final Regex favouritesGallery = new Regex(addedurl, PATTERN_USER_FAVORITES_GALLERY);
        if (addedurl.matches(PATTERN_USER_FAVORITES_GALLERY)) {
            return crawlFavouritesGallery(account, favouritesGallery.getMatch(0), favouritesGallery.getMatch(1), favouritesGallery.getMatch(2));
        } else if (addedurl.matches(PATTERN_USER_FAVORITES)) {
            return this.crawlProfileFavorites(account, param);
        } else if (addedurl.matches(PATTERN_GALLERY)) {
            return this.crawlProfileOrGallery(account, param);
        } else {
            return this.crawlProfileOrGallery(account, param);
        }
    }

    /** Crawls a collection of favourites of a user. */
    private ArrayList<DownloadLink> crawlFavouritesGallery(final Account account, final String username, final String galleryID, final String gallerySlug) throws IOException, PluginException, DecrypterRetryException {
        if (username == null || galleryID == null) {
            /* Developer mistake */
            throw new IllegalArgumentException();
        }
        br.getPage("https://www." + this.getHost() + "/" + username + "/favourites/" + galleryID + "/" + gallerySlug);
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(username + " - Favourites - " + gallerySlug.replace("-", " ").trim());
        final UrlQuery query = new UrlQuery();
        query.add("type", "collection");
        query.add("folderid", "galleryID");
        query.add("username", username);
        query.add("folderid", galleryID);
        return crawlPagination(account, fp, "/_puppy/dashared/gallection/contents", query);
    }

    /** Crawls all favourites of a user. */
    private ArrayList<DownloadLink> crawlProfileFavorites(final Account account, final CryptedLink param) throws IOException, PluginException, DecrypterRetryException {
        final String username = new Regex(param.getCryptedUrl(), PATTERN_USER_FAVORITES).getMatch(0);
        if (username == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        br.setFollowRedirects(true);
        br.getPage(param.getCryptedUrl());
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(username + " - Favorites");
        final UrlQuery query = new UrlQuery();
        query.add("type", "collection");
        // query.add("folderid", "");
        query.add("username", username);
        query.add("all_folder", "true");
        return this.crawlPagination(account, fp, "/_puppy/dashared/gallection/contents", query);
    }

    private ArrayList<DownloadLink> crawlProfileOrGallery(final Account account, final CryptedLink param) throws IOException, PluginException, DecrypterRetryException {
        br.setFollowRedirects(true);
        br.getPage(param.getCryptedUrl());
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String username = new Regex(br.getURL(), "https?://[^/]+/([^/\\?]+)").getMatch(0);
        final Regex gallery = new Regex(br.getURL(), PATTERN_GALLERY);
        String galleryID = null;
        String gallerySlug = null;
        if (gallery.patternFind()) {
            galleryID = gallery.getMatch(1);
            gallerySlug = gallery.getMatch(2);
        } else {
            gallerySlug = new Regex(br.getURL(), "/gallery/(all|scraps)").getMatch(0);
        }
        if (username == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final FilePackage fp = FilePackage.getInstance();
        if (gallerySlug != null) {
            fp.setName(username + " - " + gallerySlug.replace("-", " ").trim());
        } else {
            fp.setName(username);
        }
        final UrlQuery query = new UrlQuery();
        query.add("type", "gallery");
        query.add("username", username);
        if (galleryID != null) {
            query.add("folderid", galleryID);
        } else {
            if ("scraps".equalsIgnoreCase(gallerySlug)) {
                query.add("scraps_folder", "true");
            } else {
                query.add("all_folder", "true");
            }
        }
        return crawlPagination(account, fp, "/_puppy/dashared/gallection/contents", query);
    }

    private ArrayList<DownloadLink> crawlPagination(final Account account, final FilePackage fp, final String action, final UrlQuery query) throws IOException, PluginException, DecrypterRetryException {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        String csrftoken = br.getRegex("window\\.__CSRF_TOKEN__\\s*=\\s*'([^<>\"\\']+)';").getMatch(0);
        if (csrftoken == null) {
            csrftoken = br.getRegex("csrfToken\\s*:\\s*\"([^\"]+)").getMatch(0);
        }
        if (StringUtils.isEmpty(csrftoken)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        int page = 0;
        final int maxItemsPerPage = 24;
        int offset = 0;
        final HashSet<String> dupes = new HashSet<String>();
        query.add("limit", Integer.toString(maxItemsPerPage));
        query.add("csrf_token", Encoding.urlEncode(csrftoken));
        do {
            query.addAndReplace("offset", Integer.toString(offset));
            page++;
            br.getPage(action + "?" + query.toString());
            final Map<String, Object> entries = restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
            final Number nextOffset = (Number) entries.get("nextOffset");
            final List<Map<String, Object>> results = (List<Map<String, Object>>) entries.get("results");
            if (results.isEmpty()) {
                if (ret.isEmpty()) {
                    if (Boolean.TRUE.equals(entries.get("hasMore")) && account == null) {
                        throw new AccountRequiredException();
                    } else {
                        logger.info("This item doesn't contain any items");
                        throw new DecrypterRetryException(RetryReason.EMPTY_FOLDER);
                    }
                } else {
                    logger.info("Stopping because: Current page doesn't contain any items");
                    break;
                }
            }
            int numberofNewItems = 0;
            for (final Map<String, Object> result : results) {
                Map<String, Object> deviation = (Map<String, Object>) result.get("deviation");
                if (deviation == null) {
                    /* 2023-09-23 */
                    deviation = result;
                }
                // final Map<String, Object> author = (Map<String, Object>) deviation.get("author");
                final String url = deviation.get("url").toString();
                if (dupes.add(url)) {
                    numberofNewItems++;
                    final DownloadLink link = this.createDownloadlink(url);
                    final Map<String, Object> deviationRet = DeviantArtCom.parseDeviationJSON(this, link, deviation);
                    /**
                     * This file extension may change later when file is downloaded. </br>
                     * 2022-11-11: Items of type "literature" (or simply != "image") will not get any file extension at all at this moment.
                     */
                    final String assumedFileExtension = DeviantArtCom.getAssumedFileExtension(account, link);
                    link.setName(link.getStringProperty(DeviantArtCom.PROPERTY_TITLE) + " by " + link.getStringProperty(DeviantArtCom.PROPERTY_USERNAME) + "_" + deviation.get("deviationId") + assumedFileExtension);
                    link.setAvailable(true);
                    if (fp != null) {
                        link._setFilePackage(fp);
                    }
                    ret.add(link);
                    distribute(link);
                }
            }
            logger.info("Crawled page " + page + " | Offset: " + offset + " | nextOffset: " + nextOffset + " | Found items so far: " + ret.size());
            if (this.isAbort()) {
                logger.info("Stopping because: Aborted by user");
                break;
            } else if (!(Boolean) entries.get("hasMore") || nextOffset == null) {
                logger.info("Stopping because: Reached end");
                break;
            } else if (numberofNewItems == 0) {
                /* Extra fail-safe */
                logger.info("Stopping because: Failed to find new items on page " + page);
                break;
            } else {
                /* Continue to next page */
                offset = nextOffset.intValue();
                continue;
            }
        } while (true);
        return ret;
    }

    @Override
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}