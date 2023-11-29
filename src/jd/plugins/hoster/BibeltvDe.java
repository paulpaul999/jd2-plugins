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
package jd.plugins.hoster;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.jdownloader.downloader.hls.HLSDownloader;
import org.jdownloader.plugins.components.hls.HlsContainer;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.HTMLSearch;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.SiteType.SiteTemplate;

@HostPlugin(revision = "$Revision: 48528 $", interfaceVersion = 3, names = { "bibeltv.de" }, urls = { "https?://(?:www\\.)?bibeltv\\.de/mediathek/(videos/crn/\\d+|videos/([a-z0-9\\-]+-\\d+|\\d+-[a-z0-9\\-]+))" })
public class BibeltvDe extends PluginForHost {
    public BibeltvDe(PluginWrapper wrapper) {
        super(wrapper);
    }

    /* DEV NOTES */
    // Tags: kaltura player, medianac, api.medianac.com */
    // protocol: no https
    // other:
    /* Connection stuff */
    private static final boolean free_resume       = true;
    private static final int     free_maxchunks    = 0;
    private static final int     free_maxdownloads = -1;
    private String               mp4URL            = null;
    private String               hlsURL            = null;

    @Override
    public String getAGBLink() {
        return "https://www.bibeltv.de/impressum/";
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String linkid = getFID(link);
        if (linkid != null) {
            return this.getHost() + "://" + linkid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return getFID(link.getPluginPatternMatcher());
    }

    private String getFID(final String url) {
        if (url == null) {
            return null;
        }
        final String fid;
        if (url.matches(TYPE_REDIRECT)) {
            fid = new Regex(url, TYPE_REDIRECT).getMatch(0);
        } else if (url.matches(TYPE_FID_AT_BEGINNING)) {
            fid = new Regex(url, TYPE_FID_AT_BEGINNING).getMatch(0);
        } else {
            /* TYPE_FID_AT_END */
            fid = new Regex(url, TYPE_FID_AT_END).getMatch(0);
        }
        return fid;
    }

    private String getTitleFromURL(final String url) {
        if (url == null) {
            return null;
        }
        return new Regex(url, TYPE_ALL).getMatch(0);
    }

    private static final String              TYPE_ALL              = "https?://[^/]+/mediathek/videos(.+)";
    private static final String              TYPE_REDIRECT         = "https?://[^/]+/mediathek/videos/crn/(\\d+)";
    private static final String              TYPE_FID_AT_BEGINNING = "https?://[^/]+/mediathek/videos/(\\d{3,}).*";
    private static final String              TYPE_FID_AT_END       = "https?://[^/]+/mediathek/videos/[a-z0-9\\-]+-(\\d{3,})$";
    private Map<String, Object>              entries               = null;
    protected static AtomicReference<String> apiKey                = new AtomicReference<String>();

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, false);
    }

    public AvailableStatus requestFileInformation(final DownloadLink link, final boolean isDownload) throws Exception {
        /* This website contains video content ONLY! */
        final String extDefault = ".mp4";
        if (!link.isNameSet()) {
            link.setName(getTitleFromURL(link.getPluginPatternMatcher()) + extDefault);
        }
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.setAllowedResponseCodes(new int[] { 500 });
        final boolean useCRNURL;
        if (link.getPluginPatternMatcher().matches(TYPE_REDIRECT)) {
            /* ID inside URL will work fine for "crn" API request. */
            useCRNURL = true;
        } else if (link.getPluginPatternMatcher().matches(TYPE_FID_AT_BEGINNING)) {
            useCRNURL = true;
        } else {
            // TYPE_FID_AT_END
            useCRNURL = false;
            /*
             * 2020-09-18: We need to access the original URL once because the IDs in it may change. We need the ID inside the final URL to
             * use it as a video-ID for API access!
             */
            // br.getPage(link.getPluginPatternMatcher());
            // if (!new Regex(br.getURL(), this.getSupportedLinks()).matches()) {
            // logger.info("Redirect to unsupported URL --> Content is probably not downloadable");
            // throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            // }
            // fid = this.getFID(br.getURL());
            // if (new Regex(link.getPluginPatternMatcher(), TYPE_REDIRECT).matches()) {
            // /* Special handling for URLs which contain IDs that cannot be used via API! */
            // br.getPage(link.getPluginPatternMatcher());
            // if (br.getURL().matches(TYPE_REDIRECT) || !new Regex(br.getURL(), this.getSupportedLinks()).matches()) {
            // logger.info("Redirect to unsupported URL --> Content is probably not downloadable");
            // throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            // } else {
            // /* Set new URL which contains fileID which can be used via API. */
            // link.setPluginPatternMatcher(br.getURL());
            // }
            // }
        }
        final String fid = this.getFID(link);
        if (fid == null) {
            /* This should never happen */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        final String json;
        // new, 01.10.2021
        br.getPage(link.getPluginPatternMatcher());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* 2023-11-14: Very very lazy regexes */
        String internalVideoID = br.getRegex("video.\":..\"id.\":(\\d+)").getMatch(0);
        if (internalVideoID == null) {
            internalVideoID = br.getRegex("\"id.\":(\\d+),.\"active.\":true,.\"crn.\":.\"\\d+.\"").getMatch(0);
        }
        if (internalVideoID == null) {
            /* Either plugin is broken or this is not a single video page. */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* 2023-11-14: TODO: Re-add json handling. I was just too lazy to regex it out of their html. */
        json = br.getRegex("<script id=\"__NEXT_DATA__\"\\s*type\\s*=\\s*\"application/json\"\\s*>\\s*(.*?)\\s*</script>").getMatch(0);
        String description = null;
        if (json != null) {
            if (br.getHttpConnection().getResponseCode() == 404 || br.getHttpConnection().getResponseCode() == 500) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            try {
                entries = restoreFromString(json, TypeRef.MAP);
            } catch (final Throwable e) {
                /* 2019-12-17: No parsable json --> Offline */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND, null, e);
            }
            entries = (Map<String, Object>) JavaScriptEngineFactory.walkJson(entries, "props/pageProps/videoPageData/videos/{0}");
            if (entries == null) {
                /* Probably no video item available / video offline --> Website redirects to 404 page. */
                if (br.containsHTML("\"isFallback\":\\s*true")) {
                    /* 2023-03-30: This sometimes happens randomly. */
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Website under maintenance");
                } else {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            }
            internalVideoID = entries.get("id").toString();
            String filename = (String) entries.get("name");
            if (filename == null) {
                filename = (String) entries.get("title");
            }
            if (StringUtils.isEmpty(filename)) {
                /* Fallback */
                filename = getFID(link);
            }
            link.setFinalFileName(filename + extDefault);
            description = (String) entries.get("descriptionLong");
        } else {
            /* Obtain all info from HTML */
            String title = HTMLSearch.searchMetaTag(br, "og:title");
            if (title != null) {
                title = Encoding.htmlDecode(title).trim();
                title = title.replaceFirst("(?i)\\s* \\| Bibel TV$", "");
                link.setFinalFileName(title + extDefault);
            } else {
                /* Final fallback */
                link.setName(br._getURL().getPath() + extDefault);
            }
            description = HTMLSearch.searchMetaTag(br, "og:description");
        }
        if (!StringUtils.isEmpty(description) && link.getComment() == null) {
            link.setComment(Encoding.htmlOnlyDecode(description).trim());
        }
        final Browser br2 = br.cloneBrowser();
        String key = null;
        synchronized (apiKey) {
            if (apiKey.get() == null) {
                /* RegEx updated last time: Key last time: j88bRXY8DsEqJ9xmTdWhrByVi5Hm */
                /* 2023-03-30: It is hard to find the correct js URL right away so I've changed this to go through all JS URLs... */
                logger.info("Looking for authentication key...");
                final HashSet<String> alljsurls = new HashSet<String>();
                final String[] jsurls1 = br.getRegex("\"(/mediathek/[^\"]+\\.js)\"").getColumn(0);
                if (jsurls1 != null && jsurls1.length > 0) {
                    for (String jsurl : jsurls1) {
                        jsurl = br.getURL(jsurl).toExternalForm();
                        alljsurls.add(jsurl);
                    }
                }
                final String[] jsurls2 = br.getRegex("\"\\d+:([^\"]+\\.js).?\"").getColumn(0);
                if (jsurls2 != null && jsurls2.length > 0) {
                    for (String jsurl : jsurls2) {
                        jsurl = "https://www.bibeltv.de/mediathek/_next/" + jsurl;
                        alljsurls.add(jsurl);
                    }
                }
                final Browser brc = br.cloneBrowser();
                int progress = 0;
                String apiKeyDetected = null;
                for (final String jsURL : alljsurls) {
                    progress++;
                    logger.info("Working on jsURL " + progress + "/" + alljsurls.size() + " | " + jsURL);
                    if (this.isAbort()) {
                        logger.info("Aborted by user");
                        throw new InterruptedException();
                    }
                    brc.getPage(jsURL);
                    final String apiKeyTemp = brc.getRegex("Authorization\\s*:\"([^\"]+)\"").getMatch(0);
                    if (apiKeyTemp != null) {
                        if (apiKeyTemp.matches("(?i)^(Bearer|Basic) $")) {
                            logger.info("Skipping invalid authentication key: " + apiKeyTemp);
                            continue;
                        } else {
                            apiKeyDetected = apiKeyTemp;
                            break;
                        }
                    }
                }
                if (apiKeyDetected == null) {
                    logger.warning("Failed to find authentication key");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                logger.info("Detected authentication key: " + apiKeyDetected);
                apiKey.set(apiKeyDetected);
            }
            key = apiKey.get();
            br2.getHeaders().put("Authorization", key);
            br2.getPage("/mediathek/api/video/" + internalVideoID);
            if (br2.getHttpConnection().getResponseCode() == 401) {
                /* This should never happen */
                apiKey.set(null);
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "API authorization failure");
            }
        }
        entries = restoreFromString(br2.getRequest().getHtmlCode(), TypeRef.MAP);
        /* 2019-12-18: They provide HLS, DASH and http(highest quality only) */
        final List<Map<String, Object>> ressourcelist = (List<Map<String, Object>>) JavaScriptEngineFactory.walkJson(entries, "video/videoUrls");
        if (ressourcelist == null || ressourcelist.isEmpty()) {
            /* Most likely video is not available anymore because current date is > date value in field "schedulingEnd". */
            /*
             * E.g.
             * {"status":"no-video-urls","video":{"id":12345,"active":true,"crn":"12345","ovpId":"1234567890123","campaigns":[],"videoUrls":
             * [],"cuePoints":[]}}
             */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        try {
            long max_width = -1;
            for (final Map<String, Object> entry : ressourcelist) {
                String type = (String) entry.get("type");
                if (type == null) {
                    /* 2023-07-12: They got a newline character in this json key lol */
                    type = (String) entry.get("type\\n");
                }
                String url = (String) entry.get("url");
                if (url == null) {
                    url = (String) entry.get("src");
                }
                if (StringUtils.equals("application/x-mpegURL", type)) {
                    hlsURL = url;
                } else {
                    final long max_width_temp = JavaScriptEngineFactory.toLong(entry.get("width"), 0);
                    if (StringUtils.isEmpty(url) || !"video/mp4".equals(type)) {
                        logger.info("Skipping unsupported stream: " + url + "|" + type);
                        /* Skip invalid items and only grab http streams, ignore e.g. DASH streams. */
                        continue;
                    } else {
                        if (max_width_temp > max_width) {
                            mp4URL = url;
                            max_width = max_width_temp;
                        }
                    }
                }
            }
        } catch (final Exception e) {
            logger.log(e);
            logger.warning("Failed to find downloadurl due to exception");
        }
        if (StringUtils.isEmpty(mp4URL) && StringUtils.isEmpty(this.hlsURL)) {
            logger.info("Cannot download this item");
            final Object drm = JavaScriptEngineFactory.walkJson(entries, "drm");
            if (drm != null && StringUtils.equalsIgnoreCase("true", drm.toString()) && isDownload) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND, "DRM protected");
            } else {
                final String failureReason = (String) JavaScriptEngineFactory.walkJson(entries, "error/message");
                logger.info("failureReason: " + failureReason);
                if (isDownload) {
                    if (StringUtils.isEmpty(failureReason)) {
                        /* Assume that content is DRM protected. */
                        throw new PluginException(LinkStatus.ERROR_FATAL, "DRM protected content");
                    } else {
                        throw new PluginException(LinkStatus.ERROR_FATAL, failureReason);
                    }
                }
            }
        } else if (!StringUtils.isEmpty(mp4URL)) {
            URLConnectionAdapter con = null;
            try {
                final Browser brc = br.cloneBrowser();
                brc.setFollowRedirects(true);
                /* 2022-03-07: HEAD-request doesn't work for all items anymore. Use GET-request instead. */
                con = brc.openGetConnection(mp4URL);
                handleConnectionErrors(brc, con);
                if (con.getCompleteContentLength() > 0) {
                    link.setVerifiedFileSize(con.getCompleteContentLength());
                }
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link, true);
        if (mp4URL != null) {
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, mp4URL, free_resume, free_maxchunks);
            handleConnectionErrors(br, dl.getConnection());
        } else if (hlsURL != null) {
            if (true) {
                // split audio/video
                throw new PluginException(LinkStatus.ERROR_FATAL, "Unsupported streaming type: HLS with split audio/video or DRM protected");
            }
            final Browser brc = br.cloneBrowser();
            brc.setFollowRedirects(true);
            brc.getPage(hlsURL);
            final HlsContainer hlsBest = HlsContainer.findBestVideoByBandwidth(HlsContainer.getHlsQualities(brc));
            if (hlsBest == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            checkFFmpeg(link, "Download a HLS Stream");
            dl = new HLSDownloader(link, br, hlsBest.getM3U8URL());
        } else {
            /* This should never happen! */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    private void handleConnectionErrors(final Browser br, final URLConnectionAdapter con) throws PluginException, IOException {
        if (!this.looksLikeDownloadableContent(con)) {
            br.followConnection(true);
            if (con.getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (con.getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Video broken?");
            }
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        if (apiKey.get() == null) {
            /* 2023-03-30: Only allow unlimited downloads to start once we've detected that key. */
            return 1;
        } else {
            return free_maxdownloads;
        }
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.KalturaVideoPlatform;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetPluginGlobals() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}
