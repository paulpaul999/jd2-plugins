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
import java.util.regex.Pattern;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.parser.Regex;
import jd.parser.html.HTMLParser;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.hoster.AkwamCc;

@DecrypterPlugin(revision = "$Revision: 48166 $", interfaceVersion = 3, names = {}, urls = {})
@PluginDependencies(dependencies = { AkwamCc.class })
public class AkwamCcCrawler extends PluginForDecrypt {
    public AkwamCcCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        return AkwamCc.getPluginDomains();
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
            ret.add("https?://(?:\\w+\\.)?" + buildHostsPatternPart(domains) + "/(?!download).*");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        final String html = br.getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(new Regex(html, "<title>(.*)</title>").getMatch(0).replace(" | اكوام", ""));
        String[][] links;
        final boolean isSeries = (html.contains("series-episodes") || html.contains("show-episodes")) && !br.getURL().matches("https?://[^/]+/movie/.+");
        if (isSeries) {
            final String bulkHtml = new Regex(html, "(?i)(id=\"(?:series|show)-episodes\"[\\s\\S]+widget-4)").getMatch(0);
            links = new Regex(bulkHtml, "<a href=\"(https://" + Pattern.quote(br.getHost()) + "/[^\"]+)\"").getMatches();
        } else {
            links = new Regex(html, "<a href=\"([^\"]+)\"[^>]+(?:link-download|download-link)").getMatches();
        }
        if (links != null && links.length > 0) {
            for (final String[] link : links) {
                final String finalLink = link[0];
                final DownloadLink dl = createDownloadlink(finalLink);
                dl._setFilePackage(fp);
                ret.add(dl);
            }
        } else {
            /* Last resort */
            final Pattern redirectpattern = Pattern.compile("(http?://[^/]+/link/\\d+)");
            final PluginForHost plg = this.getNewPluginForHostInstance(this.getHost());
            final String[] urls = HTMLParser.getHttpLinks(br.getRequest().getHtmlCode(), br.getURL());
            for (final String url : urls) {
                if (plg.canHandle(url)) {
                    ret.add(this.createDownloadlink(url));
                } else if (new Regex(url, redirectpattern).patternFind()) {
                    ret.add(this.createDownloadlink(url));
                }
            }
        }
        if (ret.isEmpty()) {
            logger.info("Unsupported URL or crawler failure");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        return ret;
    }
}
