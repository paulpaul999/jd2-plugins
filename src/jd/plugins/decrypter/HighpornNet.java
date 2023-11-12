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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.http.requests.PostRequest;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;

@DecrypterPlugin(revision = "$Revision: 45822 $", interfaceVersion = 3, names = { "highporn.net", "tanix.net", "japanhub.net", "thatav.net" }, urls = { "https?://(?:www\\.)?highporn\\.net/video/(\\d+)(?:/[a-z0-9\\-]+)?", "https?://(?:www\\.)?tanix\\.net/video/(\\d+)(?:/[a-z0-9\\-]+)?", "https?://(?:www\\.)?japanhub\\.net/video/(\\d+)(?:/[a-z0-9\\-]+)?", "https?://(?:www\\.)?thatav\\.net/video/(\\d+)(?:/[a-z0-9\\-]+)?" })
public class HighpornNet extends PluginForDecrypt {
    public HighpornNet(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString().replace("www.", "");
        final String videoid = new Regex(parameter, this.getSupportedLinks()).getMatch(0);
        final String initialHost = Browser.getHost(parameter);
        br.setFollowRedirects(true);
        getPage(parameter);
        if (!br.getURL().contains(initialHost)) {
            logger.info("Redirect to external website");
            decryptedLinks.add(this.createOfflinelink(br.getURL()));
            return decryptedLinks;
        } else if (isOffline(br)) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        } else if (!br.getURL().contains(videoid)) {
            /* Offline --> Redirect to (external) ads page / search-page */
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        final String fpName = getTitle(this.br, parameter);
        /* 2021-02-10: thatav.net */
        final String specialVideoURL = br.getRegex("\"file\":\\s*\"(https?://[^<>\"]+\\.mp4[^<>\"]+)").getMatch(0);
        if (specialVideoURL != null) {
            final DownloadLink dl = this.createDownloadlink("directhttp://" + specialVideoURL);
            dl.setAvailable(true);
            if (fpName != null) {
                dl.setFinalFileName(fpName + ".mp4");
            }
            decryptedLinks.add(dl);
            return decryptedLinks;
        }
        boolean singleVideo = false;
        final String videoLink = br.getRegex("data-src\\s*=\\s*\"(https?[^<>\"]+)\"").getMatch(0); // If single link, no videoID
        String[] videoIDs = br.getRegex("data-src\\s*=\\s*\"([^\"]+)\"").getColumn(0);
        if (videoIDs == null || videoIDs.length == 0) {
            if (videoLink == null) {
                logger.warning("Decrypter broken for link: " + parameter);
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            } else {
                videoIDs = new String[1];
                videoIDs[0] = (Long.toString(System.currentTimeMillis())); // dummy videoID
                singleVideo = true;
            }
        }
        final int padLength = StringUtils.getPadLength(videoIDs.length);
        int counter = 0;
        for (final String videoID : videoIDs) {
            if (isAbort()) {
                continue;
            }
            counter++;
            final String orderid_formatted = String.format(Locale.US, "%0" + padLength + "d", counter);
            final String filename = fpName + "_" + orderid_formatted + ".mp4";
            final DownloadLink dl = createDownloadlink("highporndecrypted://" + videoID);
            dl.setName(filename);
            dl.setProperty("decryptername", filename);
            dl.setProperty("mainlink", parameter);
            dl.setContentUrl(parameter);
            if (singleVideo) {
                dl.setProperty("singlevideo", true);
            } else {
                final PostRequest postRequest = new PostRequest("https://play.openhub.tv/playurl?random=" + (new Date().getTime() / 1000));
                postRequest.setContentType("application/x-www-form-urlencoded");
                postRequest.addVariable("v", videoID);
                postRequest.addVariable("source_play", "highporn");
                final Browser brc = br.cloneBrowser();
                final String file = brc.getPage(postRequest);
                try {
                    final URLConnectionAdapter con = br.cloneBrowser().openHeadConnection(file);
                    // referer check
                    try {
                        if (con.getResponseCode() == 200 && con.getLongContentLength() > 0 && !StringUtils.contains(con.getContentType(), "text")) {
                            dl.setVerifiedFileSize(con.getCompleteContentLength());
                        }
                    } finally {
                        con.disconnect();
                    }
                } catch (final IOException e) {
                    logger.log(e);
                }
                dl.setAvailable(true);
            }
            decryptedLinks.add(dl);
        }
        if (fpName != null) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(fpName.trim()));
            fp.addLinks(decryptedLinks);
        }
        return decryptedLinks;
    }

    private void getPage(final String parameter) throws Exception {
        final PluginForHost plugin = JDUtilities.getPluginForHost("highporn.net");
        ((jd.plugins.hoster.HighpornNet) plugin).setBrowser(br);
        ((jd.plugins.hoster.HighpornNet) plugin).getPage(parameter);
    }

    public static boolean isOffline(final Browser br) {
        return br.getHttpConnection().getResponseCode() == 404 || br.getURL().contains("/error/video_missing");
    }

    public static String getTitle(final Browser br, final String url) {
        String title = br.getRegex("property\\s*=\\s*\"og:title\" content=\"([^<>\"]+)\"").getMatch(0);
        if (title == null) {
            title = new Regex(url, "video/(.+)").getMatch(0);
        }
        return title;
    }
}
