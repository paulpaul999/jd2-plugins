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

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.HTMLSearch;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.DirectHTTP;
import jd.plugins.hoster.YoutubeDashV2;

@DecrypterPlugin(revision = "$Revision: 48262 $", interfaceVersion = 3, names = {}, urls = {})
public class SlidesliveCom extends PluginForDecrypt {
    public SlidesliveCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "slideslive.com" });
        return ret;
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(\\d+)/([\\w\\-]+)");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        br.getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Regex urlinfo = new Regex(param.getCryptedUrl(), this.getSupportedLinks());
        final String contentID = urlinfo.getMatch(0);
        final String playerToken = br.getRegex("data-player-token=\"([^\"]+)").getMatch(0);
        final String slidesHost = br.getRegex("slideslive_on_the_fly_resized_slides_host\":\"([^\"]+)").getMatch(0);
        String title = HTMLSearch.searchMetaTag(br, "twitter:title");
        if (title == null) {
            /* Fallback */
            title = urlinfo.getMatch(1).replace("-", " ").trim();
        }
        title = Encoding.htmlDecode(title).trim();
        if (playerToken == null || slidesHost == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        br.getPage("https://ben.slideslive.com/player/" + contentID + "?player_token=" + Encoding.urlEncode(playerToken));
        final String youtubeVideoID = br.getRegex("EXT-SL-VOD-VIDEO-ID:(.+)").getMatch(0);
        if (youtubeVideoID == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        ret.add(this.createDownloadlink(YoutubeDashV2.generateContentURL(youtubeVideoID)));
        final Browser brc = br.cloneBrowser();
        brc.getPage("https://slides.slideslive.com/" + contentID + "/" + contentID + ".xml");
        final String[] items = brc.getRegex("<slideName>([^<]+)</slideName>").getColumn(0);
        if (items == null || items.length == 0) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String imagesExt = ".png";
        for (final String slideName : items) {
            final String directurl = "https://" + slidesHost + "/" + contentID + "/slides/" + slideName + imagesExt + "?h=432&f=webp&s=lambda&accelerate_s3=1";
            final DownloadLink image = createDownloadlink(DirectHTTP.createURLForThisPlugin(directurl));
            image.setAvailable(true);
            ret.add(image);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(title);
        fp.addLinks(ret);
        return ret;
    }
}
