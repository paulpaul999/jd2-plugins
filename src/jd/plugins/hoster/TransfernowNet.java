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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.appwork.storage.TypeRef;
import org.appwork.utils.StringUtils;
import org.appwork.utils.parser.UrlQuery;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 48444 $", interfaceVersion = 3, names = {}, urls = {})
public class TransfernowNet extends PluginForHost {
    public TransfernowNet(PluginWrapper wrapper) {
        super(wrapper);
        // this.enablePremium("");
    }

    @Override
    public String getAGBLink() {
        return "https://www.transfernow.net/de/bedingungen";
    }

    private static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "transfernow.net" });
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
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(dl/[A-Za-z0-9]+(?:/[A-Za-z0-9]+)?|[a-z]{2,}/dltransfer\\?utm_source=[A-Za-z0-9]+(?:\\&utm_medium=[A-Za-z0-9]+)?)");
        }
        return ret.toArray(new String[0]);
    }

    /* Connection stuff */
    private static final boolean FREE_RESUME       = true;
    private static final int     FREE_MAXCHUNKS    = 0;
    private static final int     FREE_MAXDOWNLOADS = 20;

    @Override
    public String getLinkID(final DownloadLink link) {
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://" + fid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        if (link.getPluginPatternMatcher() == null) {
            return null;
        } else if (link.getPluginPatternMatcher().matches(TYPE_1)) {
            return new Regex(link.getPluginPatternMatcher(), TYPE_1).getMatch(0);
        } else {
            return new Regex(link.getPluginPatternMatcher(), TYPE_2).getMatch(0);
        }
    }

    private String getSecret(final DownloadLink link) {
        if (link.getPluginPatternMatcher() == null) {
            return null;
        } else if (link.getPluginPatternMatcher().matches(TYPE_1)) {
            return new Regex(link.getPluginPatternMatcher(), TYPE_1).getMatch(2);
        } else {
            return new Regex(link.getPluginPatternMatcher(), TYPE_2).getMatch(2);
        }
    }

    private final String        PROPERTY_SINGLE_FILE_ID = "single_file_id";
    private static final String TYPE_1                  = "https?://[^/]+/dl/([A-Za-z0-9]+)(/([A-Za-z0-9]+))?";
    private static final String TYPE_2                  = "https?://[^/]+/[a-z]{2,}/dltransfer\\?utm_source=([A-Za-z0-9]+)(\\&utm_medium=([A-Za-z0-9]+))?";
    private static final String API_BASE                = "https://www.transfernow.net/api";

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        if (!link.isNameSet()) {
            /* Fallback */
            link.setName(this.getFID(link) + ".zip");
        }
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        final UrlQuery query = new UrlQuery();
        query.add("transferId", this.getFID(link));
        final String secret = getSecret(link);
        query.add("userSecret", secret != null ? secret : "");
        query.add("preview", "false");
        br.getPage(API_BASE + "/transfer/downloads/metadata?" + query.toString());
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final Map<String, Object> root = restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
        final List<Map<String, Object>> files = (List<Map<String, Object>>) root.get("files");
        long totalSize = 0;
        String nameOfFirstFile = null;
        String idOfFirstFile = null;
        for (final Map<String, Object> file : files) {
            if (nameOfFirstFile == null) {
                nameOfFirstFile = file.get("name").toString();
            }
            if (idOfFirstFile == null) {
                idOfFirstFile = file.get("id").toString();
            }
            totalSize += ((Number) file.get("size")).longValue();
        }
        link.setPasswordProtected(((Boolean) root.get("needPassword")).booleanValue());
        String title = (String) root.get("transferName");
        if (files.size() == 1 && nameOfFirstFile != null) {
            link.setFinalFileName(nameOfFirstFile);
            link.setProperty(PROPERTY_SINGLE_FILE_ID, idOfFirstFile);
        } else {
            link.removeProperty(PROPERTY_SINGLE_FILE_ID);
            if (!StringUtils.isEmpty(title)) {
                link.setFinalFileName(title + ".zip");
            } else {
                link.setFinalFileName(this.getFID(link) + ".zip");
            }
        }
        link.setDownloadSize(totalSize);
        /* Files can be offline while file info is still given. */
        if (!root.get("status").toString().equalsIgnoreCase("ENABLED")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        handleDownload(link, FREE_RESUME, FREE_MAXCHUNKS, "free_directlink");
    }

    private void handleDownload(final DownloadLink link, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        if (!attemptStoredDownloadurlDownload(link, directlinkproperty, resumable, maxchunks)) {
            requestFileInformation(link);
            final String idOfFirstFile = link.getStringProperty(PROPERTY_SINGLE_FILE_ID);
            final UrlQuery query = new UrlQuery();
            query.add("transferId", this.getFID(link));
            final String secret = getSecret(link);
            query.add("userSecret", secret != null ? secret : "");
            query.add("preview", "false");
            /* Empty string = Download all files inside a folder as .zip file */
            query.add("fileId", idOfFirstFile != null ? idOfFirstFile : "");
            String passCode = link.getDownloadPassword();
            if (link.isPasswordProtected()) {
                /* Check for stored password. Ask user if none is available. */
                if (passCode == null) {
                    passCode = getUserInput("Password?", link);
                }
                query.add("password", Encoding.urlEncode(passCode));
            }
            br.getPage(API_BASE + "/transfer/downloads/link?" + query.toString());
            if (br.getHttpConnection().getResponseCode() == 403 && link.isPasswordProtected()) {
                link.setDownloadPassword(null);
                throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
            }
            if (passCode != null) {
                link.setDownloadPassword(passCode);
            }
            final Map<String, Object> root = restoreFromString(br.getRequest().getHtmlCode(), TypeRef.MAP);
            final String dllink = (String) root.get("url");
            if (StringUtils.isEmpty(dllink)) {
                logger.warning("Failed to find final downloadurl");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resumable, maxchunks);
            if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                br.followConnection(true);
                if (dl.getConnection().getResponseCode() == 403) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 5 * 60 * 1000l);
                } else if (dl.getConnection().getResponseCode() == 404) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 5 * 60 * 1000l);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            link.setProperty(directlinkproperty, dl.getConnection().getURL().toString());
        }
        dl.startDownload();
    }

    @Override
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        /* 2021-05-27: No captchas at all */
        return false;
    }

    private boolean attemptStoredDownloadurlDownload(final DownloadLink link, final String directlinkproperty, final boolean resumable, final int maxchunks) throws Exception {
        final String url = link.getStringProperty(directlinkproperty);
        if (StringUtils.isEmpty(url)) {
            return false;
        }
        try {
            final Browser brc = br.cloneBrowser();
            dl = new jd.plugins.BrowserAdapter().openDownload(brc, link, url, resumable, maxchunks);
            if (this.looksLikeDownloadableContent(dl.getConnection())) {
                return true;
            } else {
                brc.followConnection(true);
                throw new IOException();
            }
        } catch (final Throwable e) {
            logger.log(e);
            try {
                dl.getConnection().disconnect();
            } catch (Throwable ignore) {
            }
            return false;
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return FREE_MAXDOWNLOADS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}