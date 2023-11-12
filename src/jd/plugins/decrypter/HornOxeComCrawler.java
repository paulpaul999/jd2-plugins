//jDownloader - Downloadmanager
//Copyright (C) 2012  JD-Team support@jdownloader.org
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

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision: 47571 $", interfaceVersion = 2, names = { "hornoxe.com" }, urls = { "https?://(www\\.)?hornoxe\\.com/(?!category)[a-z0-9\\-]+/" })
public class HornOxeComCrawler extends PluginForDecrypt {
    public HornOxeComCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String INVALIDLINKS = "https?://(www\\.)?hornoxe\\.com/(picdumps|sonstiges|eigener\\-content|comics\\-cartoons|amazon|witze|fun\\-clips|fun\\-bilder|sexy|kurzfilme|bastelstunde|games|fun\\-links|natur\\-technik|feed|shop|category|images|page)/.*?";

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        String parameter = param.toString();
        if (parameter.matches(INVALIDLINKS)) {
            logger.info("Link invalid: " + parameter);
            return ret;
        }
        br.getPage(parameter);
        if (!this.canHandle(br.getURL())) {
            /* E.g. redirect to unsupported link / mainpage. */
            ret.add(createDownloadlink(br.getURL()));
            return ret;
        } else if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.getRequest().getHtmlCode().length() <= 100) {
            /* Empty page */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (!br.getHttpConnection().getContentType().contains("html")) {
            /* E.g. https://www.hornoxe.com/wp-json/ */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String pageName = br.getRegex("og:title\" content=\"(.*?)\" />").getMatch(0);
        if (pageName == null) {
            pageName = br.getRegex("<title>(.*?) \\- Hornoxe\\.com</title>").getMatch(0);
        }
        if (pageName == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        pageName = Encoding.htmlDecode(pageName.trim());
        // Check if there are embedded links
        String externID = br.getRegex("\"(//(www\\.)?youtube\\.com/embed/[^<>\"]*?)\"").getMatch(0);
        if (externID != null) {
            ret.add(createDownloadlink("http:" + externID));
        }
        // Check if we have a single video
        final String file = br.getRegex("(file\":|src\\s*=\\s*)\"(https?://videos\\.hornoxe\\.com/[^\"]+)").getMatch(1);
        if (file != null) {
            final DownloadLink vid = createDownloadlink(file.replace("hornoxe.com", "hornoxedecrypted.com"));
            vid.setFinalFileName(pageName + getFileNameExtensionFromURL(file));
            vid.setReferrerUrl(parameter);
            ret.add(vid);
            return ret;
        }
        // Check if we have a picdump
        String[] urls = null;
        if (parameter.contains("-gifdump")) {
            urls = br.getRegex("\\'(https?://gifdumps\\.hornoxe\\.com/gifdump[^<>\"]*?)\\'").getColumn(0);
        } else {
            urls = br.getRegex("\"(https?://(www\\.)?hornoxe\\.com/wp\\-content/picdumps/[^<>\"]*?)\"").getColumn(0);
            if (urls == null || urls.length == 0) {
                urls = br.getRegex("\"(https?://(www\\.)hornoxe\\.com/wp\\-content/uploads/(?!thumb)[^<>\"]+)\"").getColumn(0);
            }
        }
        if (urls != null && urls.length != 0) {
            String title = br.getRegex("<meta property=\"og\\:title\" content=\"(.*?)\" \\/>").getMatch(0);
            FilePackage fp = null;
            if (title != null) {
                fp = FilePackage.getInstance();
                fp.setName(Encoding.htmlDecode(title.trim()));
                fp.addLinks(ret);
            }
            add(ret, urls, fp);
            String[] pageqs = br.getRegex("\"page-numbers\" href=\"(.*?nggpage\\=\\d+)").getColumn(0);
            if (pageqs == null || pageqs.length == 0) {
                pageqs = br.getRegex("<a href=\"(https?://[^\"]*?/\\d+/)\">\\d+").getColumn(0);
            }
            for (String page : pageqs) {
                br.getPage(page);
                if (parameter.contains("-gifdump")) {
                    urls = br.getRegex("\\'(https?://gifdumps\\.hornoxe\\.com/gifdump[^<>\"]*?)\\'").getColumn(0);
                } else {
                    urls = br.getRegex("\"(https?://(www\\.)?hornoxe\\.com/wp\\-content/picdumps/[^<>\"]*?)\"").getColumn(0);
                    if (urls == null || urls.length == 0) {
                        urls = br.getRegex("\"(https?://(www\\.)hornoxe\\.com/wp\\-content/uploads[^<>\"]+)\"").getColumn(0);
                    }
                }
                add(ret, urls, fp);
            }
            return ret;
        }
        // Check if it's an image
        final String image = br.getRegex("\"(https?://(www\\.)hornoxe\\.com/wp\\-content/uploads[^<>\"]+)\"").getMatch(0);
        if (image != null) {
            final DownloadLink img = createDownloadlink("directhttp://" + image);
            img.setFinalFileName(pageName + image.substring(image.lastIndexOf(".")));
            ret.add(img);
            return ret;
        }
        return ret;
    }

    private void add(ArrayList<DownloadLink> decryptedLinks, String[] urls, FilePackage fp) {
        for (final String url : urls) {
            if (url.contains("fliege.gif")) {
                continue;
            }
            DownloadLink link = createDownloadlink("directhttp://" + url);
            fp.add(link);
            decryptedLinks.add(link);
            try {
                distribute(link);
            } catch (final Throwable e) {
            }
        }
    }

    @Override
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}