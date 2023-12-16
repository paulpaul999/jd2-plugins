package jd.plugins.decrypter;

import jd.plugins.PluginForDecrypt;

import java.util.ArrayList;
import java.util.List;

import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.PluginDependencies;
import jd.plugins.hoster.BadoinkvrCom;

@DecrypterPlugin(revision = "100000", interfaceVersion = 3, names = {}, urls = {})
@PluginDependencies(dependencies = { BadoinkvrCom.class })
public class HeresphereCrawler extends PluginForDecrypt {
    public HeresphereCrawler(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.XXX };
    }

    public static List<String[]> getPluginDomains() {
        return BadoinkvrCom.getPluginDomains();
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
            /** TODO review following magic comment */
            /**
             * 2023-11-14: </br>
             * vrpornvideo: badoinkvr.com, babevr.com, 18vr.com </br>
             * cosplaypornvideo: vrcosplayx.com </br>
             * bdsm-vr-video: kinkvr.com
             */
            ret.add("https?://(?:www\\.)?" + buildHostsPatternPart(domains) + "/(?:members/)?(?:[^/]+/)?[^/]*?(\\d{3,})");
        }
        return ret.toArray(new String[0]);
    }

    private String getVideoId(final String link) {
        return new Regex(link, this.getSupportedLinks()).getMatch(0);
    }


    @Override
    public ArrayList<DownloadLink> decryptIt(CryptedLink parameter, ProgressController progress) throws Exception {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'decryptIt'");
    }
}
