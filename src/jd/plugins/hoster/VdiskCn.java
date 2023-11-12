//jDownloader - Downloadmanager
//Copyright (C) 2012  JD-Team support@jdownloader.org
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
import java.util.concurrent.atomic.AtomicReference;

import org.appwork.utils.formatter.SizeFormatter;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.RandomUserAgent;
import jd.nutils.encoding.Encoding;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 47476 $", interfaceVersion = 2, names = { "vdisk.cn" }, urls = { "http://(www\\.)?([a-z0-9]+\\.)?vdisk\\.cn/(?:down/index/[A-Z0-9]+|[a-zA-Z0-9]+/.*?\\.html)" })
public class VdiskCn extends PluginForHost {
    // No HTTPS
    // Found hard to test this hoster, has many server issues.
    // locked it to 2(dl) * -4(chunk) = 8 total connection
    // other: they keep changing final download links url structure, best to use
    // regex only on finallink static info and not html
    public static AtomicReference<String> agent = new AtomicReference<String>(null);

    private String getUserAgent() {
        while (true) {
            String agent = VdiskCn.agent.get();
            if (agent == null) {
                VdiskCn.agent.compareAndSet(null, RandomUserAgent.generate());
            } else {
                return agent;
            }
        }
    }

    private static final String NOCHUNKS = "NOCHUNKS";

    public VdiskCn(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void correctDownloadLink(final DownloadLink link) throws Exception {
        /** We only use the main domain */
        link.setUrlDownload(link.getDownloadURL().replaceAll("(www\\.)?([a-z0-9]+\\.)?vdisk\\.cn/down", "www.vdisk.cn/down"));
    }

    @Override
    public String getAGBLink() {
        return "http://vdisk.cn/terms/";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        String dllink = link.getStringProperty("freelink");
        boolean startDL = false;
        if (dllink != null) {
            try {
                br.setReadTimeout(3 * 60 * 1000);
                br.setFollowRedirects(true);
                br.setCookie("http://vdisk.cn/", "lang", "en");
                dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
                if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                    link.setProperty("freelink", Property.NULL);
                    dllink = null;
                    try {
                        dl.getConnection().disconnect();
                    } catch (final Throwable e) {
                    }
                } else {
                    startDL = true;
                }
            } catch (Exception e) {
                startDL = false;
                link.setProperty("freelink", Property.NULL);
                dllink = null;
            }
        }
        if (dllink == null) {
            requestFileInformation(link);
            dllink = getDownloadurl(br);
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        int chunks = 0;
        if (link.getBooleanProperty(VdiskCn.NOCHUNKS, false)) {
            chunks = 1;
        }
        if (startDL == false) {
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, chunks);
        }
        if (!this.looksLikeDownloadableContent(dl.getConnection())) {
            br.followConnection(true);
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        link.setProperty("freelink", dllink);
        if (link.getFinalFileName() == null) {
            link.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(dl.getConnection())));
        }
        if (!this.dl.startDownload()) {
            try {
                if (dl.externalDownloadStop()) {
                    return;
                }
            } catch (final Throwable e) {
            }
            /* unknown error, we disable multiple chunks */
            if (link.getBooleanProperty(VdiskCn.NOCHUNKS, false) == false) {
                link.setProperty(VdiskCn.NOCHUNKS, Boolean.valueOf(true));
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
        }
    }

    private String getDownloadurl(final Browser br) {
        return br.getRegex("(http://[\\w\\.]+?vdisk\\.cn/[^/]+/[0-9A-Z]{2}/[A-Z0-9]{32}\\?key=[a-z0-9]{32}[^\"\\>]+)").getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        br.getHeaders().put("User-Agent", getUserAgent());
        br.setReadTimeout(3 * 60 * 1000);
        br.setFollowRedirects(true);
        br.setCookie("http://vdisk.cn/", "lang", "en");
        br.getPage(link.getPluginPatternMatcher());
        String filename = br.getRegex("(?i)文件名称: <b>(.*?)</b><br>").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("(?i)<META content=\"(.*?)\" name=\"description\">").getMatch(0);
        }
        if (filename == null) {
            filename = br.getRegex(">文件名称</td>\\s*<td>(.*?)</td>").getMatch(0);
        }
        if (filename == null) {
            /* 2022-01-14 */
            filename = br.getRegex("<title>([^<>\"]+) - 威盘网vdisk\\.cn</title>").getMatch(0);
        }
        String filesize = br.getRegex("(?i)文件大小: ([\\d\\.]+ ?(GB|MB|KB|B))").getMatch(0);
        if (filesize == null) {
            filesize = br.getRegex(">文件大小</td>\\s*<td>(.*?)</td>").getMatch(0);
        }
        if (filesize == null) {
            /* 2020-12-02 */
            filesize = br.getRegex("文件大小:\\s*(\\d+)").getMatch(0);
        }
        if (filesize == null) {
            /* 2022-01-14 */
            filesize = br.getRegex(">大小：(\\d+ [^<]+)</div>").getMatch(0);
        }
        String MD5sum = br.getRegex("(?i)文件校验: ([A-Z0-9]{32})").getMatch(0);
        if (MD5sum == null) {
            MD5sum = br.getRegex(">MD5</td>\\s*<td>([a-fA-F0-9]{32})</td>").getMatch(0);
            if (MD5sum == null) {
                logger.warning("Can't find MD5sum, Please report issue to JDownloader Development!");
                logger.warning("Continuing...");
            }
        }
        if (filename != null) {
            link.setName(Encoding.htmlDecode(filename.trim()));
        }
        if (filesize != null) {
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        if (MD5sum != null) {
            link.setMD5Hash(MD5sum);
        }
        final String directurl = this.getDownloadurl(this.br);
        /* 2020-12-02: Filename/size can still be given for offline files! */
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("(文件已删除,无法下载\\.|>此文件涉嫌有害信息不允许下载\\!<|>找不到您需要的页面\\!<)")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML(">\\s*该文件已不提供下载")) {
            /* 2020-12-02 */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (directurl == null) {
            /* 2022-01-14: Treat undownloadable files as offline */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}