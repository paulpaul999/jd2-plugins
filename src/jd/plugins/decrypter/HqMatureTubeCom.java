//jDownloader - Downloadmanager
//Copyright (C) 2011  JD-Team support@jdownloader.org
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

import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision: 45822 $", interfaceVersion = 3, names = { "hqmaturetube.com" }, urls = { "https?://(?:www\\.)?hqmaturetube\\.com/(cms/watch/\\d+\\.php|go\\?vx=[a-zA-Z0-9_/\\+\\=\\-%]+)" })
public class HqMatureTubeCom extends PluginForDecrypt {
    public HqMatureTubeCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    private static final String TYPE_BASE64 = "https?://[^/]+/go\\?vx=(.+)";

    // This is a site which shows embedded videos of other sites so we may have
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        if (parameter.matches(TYPE_BASE64)) {
            /* 2021-02-15: new */
            // String base64String = new Regex(parameter, TYPE_BASE64).getMatch(0);
            // base64String = Encoding.htmlDecode(base64String);
            // final String decoded = Encoding.Base64Decode(base64String);
            br.setFollowRedirects(false);
            br.getPage(parameter);
            final String finallink = br.getRedirectLocation();
            if (finallink != null) {
                if (finallink.contains("hellporno")) {
                    logger.warning("WTF");
                }
                decryptedLinks.add(this.createDownloadlink(finallink));
            } else {
                decryptedLinks.add(this.createOfflinelink(parameter));
            }
            return decryptedLinks;
        } else {
            br.setFollowRedirects(true);
            br.getPage(parameter);
            if (br.containsHTML("(<TITLE>404 Not Found</TITLE>|<H1>Not Found</H1>|was not found on this server\\.<P>)") || br.getURL().equals("http://www.hqmaturetube.com/")) {
                decryptedLinks.add(createOfflinelink(parameter, "Offline Content"));
                return decryptedLinks;
            }
            String filename = br.getRegex("<title>(.*?) \\| HQ Mature Tube \\| Free streaming porn videos</title>").getMatch(0);
            if (filename == null) {
                filename = br.getRegex("<h2 style=\"text-transform:uppercase;\">(.*?)</h2>").getMatch(0);
            }
            if (filename == null) {
                logger.warning("hqmaturetube decrypter broken(filename regex) for link: " + parameter);
                return null;
            }
            filename = filename.trim();
            String externID = br.getRegex("value=\"config=embedding_feed\\.php\\?viewkey=(.*?)\"").getMatch(0);
            if (externID != null) {
                // Find original empflix link and add it to the list
                br.getPage("http://www.empflix.com/embedding_player/embedding_feed.php?viewkey=" + externID);
                String finallink = br.getRegex("<link>(http://.*?)</link>").getMatch(0);
                if (finallink == null) {
                    logger.warning("hqmaturetube decrypter broken for link: " + parameter);
                    return null;
                }
                decryptedLinks.add(createDownloadlink(Encoding.htmlDecode(finallink)));
                return decryptedLinks;
            }
            externID = br.getRegex("flashvars=\"file=(http://stream\\.mywifesmom\\.com/flv/\\d+\\.flv)\\&").getMatch(0);
            if (externID != null) {
                DownloadLink dl = createDownloadlink("directhttp://" + Encoding.htmlDecode(externID));
                dl.setFinalFileName(filename + ".flv");
                decryptedLinks.add(dl);
                return decryptedLinks;
            }
            externID = br.getRegex("value=\"id_video=(\\d+)\"").getMatch(0);
            if (externID != null) {
                String finallink = "http://www.xvideos.com/video" + externID + "/";
                decryptedLinks.add(createDownloadlink(Encoding.htmlDecode(finallink)));
                return decryptedLinks;
            }
            externID = br.getRegex("flashvars=\"file=(http.*?)\\&et_url=http").getMatch(0);
            if (externID != null) {
                br.getPage(Encoding.htmlDecode(externID));
                String finallink = br.getRegex("<location>(http://.*?)</location>").getMatch(0);
                if (finallink == null) {
                    logger.warning("hqmaturetube decrypter broken for link: " + parameter);
                    return null;
                }
                DownloadLink dl = createDownloadlink("directhttp://" + Encoding.htmlDecode(finallink));
                String type = br.getRegex("<meta rel=\"type\">(.*?)</meta>").getMatch(0);
                if (type == null) {
                    type = "flv";
                }
                dl.setFinalFileName(filename + "." + type);
                decryptedLinks.add(dl);
                return decryptedLinks;
            }
            externID = br.getRegex("flashvars=\"file=(http://[a-z0-9\\-_]+\\.60plusmilfs\\.com/.*?)\\&image=http").getMatch(0);
            if (externID != null) {
                br.getPage(Encoding.htmlDecode(externID));
                String finallink = br.getRedirectLocation();
                if (finallink == null) {
                    logger.warning("hqmaturetube decrypter broken for link: " + parameter);
                    return null;
                }
                DownloadLink dl = createDownloadlink("directhttp://" + Encoding.htmlDecode(finallink));
                dl.setFinalFileName(filename + finallink.subSequence(finallink.length() - 4, finallink.length()));
                decryptedLinks.add(dl);
                return decryptedLinks;
            }
            logger.warning("hqmaturetube decrypter broken for link: " + parameter);
            return null;
        }
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}