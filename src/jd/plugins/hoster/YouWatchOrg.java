//jDownloader - Downloadmanager
//Copyright (C) 2013  JD-Team support@jdownloader.org
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.components.XFileSharingProBasic;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision: 48154 $", interfaceVersion = 3, names = {}, urls = {})
public class YouWatchOrg extends XFileSharingProBasic {
    public YouWatchOrg(final PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(super.getPurchasePremiumURL());
    }

    /**
     * DEV NOTES XfileSharingProBasic Version SEE SUPER-CLASS<br />
     * mods: See overridden functions<br />
     * limit-info:<br />
     * captchatype-info: 2020-06-12: null<br />
     * other:<br />
     */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "youwatch.org", "sikafika.info", "voodaith7e.com", "gh1d4fr.host" });
        return ret;
    }

    @Override
    protected List<String> getDeadDomains() {
        final ArrayList<String> deadDomains = new ArrayList<String>();
        deadDomains.add("sikafika.info");
        deadDomains.add("voodaith7e.com");
        deadDomains.add("gh1d4fr.host");
        return deadDomains;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        return XFileSharingProBasic.buildAnnotationUrls(getPluginDomains());
    }

    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        final AccountType type = account != null ? account.getType() : null;
        if (AccountType.FREE.equals(type)) {
            /* Free Account */
            return true;
        } else if (AccountType.PREMIUM.equals(type) || AccountType.LIFETIME.equals(type)) {
            /* Premium account */
            return true;
        } else {
            /* Free(anonymous) and unknown account type */
            return true;
        }
    }

    @Override
    public int getMaxChunks(final Account account) {
        final AccountType type = account != null ? account.getType() : null;
        if (AccountType.FREE.equals(type)) {
            /* Free Account */
            return 1;
        } else if (AccountType.PREMIUM.equals(type) || AccountType.LIFETIME.equals(type)) {
            /* Premium account */
            return 1;
        } else {
            /* Free(anonymous) and unknown account type */
            return 1;
        }
    }

    @Override
    public int getMaxSimultaneousFreeAnonymousDownloads() {
        return -1;
    }

    @Override
    public int getMaxSimultaneousFreeAccountDownloads() {
        return -1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    protected boolean isVideohosterEmbed() {
        /* 2020-06-12: Special */
        return true;
    }

    @Override
    protected boolean isVideohoster_enforce_video_filename() {
        /* 2020-06-12: Special */
        return true;
    }

    @Override
    protected boolean websiteSupportsHTTPS() {
        return false;
    }

    @Override
    protected String requestFileInformationVideoEmbed(final Browser br, final DownloadLink link, final Account account, final boolean findFilesize) throws Exception {
        /*
         * Some video sites contain their directurl right on the first page - let's use this as an indicator and assume that the file is
         * online if we find a directurl. This also speeds-up linkchecking! Example: uqload.com
         */
        String dllink = getDllink(link, account, br, correctedBR);
        if (StringUtils.isEmpty(dllink)) {
            if (br.getURL() != null && !br.getURL().contains("/embed")) {
                final String embed_access = getMainPage() + "/embed-" + this.getFUIDFromURL(link) + ".html";
                getPage(br, embed_access);
                /**
                 * 2019-07-03: Example response when embedding is not possible (deactivated or it is not a video-file): "Can't create video
                 * code" OR "Video embed restricted for this user"
                 */
                final String iframeURL = br.getRegex("<iframe [^>]*src=\"(https?://[^/]+/embed-[a-z0-9]{12}[^\"]+)\"").getMatch(0);
                if (iframeURL != null) {
                    getPage(br, iframeURL);
                }
            }
            /*
             * Important: Do NOT use 404 as offline-indicator here as the website-owner could have simply disabled embedding while it was
             * enabled before --> This would return 404 for all '/embed' URLs! Only rely on precise errormessages!
             */
            if (br.toString().equalsIgnoreCase("File was deleted")) {
                /* Should be valid for all XFS hosts e.g. speedvideo.net, uqload.com */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            dllink = getDllink(link, account, br, br.toString());
            // final String url_thumbnail = getVideoThumbnailURL(br.toString());
        }
        if (findFilesize && !StringUtils.isEmpty(dllink) && !dllink.contains(".m3u8")) {
            /* Get- and set filesize from directurl */
            final boolean dllink_is_valid = checkDirectLinkAndSetFilesize(link, dllink, true) != null;
            /* Store directurl if it is valid */
            if (dllink_is_valid) {
                storeDirecturl(link, account, dllink);
            }
        }
        return dllink;
    }

    @Override
    public String[] scanInfo(final String[] fileInfo) {
        /* 2020-06-12: Special: Encrypted filenames */
        // super.scanInfo(fileInfo);
        if (StringUtils.isEmpty(fileInfo[0])) {
            fileInfo[0] = new Regex(correctedBR, "<h3 id=\"title\">([^<>\"]*?)</h3>").getMatch(0);
        }
        if (!StringUtils.isEmpty(fileInfo[0])) {
            // Decode Caesar's shift code
            fileInfo[0] = fileInfo[0].replaceAll("(</?b>|\\.html)", "");
            fileInfo[0] = fileInfo[0].trim();
            if (br.containsHTML("\\$\\(\"\\#title\"\\).text\\(\\s*vrot\\(\\s*\\$\\(\"\\#title\"\\)") && fileInfo[0] != null) {
                final StringBuilder sb = new StringBuilder();
                for (int index = 0; index < fileInfo[0].length(); index++) {
                    final char c = fileInfo[0].charAt(index);
                    final String v = String.valueOf(c);
                    if (v.matches("[a-zA-Z]{1}")) {
                        final char n = (char) (c + (v.toUpperCase(Locale.ENGLISH).charAt(0) <= 'M' ? 13 : -13));
                        sb.append(n);
                    } else {
                        sb.append(c);
                    }
                }
                fileInfo[0] = sb.toString();
            }
        }
        return fileInfo;
    }

    @Override
    protected boolean isOffline(final DownloadLink link, final Browser br, final String html) {
        if (!br.getURL().contains(this.getFUIDFromURL(this.getDownloadLink()))) {
            /* 2021-02-22 */
            return true;
        } else {
            return super.isOffline(link, br, html);
        }
    }
}