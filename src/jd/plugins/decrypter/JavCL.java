package jd.plugins.decrypter;

import java.util.ArrayList;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;

import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.Base64;
import org.jdownloader.plugins.components.antiDDoSForDecrypt;

@DecrypterPlugin(revision = "$Revision: 43201 $", interfaceVersion = 2, names = { "javcl.com" }, urls = { "https?://(?:www\\.)?javcl\\.com/([a-z0-9\\-]+)" })
public class JavCL extends antiDDoSForDecrypt {
    public JavCL(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public ArrayList<DownloadLink> decryptIt(CryptedLink parameter, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        getPage(parameter.getCryptedUrl());
        if (br.getHttpConnection().getResponseCode() == 404) {
            ret.add(this.createOfflinelink(parameter.getCryptedUrl()));
            return ret;
        }
        br.followRedirect();
        final String filename = br.getRegex("<span class=([\"\']?)title2\\1>([a-zA-Z0-9-]+)</span>").getMatch(1);
        final String data_id = br.getRegex("<div id=([\"\']?)videoPlayer\\1 data-id=([\"\']?)([0-9]+)\\2 data-ep=([\"\']?)([0-9]+)\\4>").getMatch(2);
        final String data_links[] = br.getRegex("<li data-sv=([\"\']?)([0-9]+)\\1 data-link=([\"\']?)([a-zA-Z0-9/+=-]+)\\3(?: class=([\\\"\\']?)active\\5)?>\\s*([^</]+)\\s*</li>").getColumn(3);
        if (data_links == null | data_links.length == 0) {
            if (!br.containsHTML("id='jwplayer'")) {
                /* No downloadable content e.g. https://javcl.com/most-views/ */
                ret.add(this.createOfflinelink(parameter.getCryptedUrl()));
                return ret;
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        int index = 0;
        for (String data_link : data_links) {
            logger.info("data_id: " + data_id + ", data_links: " + data_link);
            if (data_link.equals("post")) {
                continue;
            }
            final String url = decodejav(data_link, data_id);
            logger.info("Output of decodejav(data_link, data_id): " + url);
            final String name;
            if (data_links.length > 1) {
                name = filename + "_" + (char) ('a' + index);
            } else {
                name = filename;
            }
            final DownloadLink downloadLink = createDownloadlink(url);
            if (!StringUtils.isEmpty(name)) {
                downloadLink.setProperty("javclName", name);
            }
            ret.add(downloadLink);
            index++;
        }
        return ret;
    }

    private String decodejav(String data_link, String data_id) {
        String key = Base64.encode(data_id + "decode11234jav");
        key = new StringBuilder(key).reverse().toString();
        final byte[] link = Base64.decode(data_link);
        final StringBuilder sb = new StringBuilder();
        int k = 0;
        for (int i = 0; i < link.length; i++) {
            k = i % key.length();
            sb.append((char) (link[i] ^ key.codePointAt(k)));
        }
        return new String(Base64.decode(sb.toString()));
    }
}
