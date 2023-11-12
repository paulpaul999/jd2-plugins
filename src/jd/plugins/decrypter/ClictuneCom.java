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

import org.jdownloader.plugins.components.antiDDoSForDecrypt;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;

@DecrypterPlugin(revision = "$Revision: 43304 $", interfaceVersion = 3, names = { "clictune.com" }, urls = { "https?://(?:www\\.)?(?:mylinks\\.xyz|clictune\\.com)/([A-Za-z0-9]+)" })
public class ClictuneCom extends antiDDoSForDecrypt {
    public ClictuneCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        br.setFollowRedirects(true);
        getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404 || br.getHttpConnection().getResponseCode() == 403) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        String finallink = this.br.getRegex("redirect/\\?url=(http[^<>\"]+)\">Click here to access the link").getMatch(0);
        if (finallink == null) {
            /* Assume that URL is offline. */
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        finallink = Encoding.htmlDecode(finallink);
        decryptedLinks.add(createDownloadlink(finallink));
        return decryptedLinks;
    }
}
