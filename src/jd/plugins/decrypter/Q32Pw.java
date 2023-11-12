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

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.appwork.utils.IO;
import org.appwork.utils.StringUtils;
import org.appwork.utils.images.IconIO;
import org.jdownloader.captcha.v2.challenge.clickcaptcha.ClickedPoint;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.PluginJSonUtils;

@DecrypterPlugin(revision = "$Revision: 48002 $", interfaceVersion = 3, names = {}, urls = {})
public class Q32Pw extends PluginForDecrypt {
    public Q32Pw(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "q32.pw", "q32.link", "q32.ru" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(\\d+/c/[A-Za-z0-9\\-_]+|[A-Za-z0-9]+)");
        }
        return ret.toArray(new String[0]);
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        br.setFollowRedirects(true);
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String directRedirect = br.getRegex("class=\"link\" href=\"javascript://\" href=\"#\"><h3>([^>]+)</h3>").getMatch(0);
        if (directRedirect != null) {
            if (directRedirect.startsWith("http")) {
                directRedirect = "https://" + directRedirect;
            }
            decryptedLinks.add(createDownloadlink(directRedirect));
        } else {
            final String domain_key = br.getRegex("domain_key\\s*:\\s*\"([a-f0-9]+)\"").getMatch(0);
            final String linkid = br.getRegex("linkid\\s*:\\s*\"([^\"]+)\"").getMatch(0);
            final String lang = br.getRegex("lang\\s*:\\s*\"([a-z]+)\"").getMatch(0);
            final String waitStr = br.getRegex("id=\"countdown\">(\\d+)<").getMatch(0);
            if (domain_key == null || linkid == null || lang == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            /*
             * 2020-01-23: Waittime is not skippable but it starts to count here already --> We can let the user solve the captcha while the
             * timer keeps running :)
             */
            final long timeBefore = System.currentTimeMillis();
            br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            br.postPage("/ajax.php?action=get.ca", String.format("domain_key=%s&linkid=%s&lang=%s", domain_key, linkid, lang));
            boolean captchaSuccess = false;
            int counter = 0;
            do {
                final String json = PluginJSonUtils.unescape(br.toString());
                String captchaQuestion = PluginJSonUtils.getJson(json, "question");
                if (StringUtils.isEmpty(captchaQuestion)) {
                    logger.warning("Failed to find captcha question");
                    return null;
                }
                /* Some cleanup. E.g. "Click the picture for <strong>boxing gloves</strong>" --> "Click the picture for boxing gloves" */
                captchaQuestion = captchaQuestion.replaceAll("</?strong>", "");
                final int expected_numberof_images = 5;
                final String[] answerKeys = new Regex(json, "name=\"ca\" value=\"([a-f0-9]+)\"").getColumn(0);
                final String[] captchas_b64 = new Regex(json, "base64,([a-zA-Z0-9_/\\+\\=]+)").getColumn(0);
                if (captchas_b64 == null || captchas_b64.length != expected_numberof_images || answerKeys == null || answerKeys.length != expected_numberof_images) {
                    logger.warning("Failed to find captcha images or length mismatch");
                    return null;
                }
                final BufferedImage[] images = new BufferedImage[captchas_b64.length];
                final File stitchedImageOutput = getStitchedImage(captchas_b64, images);
                final ClickedPoint c = getCaptchaClickedPoint(getHost(), stitchedImageOutput, param, captchaQuestion);
                if (c == null) {
                    throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                }
                final Integer clicked_image_number = getClickedImageLocation(c, images);
                final String answer_key = answerKeys[clicked_image_number];
                logger.info("Got the captcha answer --> Waiting what's left of the waittime");
                if (counter == 0) {
                    /* Only wait before sending the first captcha answer. We can try again without waiting then. */
                    waitTime(param, waitStr, timeBefore);
                }
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.postPage("/ajax.php?action=get.cca", String.format("domain_key=%s&linkid=%s&lang=%s&ca=%s", domain_key, linkid, lang, answer_key));
                captchaSuccess = "success".equalsIgnoreCase(PluginJSonUtils.getJson(br, "status"));
                counter++;
            } while (!captchaSuccess && counter <= 4);
            if (!captchaSuccess) {
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            /* 'link' is another q32.pw URL which redirects to 'slink' --> We take 'slink' right away. */
            // final String finallink = PluginJSonUtils.getJson(br, "link");
            final String finallink = PluginJSonUtils.getJson(br, "slink");
            if (StringUtils.isEmpty(finallink)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            decryptedLinks.add(createDownloadlink(finallink));
        }
        return decryptedLinks;
    }

    protected void waitTime(final CryptedLink param, final String waitStr, final long timeBefore) throws PluginException, InterruptedException {
        final int extraWaitSeconds = 1;
        int wait = 30;
        if (waitStr != null) {
            logger.info("Found waittime, parsing waittime: " + waitStr);
            wait = Integer.parseInt(waitStr);
        }
        int passedTime = (int) ((System.currentTimeMillis() - timeBefore) / 1000) - extraWaitSeconds;
        /*
         * Check how much time has passed during captcha event before this function has been called and see how much time is left to wait.
         */
        wait -= passedTime;
        if (passedTime > 0) {
            /* This usually means that the user had to solve a captcha which cuts down the remaining time we have to wait. */
            logger.info("Total passed time during captcha: " + passedTime);
        }
        if (wait > 0) {
            logger.info("Waiting final waittime seconds: " + wait);
            sleep(wait * 1000l, param);
        } else if (wait < -extraWaitSeconds) {
            /* User needed more time to solve the captcha so there is no waittime left :) */
            logger.info("Congratulations: Time to solve captcha was higher than waittime --> No waittime left");
        } else {
            /* No waittime at all */
            logger.info("Found no waittime");
        }
    }

    public int getMaxConcurrentProcessingInstances() {
        /* 2020-01-23: Simultaneous solving is not possible */
        return 1;
    }

    private Integer getClickedImageLocation(final ClickedPoint c, final BufferedImage[] images) {
        // determine cords
        final int x = c.getX();
        int i = 0;
        int min = 0;
        int max = 0;
        for (final BufferedImage bi : images) {
            // image width
            max = min + widths[i];
            if (x >= min && x <= max) {
                // return count;
                return i;
            }
            min = max;
            i++;
        }
        return null;
    }

    private Integer[] widths = null;

    private File getStitchedImage(final String[] captchaImages, final BufferedImage[] images) throws Exception {
        int i = -1;
        for (final String ci : captchaImages) {
            i++;
            if (ci.matches("[a-zA-Z0-9_/\\+\\=]+")) {
                /* base64 encoded image */
                byte[] image_bytes = org.appwork.utils.encoding.Base64.decode(ci);
                if (image_bytes == null || image_bytes.length == 0) {
                    image_bytes = org.appwork.utils.encoding.Base64.decodeFast(ci);
                }
                final ByteArrayInputStream bis = new ByteArrayInputStream(image_bytes);
                images[i] = ImageIO.read(bis);
                bis.close();
            } else {
                /* Assume we have a valid URL --> Exception will be thrown if we have not! */
                URLConnectionAdapter con = null;
                try {
                    final Browser img = br.cloneBrowser();
                    img.getHeaders().put("Accept", "image/webp,*/*;q=0.8");
                    con = img.openGetConnection(ci);
                    images[i] = ImageIO.read(con.getInputStream());
                } finally {
                    try {
                        if (con != null) {
                            con.disconnect();
                        }
                    } catch (final Throwable e) {
                    }
                }
            }
            if (isAbort()) {
                throw new InterruptedException();
            }
        }
        // display each image within single row, merged image, show in dialog with phrase
        // track only the width to determine which image has been clicked.
        widths = new Integer[images.length];
        // analyse the images
        int h = 0;
        int w = 0;
        i = 0;
        for (final BufferedImage bi : images) {
            if (h < bi.getHeight()) {
                h = bi.getHeight();
            }
            // with width we want total
            w += bi.getWidth();
            widths[i++] = bi.getWidth();
        }
        final BufferedImage stichedImageBuffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics graphic = stichedImageBuffer.getGraphics();
        w = 0;
        for (final BufferedImage bi : images) {
            graphic.drawImage(bi, w, 0, null);
            w += bi.getWidth();
        }
        final byte[] image = IconIO.toJpgBytes(stichedImageBuffer);
        if (image == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final File stitchedImageOutput = getLocalCaptchaFile(".png");
        // image contains transparency which results in incompatible jpg file when ImageIO is being used
        // IconIO.toJpgBytes removes transparency first
        IO.writeToFile(stitchedImageOutput, image);
        return stitchedImageOutput;
    }
}
