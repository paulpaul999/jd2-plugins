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
package jd.plugins.decrypter;

import java.util.ArrayList;
import java.util.List;

import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.hoster.FreeViewMoviesCom;

@DecrypterPlugin(revision = "$Revision: 48304 $", interfaceVersion = 2, names = {}, urls = {})
public class FreeViewMoviesComCrawler extends PornEmbedParser {
    public FreeViewMoviesComCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForDecrypt, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "freeviewmovies.com" });
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
        return buildAnnotationUrls(getPluginDomains());
    }

    public static String[] buildAnnotationUrls(final List<String[]> pluginDomains) {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : pluginDomains) {
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/videos?/(\\d+)/([a-z0-9\\-]+)");
        }
        return ret.toArray(new String[0]);
    }

    @Override
    protected boolean isOffline(final Browser br) {
        return FreeViewMoviesCom.isOffline(br);
    }

    @Override
    protected String getFileTitle(final CryptedLink param, final Browser br) {
        return new Regex(param.getCryptedUrl(), this.getSupportedLinks()).getMatch(1).replace("-", " ").trim();
    }
    // @Override
    // protected boolean isSelfhosted(final Browser br) {
    // final String embedURL = br.getRegex(FreeViewMoviesCom.TYPE_EMBED).getMatch(-1);
    // if (embedURL != null && embedURL.contains(br.getHost())) {
    // return true;
    // } else {
    // return false;
    // }
    // }

    @Override
    protected boolean allowResult(final String url) {
        final String embedregex = "https?://(?:www\\.)?" + buildHostsPatternPart(getPluginDomains().get(0)) + "/embed/\\d+";
        if (url.matches(embedregex)) {
            /* Do not allow self-embedded URLs. */
            return false;
        } else {
            return super.allowResult(url);
        }
    }

    @Override
    protected boolean assumeSelfhostedContentOnNoResults() {
        return true;
    }
}