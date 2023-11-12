package jd.plugins.decrypter;

import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.net.websocket.ReadWebSocketFrame;
import org.appwork.utils.net.websocket.WebSocketFrameHeader;
import org.appwork.utils.parser.UrlQuery;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DecrypterRetryException;
import jd.plugins.DecrypterRetryException.RetryReason;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.PluginJSonUtils;
import jd.websocket.WebSocketClient;

@DecrypterPlugin(revision = "$Revision: 48194 $", interfaceVersion = 2, names = { "volafile.org" }, urls = { "https?://(?:www\\.)?volafile\\.(?:org|io)/r/[A-Za-z0-9\\-_]+" })
public class VolaFileOrg extends PluginForDecrypt {
    public VolaFileOrg(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> ret = new ArrayList<DownloadLink>();
        br.setFollowRedirects(true);
        br.getPage(param.getCryptedUrl().replace(".io/", ".org/"));
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        br.followRedirect();
        br.setCookie(getHost(), "allow-download", "1");
        final String checksum = PluginJSonUtils.getJson(br, "checksum2");
        if (StringUtils.isEmpty(checksum)) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        String room = br.getRegex("\"room_id\"\\s*:\\s*\"(.*?)\"").getMatch(0);
        if (room == null) {
            room = new Regex(param.getCryptedUrl(), "/r/([A-Za-z0-9\\-_]+)").getMatch(0);
            if (StringUtils.isEmpty(room)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        final UrlQuery query = new UrlQuery();
        query.add("room", URLEncoder.encode(room, "UTF-8"));
        query.add("cs", URLEncoder.encode(checksum, "UTF-8"));
        query.add("nick", "Alke");
        query.add("EIO", "3");
        query.add("transport", "websocket");
        String passCode = null;
        do {
            final WebSocketClient wsc = new WebSocketClient(br, new URL("https://" + this.getHost() + "/api/?" + query.toString()));
            try {
                wsc.connect();
                ReadWebSocketFrame frame = wsc.readNextFrame();// sid
                frame = wsc.readNextFrame();// session
                frame = wsc.readNextFrame();// subscription
                if (WebSocketFrameHeader.OP_CODE.UTF8_TEXT.equals(frame.getOpCode()) && frame.isFin()) {
                    String string = new String(frame.getPayload(), "UTF-8");
                    string = string.replaceFirst("^\\d+", "");
                    if (string.startsWith("[-1") && !string.contains("\"files\"")) {
                        /* Password protected content */
                        if (passCode != null) {
                            /* Wrong password - Don't allow 2nd try. */
                            throw new DecrypterRetryException(RetryReason.PASSWORD);
                        }
                        passCode = getUserInput("Password?", param);
                        query.add("password", URLEncoder.encode(passCode, "UTF-8"));
                        continue;
                    }
                    final List<Object> list = restoreFromString(string, TypeRef.LIST);
                    List<Object> files = (List<Object>) ((Map<String, Object>) ((List<Object>) ((List<Object>) ((List<Object>) list.get(6)).get(0)).get(1)).get(1)).get("files");
                    if (files == null) {
                        files = (List<Object>) ((Map<String, Object>) ((List<Object>) ((List<Object>) ((List<Object>) list.get(7)).get(0)).get(1)).get(1)).get("files");
                    }
                    if (files == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    } else {
                        for (final Object file : files) {
                            final List<Object> fileInfo = (List<Object>) file;
                            final String fileName = String.valueOf(fileInfo.get(1));
                            final Number fileSize = ((Number) fileInfo.get(3));
                            final DownloadLink link = createDownloadlink("https://volafile.org/download/" + fileInfo.get(0) + "/" + URLEncoder.encode(fileName, "UTF-8"));
                            link.setFinalFileName(fileName);
                            link.setVerifiedFileSize(fileSize.longValue());
                            link.setAvailable(true);
                            if (passCode != null) {
                                link.setDownloadPassword(passCode);
                            }
                            /* Set this as Packagizer/EventScripter property so user can see when the file will get deleted. */
                            final long expireTimestamp = ((Number) fileInfo.get(4)).longValue();
                            final SimpleDateFormat sd = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                            final String dateFormatted = sd.format(new Date(expireTimestamp));
                            link.setComment("Expires: " + dateFormatted);
                            link.setProperty("expireTimestamp", expireTimestamp);
                            ret.add(link);
                        }
                        return ret;
                    }
                } else {
                    logger.severe("Unsupported:" + frame);
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            } finally {
                wsc.close();
            }
        } while (!StringUtils.isEmpty(passCode));
        return ret;
    }
}
