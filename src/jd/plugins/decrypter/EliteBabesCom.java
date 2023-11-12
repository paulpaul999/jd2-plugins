package jd.plugins.decrypter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.plugins.DecrypterPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;

/**
 * Downloads single galleries and all per model from elitebabes.com. Re-uses functionality from SimpleHtmlBasedGalleryPlugin, but has
 * specific functionality to get the gallery urls.
 *
 * It supports auto-paging for all galleries of a model.
 */
@DecrypterPlugin(revision = "$Revision: 46930 $", interfaceVersion = 2, names = {}, urls = {})
public class EliteBabesCom extends SimpleHtmlBasedGalleryPlugin {
    private static final SiteData SITE_DATA = new SiteData("elitebabes.com", "/(?!model/).+", "/model/.+", "[^\"']+");

    public EliteBabesCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    public static String[] getAnnotationNames() {
        return new String[] { SITE_DATA.host[0] };
    }

    @Override
    public String[] siteSupportedNames() {
        return SITE_DATA.host;
    }

    public static String[] getAnnotationUrls() {
        return new String[] { SITE_DATA.getUrlRegex() };
    }

    @Override
    protected String[] getRawImageUrls(Browser brc) {
        final String[] rawlinks = brc.getRegex("<a data-fancybox\\s*=\\s*\"images\"[^>]*(?:data-)srcset\\s*=\\s*(?:\"|')([^\"' ]+\\.jpe?g/?)").getColumn(0);
        if (rawlinks.length > 0) {
            return rawlinks;
        } else {
            return super.getRawImageUrls(brc);
        }
    }

    @Override
    protected List<SiteData> getSiteData() {
        ArrayList<SiteData> siteData = new ArrayList<SiteData>();
        siteData.add(SITE_DATA);
        return siteData;
    }

    // first page is "https://www.elitebabes.com/model/alena-i/" (accessible via UI)
    // next pages are "https://www.elitebabes.com/model/alena-i/mpage/2/" (NOT accessible via UI, only programmatic)
    protected boolean fetchMoreGalleries() throws IOException {
        String[][] matches = br.getRegex("href\\s*=\\s*(?:\"|')([^\"']+mpage[^\"']+)(?:\"|')").getMatches();
        if (matches.length == 0) {
            // only 1 page, no next pages
            return false;
        }
        ArrayList<String> atLeastPage2Urls = new ArrayList<String>();
        for (String[] match : matches) {
            atLeastPage2Urls.add(match[0]);
        }
        Collections.sort(atLeastPage2Urls); // ascending, so .../mpage/2/, .../mpage/3/, and so on
        // find current page
        String currentUrl = br.getURL();
        int currentPageIndex = -1; // 1st page, not containing "mpage"
        for (int i = 0; i < atLeastPage2Urls.size(); i++) {
            if (currentUrl.equals(atLeastPage2Urls.get(i))) {
                currentPageIndex = i;
                break;
            }
        }
        // if next page is available/in the list
        if (currentPageIndex < atLeastPage2Urls.size() - 1) {
            // navigate to next page
            br.getPage(atLeastPage2Urls.get(currentPageIndex + 1)); // TODO handle 404 ?
            return true;
        }
        return false;
    }

    protected ArrayList<String> getCurrentGalleryUrls(String galleryHrefRegex) throws PluginException {
        ArrayList<String> galleryUrls = new ArrayList<String>();
        String[][] listItems = br.getRegex("<li>(.*?)</li>").getMatches();
        if (listItems.length == 0) {
            return galleryUrls;
        }
        for (String[] listItem : listItems) {
            try {
                if (!listItem[0].contains("title")) {
                    continue;
                }
                String galleryUrl = new Regex(listItem[0], "href\\s*=\\s*(?:\"|')(" + galleryHrefRegex + ")(?:\"|')").getMatch(0);
                if (StringUtils.isEmpty(galleryUrl)) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "no gallery match found");
                }
                galleryUrls.add(br.getURL(galleryUrl).toString());
            } catch (IOException e) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, null, e);
            }
        }
        return galleryUrls;
    }
}
