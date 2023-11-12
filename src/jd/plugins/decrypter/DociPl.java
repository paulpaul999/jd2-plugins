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
import java.util.regex.Matcher;

import org.appwork.utils.formatter.SizeFormatter;

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

@DecrypterPlugin(revision = "$Revision: 46794 $", interfaceVersion = 3, names = { "doci.pl" }, urls = { "https?://(?:www\\.)?doci\\.pl/[^\\?\\&]+" })
public class DociPl extends PluginForDecrypt {
    public DociPl(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String host_plugin_string = "docidecrypted://";
        jd.plugins.hoster.DociPl.prepBR(this.br);
        br.getPage(param.getCryptedUrl());
        if (jd.plugins.hoster.DociPl.isOffline(this.br)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String fpName = br.getRegex("<title>([^<>\"]+)(?:\\- Doci\\.pl)?</title>").getMatch(0);
        final String docid = jd.plugins.hoster.DociPl.getDocumentID(this.br);
        if (docid != null) {
            /* The current url is a single file ... */
            final DownloadLink dl = this.createDownloadlink(param.getCryptedUrl().replaceAll("https?://", host_plugin_string));
            jd.plugins.hoster.DociPl.setDownloadlinkInformation(this.br, dl);
            dl.setAvailable(true);
            dl.setLinkID(docid);
            ret.add(dl);
            return ret;
        }
        /* Crawl subfolders */
        final String[] folders = br.getRegex("<article\\s*class\\s*=\\s*\"elem\"\\s*>\\s*<header>\\s*<img[^<>]*?dir[^<>]*?>\\s*<p[^<>]*?>\\s*<a href=\"(/[^<>\"]+)\"").getColumn(0);
        for (final String singleLink : folders) {
            final String url = br.getURL(singleLink).toString();
            ret.add(createDownloadlink(url));
        }
        /* Crawl files */
        final String[][] files = br.getRegex("class=\"text\\-ellipsis elipsis\\-file\"[^>]*?><a href=\"(/[^<>\"]+)\"\\s*>\\s*(.*?)\\s*<.*?Rozmiar\\s*:\\s*([0-9\\.]+\\s*[GKM]*B)").getMatches();
        if (files == null || files.length == 0) {
            if (folders != null && folders.length > 0) {
                return ret;
            }
            logger.info("Failed to find any downloadable content");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        for (final String singleLink[] : files) {
            String filename = singleLink[1];
            filename = this.correctOrApplyFileNameExtension(filename, ".pdf");
            final String url = br.getURL(singleLink[0]).toString().replaceFirst("https?://", Matcher.quoteReplacement(host_plugin_string));
            final DownloadLink link = createDownloadlink(url);
            link.setAvailable(true);
            link.setName(filename);
            link.setDownloadSize(SizeFormatter.getSize(singleLink[2]));
            ret.add(link);
        }
        if (ret.size() == 0 && !isAbort()) {
            logger.info("Possible empty folder");
        }
        if (fpName != null) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(fpName.trim()));
            fp.addLinks(ret);
        }
        return ret;
    }
}
