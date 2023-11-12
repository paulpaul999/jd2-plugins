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

import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.components.XFileSharingProBasic;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision: 46542 $", interfaceVersion = 3, names = {}, urls = {})
public class VidozaNet extends XFileSharingProBasic {
    public VidozaNet(final PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(super.getPurchasePremiumURL());
    }

    /**
     * DEV NOTES XfileSharingProBasic Version SEE SUPER-CLASS<br />
     * mods: See overridden functions<br />
     * limit-info:<br />
     * captchatype-info: null (official download has reCaptchaV2)<br />
     * other:<br />
     */
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

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "vidoza.net", "vidoza.org" });
        return ret;
    }

    @Override
    public String[] scanInfo(final String[] fileInfo) {
        super.scanInfo(fileInfo);
        final String betterFilename = br.getRegex("var\\s*curFileName\\s*=\\s*\"(.*?)\"").getMatch(0);
        if (StringUtils.isNotEmpty(betterFilename)) {
            fileInfo[0] = betterFilename;
        }
        return fileInfo;
    }

    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return true;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return true;
        } else {
            /* Free(anonymous) and unknown account type */
            return true;
        }
    }

    @Override
    public int getMaxChunks(final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return 1;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return 1;
        } else {
            /* Free(anonymous) and unknown account type */
            return 1;
        }
    }

    @Override
    public int getMaxSimultaneousFreeAnonymousDownloads() {
        return 5;
    }

    @Override
    public int getMaxSimultaneousFreeAccountDownloads() {
        return 5;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return 5;
    }

    @Override
    public AvailableStatus requestFileInformationWebsite(final DownloadLink link, final Account account, final boolean isDownload) throws Exception {
        try {
            return super.requestFileInformationWebsite(link, account, isDownload);
        } catch (final PluginException e) {
            if (e.getLinkStatus() == LinkStatus.ERROR_FILE_NOT_FOUND && !this.isEmbedURL(br.getURL())) {
                /* 2022-05-17: Special handling for seemingly offline files which may still be playable and downloadable via embed URL. */
                /* File-title can even be given for offline items! */
                final String title = br.getRegex("<title>Watch ([^<]*?)( mp4)?</title>").getMatch(0);
                if (title != null) {
                    link.setFinalFileName(Encoding.htmlDecode(title) + ".mp4");
                }
                requestFileInformationVideoEmbed(br.cloneBrowser(), link, account, true);
                return AvailableStatus.TRUE;
            } else {
                throw e;
            }
        }
    }

    @Override
    protected boolean isOffline(final DownloadLink link, final Browser br, final String html) {
        boolean isOffline = super.isOffline(link, br, html);
        if (!isOffline && html != null) {
            /* 2019-07-04: Special: */
            isOffline = html.contains("/embed-.html\"") || html.contains("Reason for deletion:");
        }
        return isOffline;
    }

    @Override
    public boolean supports_availablecheck_filename_abuse() {
        /* 2019-07-04: Special */
        return false;
    }

    @Override
    protected boolean isVideohoster_enforce_video_filename() {
        return true;
    }

    @Override
    protected boolean isVideohosterEmbed() {
        return true;
    }
}