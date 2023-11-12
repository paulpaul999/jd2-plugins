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
package jd.plugins.decrypter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.utils.formatter.SizeFormatter;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.http.Browser;
import jd.http.requests.PostRequest;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.hoster.OneFichierCom;
import jd.utils.JDUtilities;

@DecrypterPlugin(revision = "$Revision: 48194 $", interfaceVersion = 3, names = {}, urls = {})
public class OneFichierComFolder extends PluginForDecrypt {
    public OneFichierComFolder(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void init() {
        OneFichierCom.setRequestIntervalLimits();
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(OneFichierCom.getPluginDomains());
    }

    public static String[] getAnnotationNames() {
        return OneFichierCom.getAnnotationNames();
    }

    /**
     * returns the annotation pattern array
     *
     */
    public static String[] getAnnotationUrls() {
        final String host = "https?://(?:www\\.)?" + buildHostsPatternPart(OneFichierCom.getPluginDomains().get(0));
        return new String[] { host + "/(?:(?:[a-z]{2})/)?dir/([A-Za-z0-9]+)" };
    }

    private ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();

    public ArrayList<DownloadLink> decryptIt(final CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<Account> accounts = AccountController.getInstance().getAllAccounts(this.getHost());
        Account account = null;
        if (accounts != null && accounts.size() != 0) {
            Iterator<Account> it = accounts.iterator();
            while (it.hasNext()) {
                Account n = it.next();
                if (n.isEnabled() && n.isValid() && n.getType() == AccountType.PREMIUM) {
                    account = n;
                    break;
                }
            }
        }
        /**
         * 2019-04-05: Folder support via API does not work (serverside) as it requires us to have the internal folder-IDs which we do not
         * have! </br>
         * Basically their folder API call is only for internal folders of the current user -> Not useful for us! See also:
         * https://1fichier.com/api.html
         */
        if (OneFichierCom.canUseAPI(account) && false) {
            /* Use premium API */
            crawlAPI(param, account);
        } else {
            /* Use website */
            crawlWebsite(param);
        }
        return decryptedLinks;
    }

    private void crawlAPI(final CryptedLink param, final Account account) throws Exception {
        // final String folderID = new Regex(param.toString(), this.getSupportedLinks()).getMatch(0);
        OneFichierCom.setPremiumAPIHeaders(this.br, account);
        final PostRequest downloadReq = br.createJSonPostRequest(OneFichierCom.API_BASE + "/file/ls.cgi", "");
        downloadReq.setContentType("application/json");
        br.openRequestConnection(downloadReq);
        br.loadConnection(null);
    }

    private void crawlWebsite(final CryptedLink param) throws Exception {
        final String folderID = new Regex(param.getCryptedUrl(), this.getSupportedLinks()).getMatch(0);
        final String folderURL = "https://" + this.getHost() + "/dir/" + folderID + "?lg=en";
        prepareBrowser(br);
        br.setLoadLimit(Integer.MAX_VALUE);
        final Browser jsonBR = br.cloneBrowser();
        jsonBR.getPage(folderURL + "?json=1");
        if (jsonBR.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* Access folder without API just to find foldername ... */
        br.getPage(folderURL);
        /* We prefer English but let's be prepared to parse both versions of their website, english and french. */
        final String fpName = br.getRegex(">(?:Shared folder|Dossier partagé)\\s*(.*?)</").getMatch(0);
        // password handling
        final String password = handlePassword(param, folderURL);
        if (password == null && "application/json; charset=utf-8".equals(jsonBR.getHttpConnection().getContentType())) {
            final List<Object> ressourcelist = restoreFromString(jsonBR.toString(), TypeRef.LIST);
            for (final Object fileO : ressourcelist) {
                final Map<String, Object> fileInfo = (Map<String, Object>) fileO;
                final String filename = (String) fileInfo.get("filename");
                final long filesize = ((Number) fileInfo.get("size")).longValue();
                final String url = (String) fileInfo.get("link");
                final int pwProtected = ((Number) fileInfo.get("password")).intValue();
                final int accessControlLimitedLink = ((Number) fileInfo.get("acl")).intValue();
                final DownloadLink dl = createDownloadlink(url);
                dl.setFinalFileName(filename);
                dl.setVerifiedFileSize(filesize);
                if (password != null) {
                    dl.setDownloadPassword(password);
                }
                dl.setAvailable(true);
                if (pwProtected == 1) {
                    dl.setPasswordProtected(true);
                }
                if (accessControlLimitedLink == 1) {
                    dl.setProperty(OneFichierCom.PROPERTY_ACL_ACCESS_CONTROL_LIMIT, true);
                }
                decryptedLinks.add(dl);
            }
        } else {
            // webmode
            final String[][] linkInfo = getLinkInfo();
            if (linkInfo == null || linkInfo.length == 0) {
                throw new DecrypterException("Plugin broken");
            }
            for (String singleLinkInfo[] : linkInfo) {
                final DownloadLink dl = createDownloadlink(singleLinkInfo[1]);
                dl.setFinalFileName(Encoding.htmlDecode(singleLinkInfo[3]));
                dl.setDownloadSize(SizeFormatter.getSize(singleLinkInfo[4]));
                if (password != null) {
                    dl.setDownloadPassword(password);
                }
                dl.setAvailable(true);
                decryptedLinks.add(dl);
            }
        }
        if (fpName != null) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(fpName);
            fp.addLinks(decryptedLinks);
        }
    }

    private final String[][] getLinkInfo() {
        // some reason the e=1 reference now spews html not deliminated results.
        // final String[][] linkInfo = br.getRegex("(https?://[a-z0-9\\-]+\\..*?);([^;]+);([0-9]+)").getMatches();
        final String[][] linkInfo = br.getRegex("<a href=(\"|')(" + JDUtilities.getPluginForHost("1fichier.com").getSupportedLinks() + ")\\1[^>]*>([^\r\n\t]+)</a>\\s*</td>\\s*<td[^>]*>([^\r\n\t]+)</td>").getMatches();
        return linkInfo;
    }

    private final String handlePassword(final CryptedLink param, final String parameter) throws Exception {
        if (browserContainsFolderPasswordForm(this.br)) {
            /* First try last crawler link password if available */
            String passCode = param.getDecrypterPassword();
            final int repeat = 3;
            for (int i = 0; i <= repeat; i++) {
                if (passCode == null) {
                    passCode = getUserInput(null, param);
                }
                br.postPage(parameter + "?json=1", "pass=" + Encoding.urlEncode(passCode));
                if (browserContainsFolderPasswordForm(this.br)) {
                    if (i + 1 >= repeat) {
                        throw new DecrypterException(DecrypterException.PASSWORD);
                    }
                    passCode = null;
                    continue;
                }
                return passCode;
            }
        }
        return null;
    }

    private boolean browserContainsFolderPasswordForm(final Browser br) {
        final Form pwform = br.getFormbyKey("pass");
        return pwform != null && this.canHandle(pwform.getAction());
    }

    private void prepareBrowser(final Browser br) {
        if (br == null) {
            return;
        }
        br.getHeaders().put("User-Agent", "JDownloader");
        br.getHeaders().put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        br.getHeaders().put("Accept-Language", "en-us,en;q=0.5");
        br.getHeaders().put("Pragma", null);
        br.getHeaders().put("Cache-Control", null);
        br.setCustomCharset("UTF-8");
        br.setFollowRedirects(true);
        // we want ENGLISH!
        br.setCookie(this.getHost(), "LG", "en");
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }
}