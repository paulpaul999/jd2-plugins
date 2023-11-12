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
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginDependencies;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.PostimagesOrg;

@DecrypterPlugin(revision = "$Revision: 46483 $", interfaceVersion = 3, names = {}, urls = {})
@PluginDependencies(dependencies = { PostimagesOrg.class })
public class PostimagesOrgGallery extends PluginForDecrypt {
    public PostimagesOrgGallery(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static List<String[]> getPluginDomains() {
        return PostimagesOrg.getPluginDomains();
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
        final String galleryID = new Regex(param.getCryptedUrl(), this.getSupportedLinks()).getMatch(0);
        br.setFollowRedirects(true);
        br.getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String[] htmls = br.getRegex("<div class=\"col-sm-3 col-md-2 thumb-container\"(.*?)class=\"image-controls\"").getColumn(0);
        for (final String html : htmls) {
            final String imageID = new Regex(html, "data-image=\"([A-Za-z0-9]+)\"").getMatch(0);
            final String title = new Regex(html, "data-name=\"([^\"]+)").getMatch(0);
            final String ext = new Regex(html, "data-ext=\"([A-Za-z]+)\"").getMatch(0);
            final DownloadLink image = this.createDownloadlink(br.getURL("/" + imageID).toString());
            image.setAvailable(true);
            if (title != null && ext != null) {
                image.setName(Encoding.htmlDecode(title).trim() + "." + ext);
            }
            ret.add(image);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(galleryID);
        fp.addLinks(ret);
        return ret;
    }
}
