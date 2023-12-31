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

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision: 45872 $", interfaceVersion = 2, names = { "tiny.cc" }, urls = { "https?://(www\\.)?tiny\\.cc/[0-9a-zA-Z]+" })
public class TinyCc extends PluginForDecrypt {
    public TinyCc(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String INVALIDLINKS = "https?://(www\\.)?tiny\\.cc/(url|example|help|tools|qrcodes|api|contact|terms|domains|app)";

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        if (parameter.matches(INVALIDLINKS)) {
            logger.info("Link invalid: " + parameter);
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        br.setFollowRedirects(false);
        br.getPage(parameter);
        if (br.containsHTML(" we weren't able to locate that URL")) {
            logger.info("Link offline: " + parameter);
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String finallink = br.getRedirectLocation();
        if (finallink == null) {
            logger.warning("Decrypter broken for link: " + parameter);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        decryptedLinks.add(createDownloadlink(finallink));
        return decryptedLinks;
    }
}
