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
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.HTMLSearch;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperCrawlerPluginRecaptchaV2;
import org.jdownloader.plugins.components.antiDDoSForDecrypt;

@DecrypterPlugin(revision = "$Revision: 46930 $", interfaceVersion = 3, names = { "ffetish.photos", "ffetish.video" }, urls = { "https?://(?:[a-z0-9\\-]+\\.)?ffetish\\.photos/\\d+[a-z0-9\\-]+\\.html", "https?://(?:[a-z0-9\\-]+\\.)?ffetish\\.video/\\d+[a-z0-9\\-]+\\.html" })
public class FfetishPhotos extends antiDDoSForDecrypt {
    public FfetishPhotos(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public int getMaxConcurrentProcessingInstances() {
        /* 2020-05-18: Preventive measure to try to avoid captchas */
        return 1;
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        final String dl_id = new Regex(parameter, "(?:photos|video)/(\\d+)").getMatch(0);
        final String skin;
        if ("ffetish.photos".equals(getHost())) {
            skin = "ffphotos";
        } else {
            skin = "ffvideo";
        }
        br.setFollowRedirects(true);
        getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        FilePackage fp = null;
        String title = HTMLSearch.searchMetaTag(br, "og:title");
        if (title != null) {
            fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(title).trim());
        }
        String picturehtml = br.getRegex("class=\"lazy\"(.*?)class=\"ui-form\"").getMatch(0);
        if (picturehtml == null) {
            logger.warning("Using picturehtml fallback");
            picturehtml = br.getRequest().getHtmlCode();
        }
        final String[] pictureurls = new Regex(picturehtml, "data-src=\"(/uploads/posts/[^\"]+)\"").getColumn(0);
        for (final String pictureurl : pictureurls) {
            final DownloadLink image = this.createDownloadlink(br.getURL(pictureurl).toString());
            if (fp != null) {
                image._setFilePackage(fp);
            }
            ret.add(image);
            distribute(image);
        }
        if (br.containsHTML("engine/modules/antibot/antibot\\.php")) {
            boolean success = false;
            br.getHeaders().put("x-requested-with", "XMLHttpRequest");
            final String initialURL = br.getURL();
            for (int i = 0; i <= 3; i++) {
                final String code = this.getCaptchaCode("/engine/modules/antibot/antibot.php?rndval=" + System.currentTimeMillis(), param);
                /*
                 * We either need to use another browser instance or set this header otherwise if user enters a wrong captcha once, all
                 * following attempts will fail due to wrong Referer header.
                 */
                br.setCurrentURL(initialURL);
                postPage("/engine/ajax/getlink.php", "sec_code=" + Encoding.urlEncode(code) + "&id=" + dl_id + "&skin=" + skin);
                final Form form = br.getForm(0);
                if (form != null && form.containsHTML("g-recaptcha")) {
                    final String recaptchaV2Response = new CaptchaHelperCrawlerPluginRecaptchaV2(this, br).getToken();
                    form.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                    submitForm(form);
                }
                if (br.getForm(0) == null && br.toString().length() > 100) {
                    success = true;
                    break;
                } else {
                    logger.info("User entered invalid captcha");
                }
            }
            if (!success) {
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
        }
        String finallink = this.br.getRegex("(https?://(?:[a-z0-9\\-]+\\.)?ffetish\\.(?:photos|video)/video/[^\"\\']+)").getMatch(0);
        if (finallink == null) {
            logger.warning("Decrypter broken for link: " + parameter);
            return null;
        }
        br.setFollowRedirects(false);
        getPage(finallink);
        finallink = br.getRedirectLocation();
        if (finallink == null) {
            logger.warning("Decrypter broken for link: " + parameter);
            return null;
        }
        final DownloadLink result = createDownloadlink(finallink);
        if (fp != null) {
            result._setFilePackage(fp);
        }
        ret.add(result);
        return ret;
    }
}
