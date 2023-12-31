//    jDownloader - Downloadmanager
//    Copyright (C) 2011  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
package jd.plugins.decrypter;

import java.util.ArrayList;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision: 45953 $", interfaceVersion = 2, names = { "mixotic.net" }, urls = { "https?://(?:www\\.)?mixotic\\.net/dj\\-(sets|mixes)/.*" })
public class MixoticNet extends PluginForDecrypt {
    public MixoticNet(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        // Logger logDebug = JDLogger.getLogger();
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        String strParameter = param.toString();
        if (strParameter.contains("?")) {
            int iPosition = strParameter.indexOf('?');
            if (iPosition > 0) {
                strParameter = strParameter.substring(0, iPosition);
            }
        }
        br.setFollowRedirects(true);
        br.getPage(strParameter);
        // Offline1
        if (br.containsHTML("(An error has occurred|The article cannot be found)")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        // Offline2
        if (br.containsHTML("Sorry, this page is not existing|<title>Error \\- Page not found</title>")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        int iIndex = strParameter.lastIndexOf("/");
        String strMixNumber = strParameter.substring(iIndex + 1);
        String strMixName = br.getRegex("<h2>(.*?)</h2>").getMatch(0);
        jd.parser.Regex rgMixDate = br.getRegex(">(19|20\\d\\d)[- /.](0[1-9]|1[012])[- /.](0[1-9]|[12][0-9]|3[01])<");
        String strMixDate = rgMixDate.getMatch(2) + "-" + rgMixDate.getMatch(1) + "-" + rgMixDate.getMatch(0);
        String strName = strMixNumber + " - " + strMixName + " (Mixotic Mix " + strMixNumber + ") (" + strMixDate + ")";
        String strRedirect = strParameter.replace("dj-sets", "dj-set-download");
        strRedirect = strRedirect.replace("dj-mixes", "dj-mix-download");
        br.getPage(strRedirect);
        String[] links = br.getRegex("<a href=\"/?files/(.*?)\"").getColumn(0);
        if (links == null || links.length == 0) {
            logger.warning("Decrypter broken for link: " + strParameter);
            return null;
        }
        // Added links
        for (String redirectlink : links) {
            redirectlink = "http://mixotic.net/files/" + redirectlink;
            DownloadLink DLLink = createDownloadlink(redirectlink);
            String strExtension = "";
            iIndex = redirectlink.lastIndexOf('.');
            if (iIndex > -1) {
                strExtension = redirectlink.substring(iIndex);
            }
            if (strExtension != "") {
                DLLink.setFinalFileName(strName + strExtension);
            }
            decryptedLinks.add(DLLink);
        }
        // Add all link in a package
        if (strName != null) {
            FilePackage fp = FilePackage.getInstance();
            fp.setName("Mixotic Mix");
            fp.addLinks(decryptedLinks);
        }
        return decryptedLinks;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}