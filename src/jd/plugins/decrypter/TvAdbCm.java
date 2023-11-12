//jDownloader - Downloadmanager
//Copyright (C) 2014  JD-Team support@jdownloader.org
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
import java.util.List;
import java.util.Map;

import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision: 46274 $", interfaceVersion = 2, names = { "tv.adobe.com" }, urls = { "https?://(www\\.|video\\.)?tv\\.adobe\\.com/((?:watch|embed)/[a-z0-9\\-]+/[a-z0-9\\-]+/?|v/[a-z0-9\\-]+)" })
public class TvAdbCm extends PluginForDecrypt {
    // dev notes
    // final links seem to not have any session info bound, nor restricted to IP and are hotlinkable, hoster plugin not required.
    /**
     * @author raztoki
     */
    public TvAdbCm(PluginWrapper wrapper) {
        super(wrapper);
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        String url_name = null;
        br.setFollowRedirects(true);
        if (param.getCryptedUrl().matches("(?i).*/watch/.+")) {
            url_name = new Regex(param.getCryptedUrl(), "adobe\\.com/watch/(.+)").getMatch(0).replace("/", "_");
            br.getPage(param.getCryptedUrl());
            if (br.getHttpConnection().getResponseCode() == 404 || !br.containsHTML("/player/player\\.swf")) {
                // No need to randomise URL when exporting offline links!
                // Please always use directhttp && property OFFLINE from decrypters! Using 'OFFLINE' property, ensures when the user checks
                // online status again it will _always_ return offline status.
                final DownloadLink offline = createDownloadlink("directhttp://" + param.getCryptedUrl());
                offline.setFinalFileName(new Regex(param.getCryptedUrl(), "([a-z0-9\\-]+)/?$").getMatch(0) + ".mp4");
                offline.setProperty("OFFLINE", true);
                offline.setAvailable(false);
                ret.add(offline);
                return ret;
            }
            final String embedurl = br.getRegex("tv\\.adobe\\.com/embed/([^<>\"]*?)\"").getMatch(0);
            if (embedurl == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            br.getPage("https://tv.adobe.com/embed/" + embedurl);
        } else {
            br.getPage(param.getCryptedUrl());
        }
        if (br.containsHTML(">\\s*Staged video playback forbidden")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.getHttpConnection().getResponseCode() == 404) {
            /* E.g. http://video.tv.adobe.com/v/0 */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String html5player = br.getRegex("var bridge = (\\{.*?\\});").getMatch(0);
        if (html5player == null) {
            if (!this.canHandle(br.getURL())) {
                /* Redirect to somewhere else --> Content offline */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        // parse for qualities
        Map<String, Object> entries = (Map<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(html5player);
        final List<Object> sources = (List) entries.get("sources");
        String name = (String) entries.get("title");
        if (name == null) {
            name = url_name;
        }
        for (final Object videoo : sources) {
            entries = (Map<String, Object>) videoo;
            final String q = Long.toString(JavaScriptEngineFactory.toLong(entries.get("bitrate"), -1));
            final String u = (String) entries.get("fsrc");
            if (q == null || u == null || !u.startsWith("http")) {
                continue;
            }
            final DownloadLink dl = createDownloadlink("directhttp://" + u);
            dl.setFinalFileName(name + " - " + q + u.substring(u.lastIndexOf(".")));
            ret.add(dl);
        }
        if (name != null) {
            FilePackage fp = FilePackage.getInstance();
            fp.setName(name);
            fp.addLinks(ret);
        }
        return ret;
    }
}