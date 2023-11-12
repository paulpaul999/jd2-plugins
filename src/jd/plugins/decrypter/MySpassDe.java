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

import java.text.DecimalFormat;
import java.util.ArrayList;

import org.appwork.utils.StringUtils;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.PluginJSonUtils;
import jd.utils.JDUtilities;

@DecrypterPlugin(revision = "$Revision: 46809 $", interfaceVersion = 3, names = { "myspass.de" }, urls = { "https?://(?:www\\.)?myspass\\.de/(?:(?:myspass/)?shows/(?:tv|web)shows/.+|channels/.+)" })
public class MySpassDe extends PluginForDecrypt {
    public MySpassDe(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String type_hoster = "^https?://(?:www\\.)?myspass\\.de/(?:(?:myspass/)?shows/(?:tv|web)shows/([a-z0-9\\-_]+/[^/]+/|.+video\\.php\\?id=)\\d+/?|channels/[^/]+/\\d+/\\d+/?)$";

    /** Handling for old website in revision: 39533 */
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        final boolean fastlinkcheck = JDUtilities.getPluginForHost(this.getHost()).getPluginConfig().getBooleanProperty("FAST_LINKCHECK", true);
        if (parameter.matches(type_hoster)) {
            /* Single item */
            final DownloadLink dl = createDownloadlink(parameter.replace("myspass.de", "myspassdecrypted.de/"));
            dl.setContentUrl(parameter);
            ret.add(dl);
            return ret;
        }
        br.setFollowRedirects(true);
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("404 - SEITE NICHT GEFUNDEN")) {
            ret.add(this.createOfflinelink(parameter));
            return ret;
        }
        String show = br.getRegex("itemprop='name'>([^<>\"]*?) - (im kostenlosen|Ganze Folgen|Der gratis) [^<>]+</title>").getMatch(0);
        if (show == null) {
            /* Fallback to url */
            show = new Regex(parameter, ".+/shows/[^/]+/([^/]+)/?").getMatch(0);
            show = show.replace("-", " ");
        }
        show = Encoding.htmlDecode(show).trim();
        String videoid = br.getRegex("data-videoid=\"(\\d+)\"\\s").getMatch(0);
        String seasonnumber = null;
        String seasonnumber_formatted = null;
        String fpName = null;
        short seasonnumber_parsed;
        boolean yearInsteadOfSeasonNumber = false;
        boolean needs_series_filename;
        final DecimalFormat df = new DecimalFormat("00");
        final FilePackage fp = FilePackage.getInstance();
        if (br.containsHTML("id=\"channel_playlist\"")) {
            /* Channel playlist */
            fp.setName(show);
            final String[] playlist_htmls = br.getRegex("<li class=\"[a-z0-9_]+_video_li\".*?</li>").getColumn(-1);
            for (final String html_single_video : playlist_htmls) {
                final String singleLink = new Regex(html_single_video, "<a href=\"(/channels/[^\"]+\\d+/)\"").getMatch(0);
                final String episodenumber = new Regex(html_single_video, "Folge\\s*?(\\d+)").getMatch(0);
                final String fid = new Regex(singleLink, "(\\d+)/$").getMatch(0);
                if (singleLink == null || fid == null) {
                    /* Skip invalid items */
                    continue;
                }
                final String url_content = "http://myspass.de" + singleLink;
                final DownloadLink dl = createDownloadlink("http://myspassdecrypted.de" + singleLink);
                String episodeTitle = new Regex(html_single_video, "title=\"([^\"]+)\"").getMatch(0);
                // dl.setProperty("needs_series_filename", needs_series_filename);
                dl.setContentUrl(url_content);
                dl.setLinkID(this.getHost() + "://" + fid);
                String filename_temp;
                if (episodenumber != null && seasonnumber != null && seasonnumber_formatted != null) {
                    if (yearInsteadOfSeasonNumber) {
                        filename_temp = fid + "_" + show;
                        if (episodeTitle != null) {
                            filename_temp += " - " + episodeTitle;
                        }
                        filename_temp += "_" + seasonnumber + "E" + episodenumber;
                    } else {
                        filename_temp = fid + "_" + show;
                        if (episodeTitle != null) {
                            filename_temp += " - " + episodeTitle;
                        }
                        filename_temp += "_S" + seasonnumber_formatted + "E" + episodenumber;
                    }
                } else if (episodeTitle != null) {
                    filename_temp = fid + "_" + episodeTitle;
                } else {
                    /* Fallback */
                    filename_temp = fid;
                }
                filename_temp += ".mp4";
                dl.setName(filename_temp);
                dl.setMimeHint(CompiledFiletypeFilter.VideoExtensions.MP4);
                if (fastlinkcheck) {
                    dl.setAvailable(true);
                }
                dl._setFilePackage(fp);
                distribute(dl);
                ret.add(dl);
            }
        } else {
            /* Series */
            // class="float-left seasonTab baxx-tabbes-tab full_episode_seasonTab"
            String[] html_list_season = this.br.getRegex("<option data\\-remote\\-args=\"\\&seasonId[^\"]+\\&category=full_episode.*?\\</option>").getColumn(-1);
            if (html_list_season == null || html_list_season.length == 0 && videoid != null) {
                if (!parameter.contains(videoid) || parameter.matches("https?://[^/]+/channels/[^/]+/\\d+/?$")) {
                    /* No downloadable content (e.g. overview of channel (Displays trailer in browser)) */
                    ret.add(this.createOfflinelink(parameter));
                    return ret;
                }
                /* Single video */
                final DownloadLink dl = createDownloadlink(parameter.replace("myspass.de", "myspassdecrypted.de/") + videoid + "/");
                dl.setContentUrl(parameter);
                ret.add(dl);
                return ret;
            }
            if (html_list_season == null || html_list_season.length == 0) {
                throw new DecrypterException("Decrypter broken for link: " + parameter);
            }
            for (final String html_season : html_list_season) {
                /* Reset variables which we re-use. */
                yearInsteadOfSeasonNumber = false;
                needs_series_filename = true;
                /*
                 * Normally we could use seasoncounter here but site is buggy - sometimes they either don't have all seasons or they just
                 * start with- or use random numbers e.g. here the "first season" from 2011 has the season number 9:
                 * http://www.myspass.de/myspass/shows/tvshows/tv-total-wok-wm/
                 *
                 * --> Let's use their buggy numbers but set correct filenames below :)
                 */
                /* Seasonnumbers for http requests --> They have nothing todo with the real year- or seasonnumber! */
                String seasonnumber_intern = new Regex(html_season, "seasonId=(\\d+)").getMatch(0);
                if (seasonnumber_intern == null) {
                    /* 2018-12-29: For single channel playlists (no seasons & episodes) */
                    seasonnumber_intern = new Regex(html_season, "seasonId\\s*?:\\s*?(\\d+)").getMatch(0);
                }
                String format_intern = new Regex(html_season, "formatId=(\\d+)").getMatch(0);
                if (format_intern == null) {
                    /* 2018-12-29: For single channel playlists (no seasons & episodes) */
                    format_intern = new Regex(html_season, "formatId\\s*?:\\s*?(\\d+)").getMatch(0);
                }
                /* Real seasonnumber or year instead. */
                seasonnumber = new Regex(html_season, "data\\-target=\"#episodes_season_\\d+_category_full_episode\">([^<>\"]+)</li>").getMatch(0);
                if (seasonnumber == null) {
                    seasonnumber = new Regex(html_season, ">\\s*?(\\d+)[^<>]*?</option>").getMatch(0);
                }
                if (seasonnumber == null) {
                    /* Just a playlist - no season given. */
                    seasonnumber = "0";
                }
                if (format_intern == null || seasonnumber_intern == null || seasonnumber == null) {
                    /* E.g. Skip "Highlights"-tab and other invalid items. */
                    continue;
                }
                seasonnumber = seasonnumber.trim();
                if (seasonnumber.matches("Staffel\\s*?\\d+")) {
                    seasonnumber_parsed = Short.parseShort(new Regex(seasonnumber, "(\\d+)$").getMatch(0));
                } else {
                    /* We don't have a season-number but a YEAR instead. */
                    seasonnumber_parsed = Short.parseShort(new Regex(seasonnumber, "(\\d+)").getMatch(0));
                    yearInsteadOfSeasonNumber = true;
                    needs_series_filename = false;
                }
                seasonnumber_formatted = df.format(seasonnumber_parsed);
                /* Go through all years/seasons */
                this.br.getPage("//www." + this.getHost() + "/frontend/php/ajax.php?query=bob&formatId=" + format_intern + "&seasonId=" + seasonnumber_intern + "&category=full_episode&sortBy=episode_desc");
                if (yearInsteadOfSeasonNumber) {
                    fpName = show + " " + seasonnumber;
                } else {
                    fpName = show + " S" + seasonnumber_formatted;
                }
                fp.setName(fpName);
                /* Get all video items of this season/year */
                // final String[] html_episode_list = this.br.getRegex("<li class=\"played_video_li\".*?</li>").getColumn(-1);
                br.getRequest().setHtmlCode(PluginJSonUtils.unescape(br.toString()));
                final String[] html_episode_list = this.br.getRegex("<tr.*?</tr>").getColumn(-1);
                if (html_episode_list == null || html_episode_list.length == 0) {
                    logger.warning("Decrypter broken for link: " + parameter);
                    return null;
                }
                for (final String html_episode : html_episode_list) {
                    // final String[] columns = new Regex(html_episode, "<td>(.*?)</td>").getColumn(0);
                    String item_title = new Regex(html_episode, "<a href=\"[^\"]+\">([^<]+)</a>").getMatch(0);
                    final String singleLink = new Regex(html_episode, "<a href=\"/shows/([^\"]+\\d+/)\"").getMatch(0);
                    final String episodenumber = new Regex(html_episode, "Folge\\s*?(\\d+)").getMatch(0);
                    if (singleLink == null) {
                        /* Skip invalid items */
                        continue;
                    }
                    if (!StringUtils.isEmpty(item_title)) {
                        /* Do a small correction which is sometimes needed */
                        final String betterItemTitle = new Regex(item_title, "1 - (.+)").getMatch(0);
                        if (betterItemTitle != null) {
                            item_title = betterItemTitle;
                        }
                    }
                    final String url_content = "http://myspass.de/shows/" + singleLink;
                    final String fid = new Regex(singleLink, "(\\d+)/$").getMatch(0);
                    final DownloadLink dl = createDownloadlink("http://myspassdecrypted.de/shows/" + singleLink);
                    dl.setProperty("needs_series_filename", needs_series_filename);
                    dl.setContentUrl(url_content);
                    dl.setLinkID(this.getHost() + "://" + fid);
                    final String filename_temp;
                    if (episodenumber != null) {
                        if (yearInsteadOfSeasonNumber) {
                            filename_temp = fid + "_" + show + "_" + seasonnumber + "E" + episodenumber;
                        } else {
                            filename_temp = fid + "_" + show + "_S" + seasonnumber_formatted + "E" + episodenumber;
                        }
                    } else if (!StringUtils.isEmpty(item_title)) {
                        filename_temp = item_title;
                    } else {
                        /* Fallback/last-chance: Ugliest temporary filename */
                        filename_temp = singleLink;
                    }
                    /* Final filename will be set by host plugin later */
                    dl.setName(filename_temp + ".mp4");
                    if (fastlinkcheck) {
                        dl.setAvailable(true);
                    }
                    dl._setFilePackage(fp);
                    distribute(dl);
                    ret.add(dl);
                }
                if (this.isAbort()) {
                    logger.info("Decryption aborted by user");
                    return ret;
                }
            }
        }
        return ret;
    }
}
