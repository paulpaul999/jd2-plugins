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
import java.util.Collections;

import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.components.antiDDoSForDecrypt;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@DecrypterPlugin(revision = "$Revision: 46827 $", interfaceVersion = 3, names = { "newcomic.info" }, urls = { "https?://(\\w+\\.)?newcomic\\.info/(tags/)?([\\w\\-\\.\\%/]+)" })
public class NewComicInfo extends antiDDoSForDecrypt {
    public NewComicInfo(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void init() {
        super.init();
        Browser.setRequestIntervalLimitGlobal(getHost(), 500);
    }

    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        getPage(param.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String fpName = br.getRegex("(?:og:)?(?:title|description)\\\"[^>]*content=[\\\"'](?:\\s*Watch\\s*Couchtuner\\s*)?([^\\\"\\']+)\\s+(?:online\\s+for\\s+free|\\|)").getMatch(0);
        ArrayList<String> links = new ArrayList<String>();
        String linkBlock = br.getRegex("<div[^>]+class\\s*=\\s*\"newcomic-m-buttons\"[^>]*>\\s*([^°]+)\\s*<div[^>]+class\\s*=\\s*\"newcomic-section newcomic-related\"").getMatch(0);
        if (linkBlock != null) {
            Collections.addAll(links, new Regex(linkBlock, "<a[^>]+href\\s*=\\s*\"([^\"]+)").getColumn(0));
        }
        if (links.isEmpty()) {
            Collections.addAll(links, br.getRegex("<div[^>]*class\\s*=\\s*\\\"[^\\\"]*newcomic-mask-bottom[^\\\"]*\\\"[^>]*>\\s*<a[^>]*href\\s*=\\s*\\\"\\s*([^\\\"\\s]*)").getColumn(0));
            Collections.addAll(links, br.getRegex("<a[^>]+href\\s*=\\s*\"([^\\\"]+/tags/[^\\\"]+)\"[^>]*>(Previous|Next|\\d+)").getColumn(0));
        }
        if (!links.isEmpty()) {
            for (String link : links) {
                link = Encoding.htmlDecode(link).replaceAll("^//", "https://");
                decryptedLinks.add(createDownloadlink(link));
            }
            if (StringUtils.isNotEmpty(fpName)) {
                final FilePackage fp = FilePackage.getInstance();
                fp.setName(Encoding.htmlDecode(fpName).trim());
                fp.addLinks(decryptedLinks);
            }
        }
        return decryptedLinks;
    }
}