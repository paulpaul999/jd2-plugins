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
import jd.controlling.ProgressController;
import jd.http.Request;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;

@DecrypterPlugin(revision = "$Revision: 46795 $", interfaceVersion = 3, names = { "files.fm" }, urls = { "https?://(?:\\w+\\.)?files\\.fm/u/[a-z0-9]+" })
public class FilesFmFolder extends PluginForDecrypt {
    public FilesFmFolder(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final PluginForHost hostplg = this.getNewPluginForHostInstance(this.getHost());
        /* 2016-03-10: They enforce https */
        final String folderID = new Regex(param.getCryptedUrl(), "([a-z0-9]+)$").getMatch(0);
        br.setFollowRedirects(true);
        br.getPage("https://files.fm/u/" + folderID + "?view=gallery&items_only=true&index=0&count=10000");
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML(">This link does not contain any files|These files are deleted by the owner<|The expiry date of these files is over<|class=\"deleted_wrapper\"")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (this.br.containsHTML("id=\"ist_no_files_message\"")) {
            throw new DecrypterRetryException(RetryReason.EMPTY_FOLDER, folderID);
        } else if (br.containsHTML("list_private_upload_msg")) {
            /* 2020-06-25: Private file which only the owner can access */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (this.br.containsHTML("name=\"upl_passw\"")) {
            /* 2017-01-30: Password protected */
            logger.info("Password protected urls are not yet supported");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String fpName = null;
        String[] folders = br.getRegex("files\\.fm/u/([a-z0-9]+)").getColumn(0);
        for (String folderIDTmp : folders) {
            /* Do not re-add current folder */
            if (folderIDTmp.equals(folderID)) {
                continue;
            }
            final String contentUrl = br.getURL("/u/" + folderIDTmp).toString();
            ret.add(createDownloadlink(contentUrl));
        }
        String[] links = br.getRegex("id=\"report_[^\"]+\".*?class=\"OrderID\"").getColumn(-1);
        if (links == null || links.length == 0) {
            if (folders != null && folders.length > 0) {
                return ret;
            }
        }
        if (links == null || links.length == 0) {
            if (hostplg.canHandle(br.getURL())) {
                /* Folder redirected to single file-link */
                ret.add(this.createDownloadlink(br.getURL()));
                return ret;
            }
            return null;
        }
        for (final String singleLink : links) {
            String filename = new Regex(singleLink, "class=\"full-file-name\">([^<>\"]+)<").getMatch(0);
            final String ext = new Regex(singleLink, "class=\"filename-extension\"[^>]*>([^<>\"]+)<").getMatch(0);
            final String filesize = new Regex(singleLink, "class=\"file_size\">([^<>}\"]*?)<").getMatch(0);
            String fileid = new Regex(singleLink, "(?:\\?|&)i=([a-z0-9]+)").getMatch(0);
            if (fileid == null) {
                /* 2021-01-25 */
                fileid = new Regex(singleLink, "id=\"report_([a-z0-9]+)\"").getMatch(0);
            }
            if (filename == null || filesize == null || fileid == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            filename = Encoding.htmlDecode(filename);
            if (!filename.endsWith(ext)) {
                filename += ext;
            }
            final String contentUrl = Request.getLocation("/down.php?i=" + fileid + "&n=" + filename, br.getRequest());
            final DownloadLink dl = createDownloadlink(contentUrl);
            dl.setProperty("mainlink", param.getCryptedUrl());
            dl.setContentUrl(contentUrl);
            dl.setLinkID(fileid);
            dl.setAvailable(true);
            dl.setName(Encoding.htmlDecode(filename));
            dl.setDownloadSize(SizeFormatter.getSize(Encoding.htmlDecode(filesize)));
            dl.setProperty("originalname", filename);
            ret.add(dl);
        }
        if (fpName != null) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(fpName.trim()));
            fp.addLinks(ret);
        }
        return ret;
    }
}
