//    jDownloader - Downloadmanager
//    Copyright (C) 2020  JD-Team support@jdownloader.org
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
package jd.plugins.decrypter;

import java.util.ArrayList;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

import org.jdownloader.plugins.components.antiDDoSForDecrypt;

@DecrypterPlugin(revision = "$Revision: 42000 $", interfaceVersion = 2, names = { "streamcrypt.net" }, urls = { "https?://[\\w.]*?streamcrypt\\.net/(?:hoster\\.[\\w.]+?\\.php\\?id=|[^/]+/)\\p{Alnum}++(?:-\\d+x\\d+\\.html)?" })
public class StreamCryptNet extends antiDDoSForDecrypt {
    public StreamCryptNet(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        br.setFollowRedirects(false);
        getPage(param.getCryptedUrl());
        if (br.getRedirectLocation() == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        } else {
            final DownloadLink downloadLink = createDownloadlink(br.getRedirectLocation());
            downloadLink.setProperty("redirect_link", param.getCryptedUrl());
            decryptedLinks.add(downloadLink);
            return decryptedLinks;
        }
    }
}
