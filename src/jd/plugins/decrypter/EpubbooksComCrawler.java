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

import org.appwork.utils.formatter.SizeFormatter;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
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
import jd.plugins.hoster.EpubbooksCom;

@DecrypterPlugin(revision = "$Revision: 48123 $", interfaceVersion = 3, names = { "epubbooks.com" }, urls = { "https?://(?:www\\.)?epubbooks\\.com/book/\\d+\\-[a-z0-9\\-]+" })
public class EpubbooksComCrawler extends PluginForDecrypt {
    public EpubbooksComCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final Account aa = AccountController.getInstance().getValidAccount(this.getHost());
        if (aa == null) {
            throw new AccountRequiredException();
        }
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String parameter = param.getCryptedUrl();
        // final PluginForHost hostplugin = this.getNewPluginForHostInstance(this.getHost());
        EpubbooksCom.login(this.br, aa, false);
        br.getPage(parameter);
        if (jd.plugins.hoster.EpubbooksCom.isOffline(this.br)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String url_title = new Regex(parameter, "/\\d+\\-([^/]+)$").getMatch(0);
        String fpName = br.getRegex("<title>([^<>]+)</title>").getMatch(0);
        if (fpName == null) {
            fpName = url_title;
        }
        final String[] htmls = br.getRegex("<li class=\"list\\-group\\-item clearfix\".*?</li>").getColumn(-1);
        if (htmls == null || htmls.length == 0) {
            logger.warning("Decrypter broken for link: " + parameter);
            return null;
        }
        for (final String html : htmls) {
            final String type = new Regex(html, "(EPUB|Kindle)").getMatch(0);
            String downloadlink = new Regex(html, "/downloads/\\d+").getMatch(-1);
            final String filesize = new Regex(html, "(\\d+ (?:KB|MB|GB))").getMatch(0);
            if (type == null || downloadlink == null || filesize == null) {
                return null;
            }
            /* 2017-01-23: At the moment they only have 2 formats available, EPUB (.epub) and Kindle (.mobi). */
            final String ext;
            if (type.equalsIgnoreCase("Kindle")) {
                ext = ".mobi";
            } else {
                ext = "." + type.toLowerCase();
            }
            downloadlink = "https://www." + this.getHost() + downloadlink;
            final DownloadLink dl = createDownloadlink(downloadlink);
            dl.setDownloadSize(SizeFormatter.getSize(filesize));
            dl.setName(url_title + ext);
            dl.setAvailable(true);
            dl.setProperty("mainlink", parameter);
            ret.add(dl);
        }
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(Encoding.htmlDecode(fpName).trim());
        fp.addLinks(ret);
        return ret;
    }
}
