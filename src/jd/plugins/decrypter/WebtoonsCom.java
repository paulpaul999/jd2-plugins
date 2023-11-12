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

import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;

import org.appwork.utils.parser.UrlQuery;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.DirectHTTP;

@DecrypterPlugin(revision = "$Revision: 48432 $", interfaceVersion = 3, names = { "webtoons.com" }, urls = { "https?://(?:www\\.)?webtoons\\.com/[a-z\\-]+/[^/]+/[^/]+/(?:[^/]+/viewer\\?title_no=\\d+\\&episode_no=\\d+|list\\?title_no=\\d+)" })
public class WebtoonsCom extends PluginForDecrypt {
    public WebtoonsCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Browser createNewBrowserInstance() {
        final Browser br = super.createNewBrowserInstance();
        setImportantCookies(br, this.getHost());
        br.setFollowRedirects(true);
        br.setAllowedResponseCodes(400, 429);
        return br;
    }

    private void setImportantCookies(final Browser br, final String host) {
        /* These cookies will allow us to access 18+ content. */
        br.setCookie(host, "pagGDPR", "true");
        br.setCookie(host, "atGDPR", "AD_CONSENT");
        br.setCookie(host, "needGDPR", "false");
    }

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        br.getPage(param.getCryptedUrl());
        checkErrors(br);
        final UrlQuery query = UrlQuery.parse(param.getCryptedUrl());
        final String titlenumber = query.get("title_no");
        final String episodenumber = query.get("episode_no");
        String fpName = br.getRegex("\"og:title\"\\s*content=\"(.*?)\"").getMatch(0);
        if (fpName == null) {
            fpName = br.getRegex("<title>([^<>]+)</title>").getMatch(0);
        }
        final FilePackage fp = FilePackage.getInstance();
        if (fpName != null) {
            fp.setName(Encoding.htmlDecode(fpName).trim());
        }
        String[] links;
        if (episodenumber != null) {
            /* Crawl single episode */
            links = br.getRegex("class=\"_images\" data\\-url=\"(https?://[^<>\"]*?)\"").getColumn(0);
            if (links == null || links.length == 0) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            final DecimalFormat df = new DecimalFormat("0000");
            int counter = 0;
            for (final String singleLink : links) {
                counter++;
                final DownloadLink dl = createDownloadlink(DirectHTTP.createURLForThisPlugin(singleLink));
                String name = getFileNameFromURL(new URL(singleLink));
                if (name == null) {
                    name = ".jpg";
                }
                name = df.format(counter) + "_" + name;
                dl.setAvailable(true);
                dl.setFinalFileName(name);
                if (fp != null) {
                    fp.add(dl);
                }
                ret.add(dl);
            }
        } else {
            /* Crawl all episodes of a series */
            int page = 1;
            final HashSet<String> dupes = new HashSet<String>();
            do {
                logger.info("Crawling page: " + page);
                /* Find urls of all episode of a title --> Re-Add these single episodes to the crawler. */
                links = br.getRegex("<li[^>]*id=\"episode_\\d+\"[^>]+>[^<>]*?<a href=\"(https?://[^<>\"]+title_no=" + titlenumber + "\\&episode_no=\\d+)\"").getColumn(0);
                if (links.length == 0) {
                    /* Maybe we already found everything or there simply isn't anything. */
                    if (ret.isEmpty()) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    } else {
                        /* This should be an extremely rare case */
                        logger.info("Stopping because: Failed to find any item on current page");
                        break;
                    }
                }
                logger.info("Crawled page " + page + " | Found items so far: " + ret.size());
                int numberofNewItems = 0;
                for (final String singleLink : links) {
                    if (dupes.add(singleLink)) {
                        numberofNewItems++;
                        final DownloadLink link = this.createDownloadlink(singleLink);
                        if (fp != null) {
                            fp.add(link);
                        }
                        distribute(link);
                        ret.add(link);
                    }
                }
                if (numberofNewItems == 0) {
                    logger.info("Stopping because: Failed to find any new item on current page: " + page);
                    break;
                } else if (!br.containsHTML("page=" + (page + 1))) {
                    logger.info("Stopping because: No next page available -> Reached end? Last page = " + page);
                    break;
                } else if (this.isAbort()) {
                    logger.info("Stopping because: Aborted by user");
                    break;
                } else {
                    /* Continue to next page */
                    page++;
                    final String nextPage = param.getCryptedUrl() + "&page=" + page;
                    this.br.getPage(nextPage);
                    /* 2021-08-02: This sometimes randomly happens... */
                    if (br.getURL().contains("/gdpr/ageGate")) {
                        setImportantCookies(br, this.br.getHost());
                        this.br.getPage(nextPage);
                    }
                    checkErrors(br);
                    // continue;
                }
            } while (!this.isAbort());
            if (ret.isEmpty()) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        return ret;
    }

    private void checkErrors(final Browser br) throws PluginException, DecrypterRetryException {
        if (br.getHttpConnection().getResponseCode() == 400 || br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.getHttpConnection().getResponseCode() == 429) {
            throw new DecrypterRetryException(RetryReason.HOST_RATE_LIMIT);
        }
    }

    @Override
    public int getMaxConcurrentProcessingInstances() {
        /* 2023-11-07: Try to avoid running into rate limits. */
        return 1;
    }
}
