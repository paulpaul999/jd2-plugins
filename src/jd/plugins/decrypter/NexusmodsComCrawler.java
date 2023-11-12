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
import java.util.Map;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
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
import jd.utils.JDUtilities;

@DecrypterPlugin(revision = "$Revision: 46811 $", interfaceVersion = 3, names = { "nexusmods.com" }, urls = { "https?://(?:www\\.)?nexusmods\\.com/(?!contents)([^/]+)/mods/(\\d+)/?" })
public class NexusmodsComCrawler extends PluginForDecrypt {
    public NexusmodsComCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String url = param.toString().replaceFirst("^http://", "https://");
        final PluginForHost plugin = JDUtilities.getPluginForHost(this.getHost());
        ((jd.plugins.hoster.NexusmodsCom) plugin).setLogger(getLogger());
        ((jd.plugins.hoster.NexusmodsCom) plugin).setBrowser(br);
        final String game_domain_name = new Regex(url, this.getSupportedLinks()).getMatch(0);
        final String mod_id = new Regex(url, this.getSupportedLinks()).getMatch(1);
        if (game_domain_name == null || mod_id == null) {
            /* This should never happen */
            logger.warning("game_domain_name or mod_id missing");
            return null;
        }
        final Account account = AccountController.getInstance().getValidAccount(plugin.getHost());
        final String apikey = jd.plugins.hoster.NexusmodsCom.getApikey(account);
        if (apikey != null) {
            try {
                ret = crawlAPI(param, account, game_domain_name, mod_id);
            } catch (final PluginException e) {
                /* Offline errorhandling as crawler + hosterplugins share the code for errorhandling. */
                if (e.getLinkStatus() == LinkStatus.ERROR_FILE_NOT_FOUND) {
                    ret.add(this.createOfflinelink(url));
                    return ret;
                }
                throw e;
            }
        } else {
            ret = crawlWebsite(param, account, game_domain_name, mod_id);
        }
        return ret;
    }

    private ArrayList<DownloadLink> crawlAPI(final CryptedLink param, final Account account, final String game_domain_name, final String mod_id) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        jd.plugins.hoster.NexusmodsCom.prepBrAPI(br, account);
        /* First check for offline, get game_id and name of the mod */
        br.getPage(jd.plugins.hoster.NexusmodsCom.API_BASE + String.format("/games/%s/mods/%s.json", game_domain_name, mod_id));
        /* 1st offline check */
        jd.plugins.hoster.NexusmodsCom.handleErrorsAPI(br);
        Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
        final String game_id = Long.toString(JavaScriptEngineFactory.toLong(entries.get("game_id"), -1));
        if (game_id.equals("-1")) {
            return null;
        }
        String mod_name = (String) entries.get("name");
        if (StringUtils.isEmpty(mod_name)) {
            /* This should never happen */
            mod_name = "UNKNOWN";
        }
        br.getPage(jd.plugins.hoster.NexusmodsCom.API_BASE + String.format("/games/%s/mods/%s/files.json", game_domain_name, mod_id));
        /* 2nd offline check */
        jd.plugins.hoster.NexusmodsCom.handleErrorsAPI(br);
        entries = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
        final List<Object> files = (List<Object>) entries.get("files");
        for (final Object fileO : files) {
            entries = ((Map<String, Object>) fileO);
            final String file_id = Long.toString(JavaScriptEngineFactory.toLong(entries.get("file_id"), -1));
            final String description = (String) entries.get("description");
            /* This was the old way to get the game_id */
            // String game_id = null;
            // final String content_preview_link = (String) entries.get("content_preview_link");
            // if (content_preview_link != null) {
            // /* That's a little sketchy as they do not provide a field for this 'game_id' but we need that! */
            // game_id = new Regex(content_preview_link, "nexus-files-meta/(\\d+)/").getMatch(0);
            // }
            if (file_id.equals("-1")) {
                /* Skip invalid items */
                continue;
            }
            final int category_id = (int) JavaScriptEngineFactory.toLong(entries.get("category_id"), 0);
            String category_name = (String) entries.get("category_name");
            if (StringUtils.isEmpty(category_name)) {
                /* Fallback/Workaround */
                category_name = apiCategoryIDToString(category_id);
            }
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(game_domain_name + " - " + mod_name + " - " + category_name);
            final DownloadLink link = createDownloadlink(generatePluginPatternMatcher(file_id, game_id));
            link.setContentUrl(generateContentURL(game_domain_name, mod_id, file_id));
            jd.plugins.hoster.NexusmodsCom.setFileInformationAPI(link, entries, game_domain_name, mod_id, file_id);
            link._setFilePackage(fp);
            /* Important! These properties are especially required for all API requests! */
            link.setProperty(jd.plugins.hoster.NexusmodsCom.PROPERTY_game_domain_name, game_domain_name);
            link.setProperty(jd.plugins.hoster.NexusmodsCom.PROPERTY_mod_id, mod_id);
            /* Every category goes into a subfolder */
            link.setRelativeDownloadFolderPath(game_domain_name + "/" + mod_name + "/" + category_name);
            link.setAvailable(true);
            if (!StringUtils.isEmpty(description)) {
                link.setComment(description);
            }
            ret.add(link);
        }
        return ret;
    }

    private ArrayList<DownloadLink> crawlWebsite(final CryptedLink param, final Account account, final String game_domain_name, final String mod_id) throws Exception {
        final String parameter = param.toString();
        final PluginForHost plugin = JDUtilities.getPluginForHost(this.getHost());
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        if (account != null) {
            /* Login via website */
            ((jd.plugins.hoster.NexusmodsCom) plugin).loginWebsite(account);
        }
        br.setFollowRedirects(true);
        ((jd.plugins.hoster.NexusmodsCom) plugin).getPage(br, parameter);
        if (jd.plugins.hoster.NexusmodsCom.isOfflineWebsite(br)) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        } else if (((jd.plugins.hoster.NexusmodsCom) plugin).isLoginRequired(br)) {
            throw new AccountRequiredException();
        } else if (br.containsHTML(">\\s*This mod contains adult content")) {
            /* 2019-10-02: Account required + setting has to be enabled in account to be able to see/download such content! */
            logger.info("Adult content: Enable it in your account settings to be able to download such files via JD: Profile --> Settings --> Content blocking --> Show adult content");
            throw new AccountRequiredException("Adult content: Enable it in your account settings to be able to download such files via JD: Profile --> Settings --> Content blocking --> Show adult content");
        }
        String mod_name = br.getRegex("<meta property=\"og:title\" content=\"([^<>\"]+)\"").getMatch(0);
        if (mod_name == null) {
            /* This should never happen */
            mod_name = "UNKNOWN";
        }
        final String game_id = br.getRegex("game_id\\s*=\\s*(\\d+)").getMatch(0);
        if (game_id == null) {
            logger.warning("Failed to find game_id");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final Browser br2 = br.cloneBrowser();
        ((jd.plugins.hoster.NexusmodsCom) plugin).getPage(br2, "/Core/Libs/Common/Widgets/ModFilesTab?id=" + mod_id + "&game_id=" + game_id);
        final String[] downloadTypesHTMLs = br2.getRegex("<div class=\"file-category-header\">\\s*<h2>[^<>]+</h2>\\s*<div>.*?</dd>\\s*</dl>\\s*</div>").getColumn(-1);
        int counter = 0;
        for (final String downnloadTypeHTML : downloadTypesHTMLs) {
            counter++;
            String category_name = new Regex(downnloadTypeHTML, "<h2>([^<>\"]+)</h2>").getMatch(0);
            if (category_name == null) {
                /* Fallback */
                category_name = "Unknown_category_" + counter;
            }
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(game_domain_name + " - " + mod_name + " - " + category_name);
            category_name = Encoding.htmlDecode(category_name).trim();
            final String currentPath = game_domain_name + "/" + mod_name + "/" + category_name;
            final String[] htmls = new Regex(downnloadTypeHTML, "<dt id=\"file-expander-header-\\d+\".*?</div>\\s*</dd>").getColumn(-1);
            if (htmls == null || htmls.length == 0) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            for (final String html : htmls) {
                String file_id = new Regex(html, "\\?id=(\\d+)").getMatch(0);
                if (file_id == null) {
                    file_id = new Regex(html, "data-id=\"(\\d+)\"").getMatch(0);
                }
                if (file_id == null) {
                    logger.info("file_id is null");
                    continue;
                }
                final String filename = new Regex(html, "data-url=\"([^<>\"]+)\"").getMatch(0);
                final DownloadLink link = createDownloadlink(generatePluginPatternMatcher(file_id, game_id));
                link.setContentUrl(generateContentURL(game_domain_name, mod_id, file_id));
                final String filesizeStr = new Regex(html, ">\\s*File\\s*size\\s*</div>.*?\"stat\"\\s*>\\s*([0-9\\.TKGMB]+)").getMatch(0);
                if (filesizeStr != null) {
                    link.setDownloadSize(SizeFormatter.getSize(filesizeStr));
                }
                if (filename != null) {
                    link.setName(file_id + "_" + Encoding.htmlOnlyDecode(filename));
                } else {
                    link.setName(file_id);
                }
                link.setAvailable(true);
                link.setMimeHint(CompiledFiletypeFilter.ArchiveExtensions.ZIP);
                link.setRelativeDownloadFolderPath(currentPath);
                link._setFilePackage(fp);
                /* Important! These properties are especially required for all API requests! */
                link.setProperty("game_domain_name", game_domain_name);
                link.setProperty("mod_id", mod_id);
                /* Every category goes into a subfolder */
                link.setRelativeDownloadFolderPath(game_domain_name + "/" + category_name);
                decryptedLinks.add(link);
            }
        }
        return decryptedLinks;
    }

    private String generateContentURL(final String game_domain_name, final String mod_id, final String file_id) {
        return String.format("https://www." + this.getHost() + "/%s/mods/%s?tab=files&file_id=%s", game_domain_name, mod_id, file_id);
    }

    private String generatePluginPatternMatcher(final String file_id, final String game_id) {
        return String.format("https://www." + this.getHost() + "/Core/Libs/Common/Widgets/DownloadPopUp?id=%s&nmm=0&game_id=%s&source=FileExpander", file_id, game_id);
    }

    /*
     * Sadly there is not always a mapping via API so we will have to keep this updated by hand but they will probably not add / change
     * these category IDs in the near future! Especially for files of category 6 their API will often return 'null' as 'category_name'.
     */
    private String apiCategoryIDToString(final int cetegoryID) {
        switch (cetegoryID) {
        case 1:
            return "MAIN";
        case 2:
            return "UPDATE";
        case 3:
            return "OPTIONAL";
        case 4:
            return "OLD_VERSION";
        case 5:
            return "MISCELLANEOUS";
        case 6:
            return "OLD FILES";
        default:
            return "UNKNOWN_CATEGORY_ID_" + cetegoryID;
        }
    }
}
