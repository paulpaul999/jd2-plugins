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

import jd.PluginWrapper;
import jd.http.Browser;
import jd.plugins.Account;
import jd.plugins.HostPlugin;

@HostPlugin(revision = "$Revision: 46959 $", interfaceVersion = 3, names = {}, urls = {})
public class AnysexCom extends KernelVideoSharingComV2 {
    public AnysexCom(final PluginWrapper wrapper) {
        super(wrapper);
    }

    /** Add all KVS hosts to this list that fit the main template without the need of ANY changes to this class. */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "anysex.com" });
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
        return KernelVideoSharingComV2.buildAnnotationUrlsDefaultVideosPatternOnlyNumbers(getPluginDomains());
    }

    @Override
    protected String regexNormalTitleWebsite(final Browser br) {
        String title = br.getRegex("<title>([^<>\"]+) - AnySex\\.com Video</title>").getMatch(0);
        if (title == null) {
            title = br.getRegex("<h1 itemprop=\"name\">([^<>\"]+)</h1>").getMatch(0);
        }
        if (title != null) {
            return title;
        } else {
            /* Fallback to upper handling */
            return super.regexNormalTitleWebsite(br);
        }
    }

    @Override
    protected int getMaxChunks(final Account account) {
        /* 2022-03-02: Varies per server. Use max. = 1 to be safe. */
        return 1;
    }

    @Override
    String generateContentURL(final String host, final String fuid, final String urlSlug) {
        return generateContentURLDefaultVideosPatternOnlyNumbers(host, fuid);
    }
}