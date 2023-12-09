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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.appwork.utils.Regex;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.RecurbateCom;

@DecrypterPlugin(revision = "$Revision: 48569 $", interfaceVersion = 3, names = {}, urls = {})
@PluginDependencies(dependencies = { RecurbateCom.class })
public class RecurbateComProfile extends PluginForDecrypt {
    public RecurbateComProfile(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        return RecurbateCom.getPluginDomains();
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/performer/([^/]+)");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String usernameSlug = new Regex(param.getCryptedUrl(), this.getSupportedLinks()).getMatch(0);
        final String username = Encoding.htmlDecode(usernameSlug).trim();
        /* Init hosterplugin so we're using the same browser (same headers/settings). */
        final RecurbateCom hosterplugin = (RecurbateCom) this.getNewPluginForHostInstance(this.getHost());
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        final List<String> deadDomains = hosterplugin.getDeadDomains();
        String contenturl = param.getCryptedUrl();
        final String hostFromURL = Browser.getHost(contenturl);
        if (deadDomains.contains(hostFromURL)) {
            contenturl = contenturl.replaceFirst(Pattern.quote(hostFromURL), this.getHost());
        }
        if (account != null) {
            /* Login whenever possible. This is not needed to get the content but can help to get around Cloudflare. */
            hosterplugin.login(account, contenturl, true);
        } else {
            br.getPage(contenturl);
        }
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(username);
        int page = 0;
        final Set<String> dupes = new HashSet<String>();
        do {
            page += 1;
            final String[] videourls = br.getRegex("(/video/\\d+/play)").getColumn(0);
            if (videourls == null || videourls.length == 0) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            int numberofNewItems = 0;
            for (final String videourl : videourls) {
                if (!dupes.add(videourl)) {
                    /* Skip duplicates */
                    continue;
                }
                numberofNewItems++;
                final String videoDetails = br.getRegex(Pattern.quote(videourl) + "\">(.*?<div\\s*class\\s*=\\s*\"video-info-sub.*?</div>)").getMatch(0);
                final DownloadLink dlvideo = createDownloadlink(br.getURL(videourl).toExternalForm());
                if (videoDetails != null) {
                    final String dateStr = new Regex(videoDetails, "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})").getMatch(0);
                    RecurbateCom.setDate(dlvideo, dateStr);
                }
                dlvideo.setProperty(RecurbateCom.PROPERTY_USER, username);
                hosterplugin.setFilename(dlvideo);
                dlvideo.setAvailable(true);
                dlvideo._setFilePackage(fp);
                ret.add(dlvideo);
                distribute(dlvideo);
            }
            logger.info("Crawled page " + page + " | Found items so far: " + ret.size());
            final String nextpage = br.getRegex("(/performer/" + Pattern.quote(usernameSlug) + "/page/" + (page + 1) + ")").getMatch(0);
            if (this.isAbort()) {
                logger.warning("Stopping because: Aborted by user");
                break;
            } else if (numberofNewItems == 0) {
                logger.warning("Stopping because: Failed to find any NEW items on current page");
                break;
            } else if (nextpage == null) {
                logger.info("Stopping because: Looks like we've reached last page: " + page);
                break;
            } else {
                logger.info("Found number of items so far: " + ret.size() + " | Continuing to next page: " + nextpage);
                br.getPage(nextpage);
                continue;
            }
        } while (true);
        return ret;
    }
}
