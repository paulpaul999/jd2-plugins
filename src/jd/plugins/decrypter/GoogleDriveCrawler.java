//  jDownloader - Downloadmanager
//  Copyright (C) 2013  JD-Team support@jdownloader.org
//
//  This program is free software: you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation, either version 3 of the License, or
//  (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.decrypter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.URLEncode;
import org.appwork.utils.parser.UrlQuery;
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
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.hoster.GoogleDrive;
import jd.plugins.hoster.GoogleDrive.JsonSchemeType;

@DecrypterPlugin(revision = "$Revision: 48446 $", interfaceVersion = 3, names = {}, urls = {})
@PluginDependencies(dependencies = { GoogleDrive.class })
public class GoogleDriveCrawler extends PluginForDecrypt {
    /**
     * @author raztoki, pspzockerscene, Jiaz
     */
    public GoogleDriveCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        return GoogleDrive.getPluginDomains();
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            String regex = "https?://" + buildHostsPatternPart(domains) + "/(?:";
            regex += "open\\?id=[a-zA-Z0-9\\-_]+";
            regex += "|folder/d/[a-zA-Z0-9\\-_]+";
            regex += "|(?:embedded)?folderview\\?[a-z0-9\\-_=\\&]+";
            regex += "|drive/(?:[\\w\\-]+/)*folders/[a-zA-Z0-9\\-_=\\&]+(\\?resourcekey=[A-Za-z0-9_\\-]+)?";
            regex += ")";
            ret.add(regex);
        }
        return ret.toArray(new String[0]);
    }

    // DEV NOTES
    // https://docs.google.com/folder/d/0B4lNqBSBfg_dbEdISXAyNlBpLUk/edit?pli=1 :: folder view of dir and files, can't seem to view dir
    // unless edit present.
    // https://docs.google.com/folder/d/0B4lNqBSBfg_dOEVERmQzcU9LaWc/edit?pli=1&docId=0B4lNqBSBfg_deEpXNjJrZy1MSGM :: above sub dir of docs
    // they don't provide data constistantly.
    // - with /edit?pli=1 they provide via javascript section partly escaped
    // - with /list?rm=whitebox&hl=en_GB&forcehl=1&pref=2&pli=1"; - not used and commented out, supported except for scanLinks
    // language determined by the accept-language
    private static final String  TYPE_FOLDER_NORMAL               = "(?i)https?://[^/]+/folder/d/([a-zA-Z0-9\\-_]+)";
    /* Usually with old docs.google.com domain. */
    private static final String  TYPE_FOLDER_OLD                  = "(?i)https?://[^/]+/(?:embedded)?folderview\\?(pli=1\\&id=[A-Za-z0-9_]+(\\&tid=[A-Za-z0-9]+)?|id=[A-Za-z0-9_]+\\&usp=sharing)";
    private static final String  TYPE_FOLDER_CURRENT              = "(?i)https?://[^/]+/drive/(?:[\\w\\-]+/)*folders/([^/?]+)(\\?resourcekey=[A-Za-z0-9_\\-]+)?";
    /* 2021-02-26: Theoretically, "leaf?" does the same but for now we'll only handle "open=" as TYPE_REDIRECT */
    private static final String  TYPE_REDIRECT                    = "(?i)https?://[^/]+/open\\?id=([a-zA-Z0-9\\-_]+)";
    private final String         PROPERTY_SPECIAL_SHORTCUT_FOLDER = "special_shortcut_folder";
    /* Developer: Set this to false if for some reason, private folders cannot be crawled with this plugin (anymore/temporarily). */
    private static final boolean CAN_HANDLE_PRIVATE_FOLDERS       = true;

    private String getContentURL(final CryptedLink param) {
        return param.getCryptedUrl().replaceFirst("(?i)http://", "https://");
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final String contenturl = getContentURL(param);
        try {
            if (contenturl.matches(TYPE_REDIRECT)) {
                /**
                 * Special case: This could either be a file or a folder. Other theoretically possible special cases which we will ignore
                 * here: folderID in file URL --> Un-Downloadable item and can only be handled by API </br>
                 *
                 */
                logger.info("Checking if we have a file or folder");
                br.setFollowRedirects(false);
                br.getPage(contenturl);
                String redirect = br.getRedirectLocation();
                if (redirect == null) {
                    /* No redirect -> Content must be offline */
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                int redirectCounter = -1;
                final PluginForHost hostPlg = this.getNewPluginForHostInstance(this.getHost());
                do {
                    redirectCounter++;
                    logger.info("Detected folder redirect: " + redirect);
                    if (hostPlg.canHandle(redirect)) {
                        logger.info("Redirect looks like single file URL --> Returning this as result for host plugin: " + redirect);
                        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
                        ret.add(this.createDownloadlink(redirect));
                        return ret;
                    } else if (!redirect.matches(TYPE_REDIRECT)) {
                        /* Must be folder */
                        break;
                    } else {
                        /* E.g. possible http -> https redirect */
                        logger.info("Redirect URL structure is undefined, following redirect...");
                        br.followRedirect();
                        redirect = br.getRedirectLocation();
                    }
                } while (redirect != null && redirectCounter <= 3);
                /* Too many redirects or content offline. */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            if (GoogleDrive.canUseAPI()) {
                return this.crawlAPI(param);
            } else {
                return this.crawlWebsite(param);
            }
        } catch (final GdriveException gde) {
            final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
            final String privateFoldersUnsupportedInfoText = "JDownloader cannot crawl private folders (yet).\r\nEither make this folder public or in case it is not owned by you, import it into your own google account and create your own public link to this folder and add that link to JDownloader instead.";
            if (gde.getGdriveStatus() == GdriveFolderStatus.FOLDER_OFFLINE) {
                ret.add(this.createOfflinelink(param.getCryptedUrl(), "OFFLINE_FOLDER " + gde.getOfflineTitle(), "This folder is offline."));
            } else if (gde.getGdriveStatus() == GdriveFolderStatus.FOLDER_EMPTY) {
                throw new DecrypterRetryException(RetryReason.EMPTY_FOLDER, gde.getOfflineTitle());
            } else if (gde.getGdriveStatus() == GdriveFolderStatus.FOLDER_EMPTY_OR_PRIVATE_OR_SHORTCUT) {
                final DownloadLink offlineFolder = this.createOfflinelink(param.getCryptedUrl());
                offlineFolder.setFinalFileName(gde.getOfflineTitle());
                offlineFolder.setComment("This folder is empty, a private folder or a folder-shortcut.\r\nIn case this folder is a folder shortcut: Disable API and re-add this URL.\r\nIn case this is a private folder:\r\n" + privateFoldersUnsupportedInfoText);
                ret.add(offlineFolder);
            } else if (gde.getGdriveStatus() == GdriveFolderStatus.FOLDER_PRIVATE) {
                throw new DecrypterRetryException(RetryReason.NO_ACCOUNT, gde.getOfflineTitle(), "This is a private folder!\r\n" + privateFoldersUnsupportedInfoText, null);
            } else if (gde.getGdriveStatus() == GdriveFolderStatus.FOLDER_PRIVATE_NO_ACCESS) {
                throw new DecrypterRetryException(RetryReason.NO_ACCOUNT, "PERMISSIONS_MISSING_" + gde.getOfflineTitle(), "This is a private folder!\r\nYour currently added Google Drive account doesn't have the required permission to access this folder.\r\nGet the required permissions or add an account which has the required permissions.\r\nAlso note this:\r\n" + privateFoldersUnsupportedInfoText, null);
            } else {
                /* Developer mistake!! */
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            return ret;
        }
    }

    /** Extracts folderID from given URL. */
    private String getFolderID(final String url) {
        if (url.matches(TYPE_FOLDER_NORMAL)) {
            return new Regex(url, TYPE_FOLDER_NORMAL).getMatch(0);
        } else if (url.matches(TYPE_FOLDER_CURRENT)) {
            return new Regex(url, TYPE_FOLDER_CURRENT).getMatch(0);
        } else {
            return new Regex(url, "id=([^\\&=]+)").getMatch(0);
        }
    }

    private String getFolderResourceKey(final String url) {
        try {
            return UrlQuery.parse(url).get("resourcekey");
        } catch (final Exception ignore) {
            return null;
        }
    }

    private ArrayList<DownloadLink> crawlAPI(final CryptedLink param) throws Exception {
        String folderID = getFolderID(param.getCryptedUrl());
        final String folderResourceKey = this.getFolderResourceKey(param.getCryptedUrl());
        if (folderID == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final Browser websiteBR = new Browser();
        websiteBR.setFollowRedirects(true);
        if (param.getDownloadLink() != null && param.getDownloadLink().hasProperty(PROPERTY_SPECIAL_SHORTCUT_FOLDER)) {
            /**
             * 2021-05-31: Workaround for special folder shortcuts --> FolderID will change and we cannot use the given folderID via API!
             * </br>
             * Very rare case!!
             */
            websiteBR.getPage(param.getCryptedUrl());
            final String newFolderID = this.getFolderID(websiteBR.getURL());
            if (newFolderID == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            } else if (!newFolderID.equals(folderID)) {
                logger.info("Folder redirected to new folder: Old: " + folderID + " | New: " + newFolderID);
                folderID = newFolderID;
            } else {
                /*
                 * This should never happen and chances are high that this url is either offline or API crawler will fail to process it.
                 */
                logger.warning("Expected to find new folderID but failed to do so");
            }
        }
        final PluginForHost hostPlugin = this.getNewPluginForHostInstance(this.getHost());
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        final String subfolderPath = this.getAdoptedCloudFolderStructure();
        String nameOfCurrentFolder = null;
        final UrlQuery queryFolder = new UrlQuery();
        queryFolder.appendEncoded("q", "'" + folderID + "' in parents");
        queryFolder.add("supportsAllDrives", "true");
        queryFolder.add("includeItemsFromAllDrives", "true");
        /**
         * pageSize = up to how many items get returned per request. </br>
         * 2021-02-25: Apparently the GDrive API decides randomly how many items it wants to return but it doesn't matter as we got
         * pagination. It worked fine in my tests in their API explorer but in reality the max number of items I got was 30.
         */
        queryFolder.add("pageSize", "200");
        queryFolder.appendEncoded("fields", "kind,nextPageToken,incompleteSearch,files(" + GoogleDrive.getSingleFilesFieldsAPI() + ")");
        /* API key for testing */
        queryFolder.appendEncoded("key", GoogleDrive.getAPIKey());
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final ArrayList<String> dupes = new ArrayList<String>();
        if (folderResourceKey != null) {
            setResourceKeyHeaderAPI(br, folderID, folderResourceKey);
        }
        int page = 0;
        do {
            br.getPage(GoogleDrive.API_BASE + "/files?" + queryFolder.toString());
            ((jd.plugins.hoster.GoogleDrive) hostPlugin).handleErrorsAPI(br, null, account);
            final Map<String, Object> entries = restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
            /* 2020-12-10: This will return "Offline folder" for private items too! */
            final Object filesO = entries.get("files");
            if (filesO == null || ((List<Object>) filesO).size() == 0) {
                final String offlineFolderTitle;
                if (!StringUtils.isEmpty(subfolderPath)) {
                    offlineFolderTitle = folderID + "_" + subfolderPath;
                } else {
                    offlineFolderTitle = folderID;
                }
                /*
                 * 2021-05-31: TODO: Try to find a good(!) way to handle "folder shortcuts" via API completely without website (rare case)!
                 */
                throw new GdriveException(GdriveFolderStatus.FOLDER_EMPTY_OR_PRIVATE_OR_SHORTCUT, offlineFolderTitle);
            }
            if (page == 0 && subfolderPath == null) {
                /**
                 * Workaround to find the name of the folder we're currently in. </br>
                 * TODO: Find a way to do this via API.
                 */
                /* Leave this in the loop! It doesn't really belong here but it's only a workaround and only executed once! */
                logger.info("Trying to find title of current folder");
                try {
                    /**
                     * 2020-12-09: psp: This is a workaround because API doesn't return name of the current folder or I'm just too stupid
                     * ... </br>
                     * Basically for big folder structures we really only need to do this once and after that we'll use the API only!
                     */
                    /*
                     * TODO Login via API once API login is possible -> Then we'd be able to crawl private folders which are restricted to
                     * specified accounts.
                     */
                    // if (account != null) {
                    // login(websiteBR, account);
                    // } else {
                    // /* Respect users' plugin settings (e.g. custom User-Agent) */
                    // ((jd.plugins.hoster.GoogleDrive) hostPlugin).prepBrowser(websiteBR);
                    // }
                    if (websiteBR.getRequest() == null) {
                        /* Only access URL if it hasn't been accessed before */
                        websiteBR.getPage(param.getCryptedUrl());
                    }
                    nameOfCurrentFolder = getCurrentFolderTitleWebsite(websiteBR);
                    if (!StringUtils.isEmpty(nameOfCurrentFolder)) {
                        logger.info("Successfully found title of current folder: " + nameOfCurrentFolder);
                    } else {
                        logger.warning("Failed to find title of current folder");
                    }
                } catch (final Exception e) {
                    logger.log(e);
                    logger.info("Folder title workaround failed due to Exception");
                }
            }
            final boolean incompleteSearch = ((Boolean) entries.get("incompleteSearch")).booleanValue();
            if (incompleteSearch) {
                /* This should never happen */
                logger.warning("WTF incompleteSearch == true");
            }
            final int dupesListOldSize = dupes.size();
            this.parseFolderJsonAPI(ret, dupes, entries, this.getAdoptedCloudFolderStructure(), nameOfCurrentFolder);
            final int numberofNewItemsCrawledOnThisPage = ret.size() - dupesListOldSize;
            logger.info("Crawled page " + page + " | Items current page: " + numberofNewItemsCrawledOnThisPage + " | Total: " + ret.size());
            if (page == 0 && ret.size() == 0) {
                /* Empty folder - 2nd check which usually won't be required as we're checking for this at the beginning of this function. */
                if (!StringUtils.isEmpty(subfolderPath)) {
                    throw new GdriveException(GdriveFolderStatus.FOLDER_EMPTY, folderID + "_" + subfolderPath);
                } else {
                    throw new GdriveException(GdriveFolderStatus.FOLDER_EMPTY, folderID);
                }
            }
            final String nextPageToken = (String) entries.get("nextPageToken");
            if (this.isAbort()) {
                logger.info("Stopping because: Aborted by user");
                break;
            } else if (StringUtils.isEmpty(nextPageToken)) {
                logger.info("Stopping because: nextPageToken is null");
                break;
            } else if (numberofNewItemsCrawledOnThisPage <= 0) {
                /* Fail safe */
                logger.info("Stopping because: Failed to find any new item on current page");
                break;
            } else {
                queryFolder.addAndReplace("pageToken", Encoding.urlEncode(nextPageToken));
                page++;
            }
        } while (true);
        return ret;
    }

    private ArrayList<DownloadLink> crawlWebsite(final CryptedLink param) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        String folderID = this.getFolderID(param.getCryptedUrl());
        final String folderResourceKey = this.getFolderResourceKey(param.getCryptedUrl());
        if (folderID == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        Account account = AccountController.getInstance().getValidAccount(this.getHost());
        if (account != null) {
            if (CAN_HANDLE_PRIVATE_FOLDERS) {
                logger.info("Account available -> Logging in");
                loginWebsite(this.br, account);
            } else {
                logger.info("Account available -> Can't login (disabled by developer)");
                account = null;
            }
        } else {
            logger.info("No account available");
            GoogleDrive.prepBrowser(this.br);
        }
        br.setFollowRedirects(true);
        /* 2021-05-31: Folders can redirect to other folderIDs. Most likely we got a "Shortcut" then --> Very rare case */
        br.getPage(generateFolderURL(folderID, folderResourceKey));
        final String newFolderID = this.getFolderID(br.getURL());
        if (newFolderID == null) {
            /*
             * Indication that something is wrong. Maybe offline or private folder - errorhandling down bewlow is supposed to find that out!
             */
            logger.warning("Failed to find folderID in current URL --> Possible crawler failure");
        } else if (!newFolderID.equals(folderID)) {
            logger.info("Folder redirected to new folder: Old: " + folderID + " | New: " + newFolderID);
            folderID = newFolderID;
        }
        String subfolderPath = this.getAdoptedCloudFolderStructure();
        String offlineOrEmptyFolderTitle;
        if (!StringUtils.isEmpty(subfolderPath)) {
            offlineOrEmptyFolderTitle = folderID + "_" + subfolderPath;
        } else {
            offlineOrEmptyFolderTitle = folderID;
        }
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new GdriveException(GdriveFolderStatus.FOLDER_OFFLINE, offlineOrEmptyFolderTitle);
        } else if (br.getURL().contains("accounts.google.com")) {
            if (account != null) {
                /* We are logged in but the account doesn't have permission! */
                throw new GdriveException(GdriveFolderStatus.FOLDER_PRIVATE_NO_ACCESS, offlineOrEmptyFolderTitle);
            } else {
                /* Account required! */
                throw new GdriveException(GdriveFolderStatus.FOLDER_PRIVATE, offlineOrEmptyFolderTitle);
            }
        } else if (br.getHttpConnection().getResponseCode() == 403) {
            if (account != null) {
                /* We are logged in but the account doesn't have permission! */
                throw new GdriveException(GdriveFolderStatus.FOLDER_PRIVATE_NO_ACCESS, offlineOrEmptyFolderTitle);
            } else {
                /* Account required! */
                throw new GdriveException(GdriveFolderStatus.FOLDER_PRIVATE, offlineOrEmptyFolderTitle);
            }
        } else if (br.getHttpConnection().getResponseCode() == 429) {
            throw new DecrypterRetryException(RetryReason.HOST_RATE_LIMIT, "429_TOO_MANY_REQUESTS_" + offlineOrEmptyFolderTitle, "Error 429 too many requests. Try again later.");
        }
        String currentFolderTitle = getCurrentFolderTitleWebsite(this.br);
        if (currentFolderTitle == null) {
            /* Final fallback */
            currentFolderTitle = br.getRegex("<title>([^<>\"]*?)</title>").getMatch(0);
            if (!StringUtils.isEmpty(currentFolderTitle)) {
                currentFolderTitle = Encoding.htmlDecode(currentFolderTitle).trim();
            }
        }
        /* Update this to get more meaningful titles for empty/offline folders */
        if (!StringUtils.isEmpty(subfolderPath) && !StringUtils.isEmpty(currentFolderTitle)) {
            offlineOrEmptyFolderTitle = folderID + "_" + subfolderPath + "/" + currentFolderTitle;
        } else if (currentFolderTitle != null) {
            offlineOrEmptyFolderTitle = folderID + "_" + currentFolderTitle;
        }
        // old type
        String json_src = br.getRegex("window\\['_DRIVE_ivd'\\]\\s*=\\s*'\\[(.*?)';").getMatch(0);
        // new type 20170709-raz
        if (json_src == null) {
            json_src = br.getRegex("window\\['_DRIVE_ivd'\\]\\s*=\\s*'(.*?)';").getMatch(0);
            // hex encoded
            if (json_src != null) {
                json_src = Encoding.unicodeDecode(json_src);
            }
        }
        final String key;
        if (account != null) {
            key = br.getRegex("\"([^\"]+)\",\"https://blobcomments-pa\\.clients6\\.google\\.com\"").getMatch(0);
        } else {
            final String keys[] = br.getRegex("\"([A-Za-z0-9\\-_]{6})([A-Za-z0-9\\-_]+)\"\\s*,\\s*\"\\1[A-Za-z0-9\\-_]+\"\\s*,\\s*null").getRow(0);
            logger.info("Keys:" + Arrays.asList(keys));
            if (keys == null || keys.length != 2) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            key = keys[0] + keys[1];
        }
        if (key == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        logger.info("Using key: " + key);
        final int maxItemsPerRequest = 50;
        String teamDriveID = new Regex(json_src, ",null,\\d{10,},\\d+,\"([A-Za-z0-9_\\-]{10,30})\",null,null").getMatch(0);
        if (account != null && teamDriveID == null) {
            /* 2023-02-28: Very ugly RegEx */
            teamDriveID = br.getRegex("null,null,null,null,null,null,null,null,null,null,\"([A-Za-z0-9_\\-]{10,30})\",null,null").getMatch(0);
        }
        final UrlQuery query = new UrlQuery();
        query.add("openDrive", "false");
        query.add("reason", "102");
        query.add("syncType", "0");
        query.add("errorRecovery", "false");
        query.add("q", URLEncode.encodeURIComponent("trashed = false and '" + folderID + "' in parents"));
        query.add("fields", URLEncode.encodeURIComponent("kind,nextPageToken,incompleteSearch,items(" + GoogleDrive.getSingleFilesFieldsWebsite() + ")"));
        query.add("appDataFilter", "NO_APP_DATA");
        query.add("spaces", "drive");
        query.add("maxResults", Integer.toString(maxItemsPerRequest));
        query.add("orderBy", "folder%2Ctitle_natural%20asc");
        query.add("retryCount", "0");
        query.add("key", key);
        if (folderResourceKey != null) {
            query.add("resourcekey", folderResourceKey);
        }
        query.add("supportsTeamDrives", "true");
        /* Optional params (team drives only) */
        if (teamDriveID != null) {
            query.add("includeTeamDriveItems", "true");
            query.add("teamDriveId", teamDriveID);
            query.add("corpora", "teamDrive");
        }
        String nextPageToken = null;
        int page = 0;
        final Browser brc = br.cloneBrowser();
        if (folderResourceKey != null) {
            setResourceKeyHeaderAPI(brc, folderID, folderResourceKey);
        }
        logger.info("Start folder crawl process via WebAPI");
        do {
            /* Set headers on every run as some tokens (Authorization header!) contain timestamps so they can expire. */
            GoogleDrive.prepBrowserWebAPI(brc, account);
            page++;
            /* Most common reason of failure: teamDriveID was not found thus the request is wrong! */
            if (account != null) {
                brc.getPage(GoogleDrive.WEBAPI_BASE_2 + "/v2internal/files?" + query.toString());
            } else {
                brc.getPage(GoogleDrive.WEBAPI_BASE_2 + "/v2beta/files?" + query.toString());
            }
            Map<String, Object> entries = restoreFromString(brc.toString(), TypeRef.MAP);
            final List<Object> items = (List<Object>) entries.get("items");
            if (items == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            } else if (items.isEmpty()) {
                if (ret.isEmpty()) {
                    /**
                     * Important developer note!! If this happens but the folder is not empty, most of all times, "teamDriveId" is missing
                     * or wrong!
                     */
                    logger.warning("!! Developer !! Check if teamDriveId is missing while being required!");
                    throw new GdriveException(GdriveFolderStatus.FOLDER_EMPTY, offlineOrEmptyFolderTitle);
                } else {
                    logger.info("Stopping because: Last pagination page is empty -> Should never happen but we'll allow it to happen.");
                    break;
                }
            }
            nextPageToken = (String) entries.get("nextPageToken");
            parseFolderJsonWebsite(ret, entries, subfolderPath, currentFolderTitle);
            logger.info("Crawled page " + page + " | Items current page: " + items.size() + " of max " + maxItemsPerRequest + " | Total: " + ret.size());
            if (this.isAbort()) {
                logger.info("Stopping because: Aborted by user");
                break;
            } else if (StringUtils.isEmpty(nextPageToken)) {
                /* Either we found everything or plugin failure ... */
                logger.info("Stopping because: Failed to find nextPageToken");
                break;
            } else if (items.isEmpty()) {
                logger.info("Stopping because: Failed to find any items on current page");
                break;
            } else {
                /* Continue to next page */
                query.addAndReplace("pageToken", Encoding.urlEncode(nextPageToken));
                sleep(500, param);
            }
        } while (true);
        if (ret.size() == 0) {
            logger.info("Found nothing to download: " + param.getCryptedUrl());
            return ret;
        }
        return ret;
    }

    public static void setResourceKeyHeaderAPI(final Browser br, final String resourceID, final String resourceKey) {
        /* https://developers.google.com/drive/api/v3/resource-keys */
        br.getHeaders().put("X-Goog-Drive-Resource-Keys", resourceID + "/" + resourceKey);
    }

    private static String getCurrentFolderTitleWebsite(final Browser br) {
        String title = br.getRegex("\"title\":\"([^\"]+)\",\"urlPrefix\"").getMatch(0);
        if (title == null) {
            title = br.getRegex("<title>([^<]+)</title>").getMatch(0);
        }
        if (!StringUtils.isEmpty(title)) {
            title = Encoding.htmlDecode(title).trim();
            /* Different country = different variation of that title-ending. */
            title = title.replaceFirst(" - Google Drive$", "");
            title = title.replaceFirst(" – Google Drive$", "");
            title = title.replaceFirst(" – Google Drive$", "");
            return title;
        } else {
            return null;
        }
    }

    /**
     * There are differences between website- and API json e.g. we cannot request all fields we can get via API from website and the
     * filesize field is "fileSize" via website and "size" via API.
     *
     * @throws PluginException
     */
    private void parseFolderJsonWebsite(final ArrayList<DownloadLink> ret, final Map<String, Object> data, String subfolder, final String currentFolderTitle) throws PluginException {
        if (StringUtils.isEmpty(subfolder)) {
            /* Begin subfolder structure if not given already */
            subfolder = currentFolderTitle;
        }
        FilePackage fp = null;
        String root_dir_name = null;
        if (subfolder != null) {
            fp = FilePackage.getInstance();
            fp.setName(subfolder);
            root_dir_name = new Regex(subfolder, "^/?([^/]+)").getMatch(0);
        }
        final List<Object> items = (List<Object>) data.get("items");
        for (final Object item : items) {
            final Map<String, Object> resource = (Map<String, Object>) item;
            // kind within entries, returns false positives 20170709-raz
            final String mimeType = (String) resource.get("mimeType");
            final String kind = mimeType != null && mimeType.contains(".folder") ? "folder" : (String) resource.get("kind").toString();
            final String title = resource.get("title").toString();
            final String id = resource.get("id").toString();
            final String resourceKey = (String) resource.get("resourceKey");
            final Boolean canDownload = ((Boolean) JavaScriptEngineFactory.walkJson(resource, "capabilities/canDownload"));
            /* 2021-05-31: application/vnd.google-apps.shortcut is (or can be??) a folder shortcut -> Special kind of folder */
            final boolean isShortcutFolder = StringUtils.equalsIgnoreCase(mimeType, "application/vnd.google-apps.shortcut") && Boolean.FALSE.equals(canDownload);
            final DownloadLink dl;
            if (kind.equals("drive#file") && !isShortcutFolder) {
                /* Single file */
                dl = createDownloadlink(generateFileURL(id, resourceKey));
                GoogleDrive.parseFileInfoAPIAndWebsiteWebAPI(this, JsonSchemeType.WEBSITE, dl, true, true, true, resource);
                if (subfolder != null) {
                    /*
                     * Packagizer property so user can e.g. merge all files of a folder and subfolders in a package named after the name of
                     * the root dir.
                     */
                    dl.setProperty(GoogleDrive.PROPERTY_ROOT_DIR, root_dir_name);
                    dl.setRelativeDownloadFolderPath(subfolder);
                }
                if (fp != null) {
                    dl._setFilePackage(fp);
                }
            } else {
                /* Folder */
                dl = createDownloadlink(generateFolderURL(id, resourceKey));
                if (isShortcutFolder) {
                    /* Mandatory website-workaround! Without this, such items will fail in API mode!! */
                    dl.setProperty(PROPERTY_SPECIAL_SHORTCUT_FOLDER, true);
                }
                if (subfolder != null) {
                    dl.setRelativeDownloadFolderPath(subfolder + "/" + title);
                } else {
                    dl.setRelativeDownloadFolderPath("/" + title);
                }
            }
            distribute(dl);
            ret.add(dl);
        }
    }

    public static String generateFileURL(final String fileID, final String resourceKey) {
        String url = "https://drive.google.com/file/d/" + fileID;
        if (resourceKey != null) {
            url += "?resourcekey=" + resourceKey;
        }
        return url;
    }

    public static String generateFolderURL(final String folderID, final String folderResourceKey) {
        String url = "https://drive.google.com/drive/folders/" + folderID;
        if (folderResourceKey != null) {
            url += "?resourcekey=" + folderResourceKey;
        }
        return url;
    }

    /** Very similar to the website json but with some differences thus we got separate methods for API and website. */
    private void parseFolderJsonAPI(final ArrayList<DownloadLink> ret, final ArrayList<String> dupes, final Map<String, Object> data, String subfolder, final String currentFolderTitle) throws PluginException {
        if (StringUtils.isEmpty(subfolder)) {
            /* Begin subfolder structure if not given already */
            subfolder = currentFolderTitle;
        }
        FilePackage fp = null;
        String root_dir_name = null;
        /* 2020-12-07: Workaround: Use path as packagename as long as we're unable to get the name of the folder we're currently in! */
        if (subfolder != null) {
            fp = FilePackage.getInstance();
            fp.setName(subfolder);
            root_dir_name = new Regex(subfolder, "^/?([^/]+)").getMatch(0);
        }
        final List<Object> items = (List<Object>) data.get("files");
        for (final Object item : items) {
            final Map<String, Object> resource = (Map<String, Object>) item;
            final String mimeType = (String) resource.get("mimeType");
            final String kind = mimeType != null && mimeType.contains(".folder") ? "folder" : (String) resource.get("kind");
            final String title = (String) resource.get("name");
            final String id = (String) resource.get("id");
            final String resourceKey = (String) resource.get("resourceKey");
            if (StringUtils.isEmpty(kind) || StringUtils.isEmpty(title) || StringUtils.isEmpty(id)) {
                /* This should never happen */
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            if (dupes.contains(id)) {
                continue;
            }
            dupes.add(id);
            final Boolean canDownload = ((Boolean) JavaScriptEngineFactory.walkJson(resource, "capabilities/canDownload"));
            /* 2021-05-31: application/vnd.google-apps.shortcut is (or can be??) a folder shortcut */
            final boolean isShortcutFolder = StringUtils.equalsIgnoreCase(mimeType, "application/vnd.google-apps.shortcut") && Boolean.FALSE.equals(canDownload);
            final boolean isFile = kind.equals("drive#file") && !isShortcutFolder;
            final DownloadLink dl;
            if (isFile) {
                /* Single file */
                dl = createDownloadlink(generateFileURL(id, resourceKey));
                GoogleDrive.parseFileInfoAPIAndWebsiteWebAPI(this, JsonSchemeType.API, dl, true, true, true, resource);
                if (subfolder != null) {
                    /*
                     * Packagizer property so user can e.g. merge all files of a folder and subfolders in a package named after the name of
                     * the root dir.
                     */
                    dl.setProperty(GoogleDrive.PROPERTY_ROOT_DIR, root_dir_name);
                    dl.setRelativeDownloadFolderPath(subfolder);
                }
                if (fp != null) {
                    dl._setFilePackage(fp);
                }
            } else {
                /* Folder */
                dl = createDownloadlink(generateFolderURL(id, resourceKey));
                if (subfolder != null) {
                    dl.setRelativeDownloadFolderPath(subfolder + "/" + title);
                } else {
                    dl.setRelativeDownloadFolderPath("/" + title);
                }
                if (isShortcutFolder) {
                    dl.setProperty(PROPERTY_SPECIAL_SHORTCUT_FOLDER, true);
                }
            }
            this.distribute(dl);
            ret.add(dl);
        }
    }

    public void loginWebsite(final Browser br, final Account account) throws Exception {
        final GoogleDrive plg = (GoogleDrive) this.getNewPluginForHostInstance(this.getHost());
        plg.loginWebsite(this.br, account, false);
    }

    public boolean hasCaptcha(final CryptedLink link, final jd.plugins.Account acc) {
        return false;
    }

    public class GdriveFolderStatus implements Serializable {
        public final static int FOLDER_EMPTY                        = 1 << 1;
        public final static int FOLDER_EMPTY_OR_PRIVATE_OR_SHORTCUT = 1 << 2;
        public final static int FOLDER_OFFLINE                      = 1 << 3;
        public final static int FOLDER_PRIVATE                      = 1 << 4;
        public final static int FOLDER_PRIVATE_NO_ACCESS            = 1 << 5;
    }

    public class GdriveException extends Exception {
        private int    gdrivestatus = -1;
        private String offlineTitle = null;

        // public GdriveException(final int gdrivestatus) {
        // this.gdrivestatus = gdrivestatus;
        // }
        public GdriveException(final int gdrivestatus, final String offlineTitle) {
            this.gdrivestatus = gdrivestatus;
            this.offlineTitle = offlineTitle;
        }

        public int getGdriveStatus() {
            return this.gdrivestatus;
        }

        public String getOfflineTitle() {
            return this.offlineTitle;
        }
    }
}