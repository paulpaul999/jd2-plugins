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

import java.text.DecimalFormat;
import java.util.ArrayList;

import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision: 48359 $", interfaceVersion = 3, names = { "mangatown.com" }, urls = { "https?://(?:www\\.)?mangatown\\.com/manga/[^/]+/c\\d+/(?:\\d+\\.html)?" })
public class MangatownComCrawler extends PluginForDecrypt {
    public MangatownComCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.IMAGE_HOST, LazyPlugin.FEATURE.IMAGE_GALLERY };
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String parameter = param.getCryptedUrl();
        this.br.setFollowRedirects(true);
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404 || this.br.containsHTML("class=\"mangaread_next_info\"")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML(">\\s*The series [^>]* has been licensed")) {
            /* 2020-11-11: >The series Onepunch-Man has been licensed, it is not available in MangaTown. </div> */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (!this.canHandle(this.br.getURL())) {
            /* 2021-03-02: E.g. redirect to mainpage or search */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Regex urlinfo = new Regex(parameter, "mangatown\\.com/manga/([^/]+)/c(\\d+)/");
        final String chapter_str = urlinfo.getMatch(1);
        final short chapter = Short.parseShort(chapter_str);
        final String url_name = urlinfo.getMatch(0);
        final String url_fpname = url_name + "_chapter_" + chapter_str;
        final DecimalFormat df_chapter = new DecimalFormat("0000");
        final DecimalFormat df_page = new DecimalFormat("000");
        final Regex downloadinfo = this.br.getRegex("\"[^\"]*([A-Za-z0-9\\-]+\\.[^/]+/store/manga/[^\"]+)\\d+(\\.[A-Za-z0-9]+)");
        final String server_urlpart = downloadinfo.getMatch(0);
        final String ext = downloadinfo.getMatch(1);
        if (server_urlpart == null || ext == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        short page_max = 1;
        final String[] pages = this.br.getRegex("<option value=\"[^<>\"]+\"[^<>]*>(\\d+)</option>").getColumn(0);
        for (final String page_temp_str : pages) {
            final short page_temp = Short.parseShort(page_temp_str);
            if (page_temp > page_max) {
                page_max = page_temp;
            }
        }
        for (short page = 1; page <= page_max; page++) {
            final String chapter_formatted = df_chapter.format(chapter);
            final String page_formatted = df_page.format(page);
            final String content_url = String.format("https://www.mangatown.com/manga/%s/c%s/%d.html", url_name, chapter_str, page);
            final DownloadLink dl = this.createDownloadlink(content_url);
            dl.setFinalFileName(url_name + "_" + chapter_formatted + "_" + page_formatted + ext);
            dl.setAvailable(true);
            ret.add(dl);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(url_fpname);
        fp.addLinks(ret);
        return ret;
    }
}
