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

import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
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
import jd.plugins.hoster.PixhostTo;

@DecrypterPlugin(revision = "$Revision: 48358 $", interfaceVersion = 3, names = {}, urls = {})
@PluginDependencies(dependencies = { PixhostTo.class })
public class PixhostToGallery extends PluginForDecrypt {
    public PixhostToGallery(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.IMAGE_HOST, LazyPlugin.FEATURE.IMAGE_GALLERY };
    }

    private static List<String[]> getPluginDomains() {
        return PixhostTo.getPluginDomains();
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/gallery/([A-Za-z0-9]+)");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        br.getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML(">\\s*Gallery doesn't exist")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String galleryTitle = br.getRegex("<h2>([^<]+)</h2>").getMatch(0);
        if (galleryTitle == null) {
            /* Fallback */
            logger.warning("Failed to find galleryTitle in html code");
            galleryTitle = "";
        }
        galleryTitle = Encoding.htmlDecode(galleryTitle).trim();
        final PluginForHost plg = this.getNewPluginForHostInstance(this.getHost());
        final String[] urls = HTMLParser.getHttpLinks(br.getRequest().getHtmlCode(), br.getURL());
        if (urls == null || urls.length == 0) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        for (String url : urls) {
            /* Fix broken URLs */
            url = url.replaceAll("%5D%5Bimg.+", "");
            if (plg.canHandle(url)) {
                final DownloadLink image = createDownloadlink(url);
                image.setAvailable(true);
                ret.add(image);
            }
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(galleryTitle);
        fp.addLinks(ret);
        return ret;
    }
}
