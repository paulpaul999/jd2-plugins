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

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.controlling.ProgressController;
import jd.parser.Regex;
import jd.plugins.Account;
import jd.plugins.AccountRequiredException;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;

import org.appwork.utils.StringUtils;
import org.jdownloader.scripting.JavaScriptEngineFactory;

@DecrypterPlugin(revision = "$Revision: 46420 $", interfaceVersion = 3, names = { "udemy.com" }, urls = { "https?://(?:www\\.)?udemy\\.com/(?:course/[^/]+|share/.+)" })
public class UdemyComDecrypter extends PluginForDecrypt {
    public UdemyComDecrypter(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String     decrypter_domain = "udemydecrypted.com";
    private String                  course_id        = null;
    private ArrayList<DownloadLink> decryptedLinks   = new ArrayList<DownloadLink>();
    private static final String     TYPE_COURSE      = "https?://(?:www\\.)?udemy\\.com/course/([^/]+)";

    @SuppressWarnings("deprecation")
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        /* 2020-07-24: Don't do this anymore - always add all items of the course instead! */
        // if (parameter.matches(jd.plugins.hoster.UdemyCom.TYPE_SINGLE_PREMIUM_WEBSITE)) {
        // /* Single links --> Host plugin */
        // decryptedLinks.add(this.createDownloadlink(parameter.replace(this.getHost() + "/", decrypter_domain + "/")));
        // return decryptedLinks;
        // }
        final Account aa = AccountController.getInstance().getValidAccount(JDUtilities.getPluginForHost(this.getHost()));
        final PluginForHost hostPlugin = this.getNewPluginForHostInstance(this.getHost());
        if (aa == null) {
            /* Account is always required to access udemy.com content. */
            throw new AccountRequiredException();
        }
        ((jd.plugins.hoster.UdemyCom) hostPlugin).login(aa, false);
        ((jd.plugins.hoster.UdemyCom) hostPlugin).prepBRAPI(this.br);
        br.getPage(param.getCryptedUrl());
        br.followRedirect();
        if (this.br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        course_id = jd.plugins.hoster.UdemyCom.getCourseIDFromHtml(this.br);
        if (course_id == null) {
            /* Unsupported URL or offline */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        this.br.getPage("https://www.udemy.com/api-2.0/courses/" + course_id + "/?fields[course]=title,headline,description,prerequisites,objectives,target_audiences,url,is_published,is_approved,is_practice_test_course,content_length_video,instructional_level,locale,content_length_practice_test_questions,content_info,num_subscribers,visible_instructors,is_paid,is_private,is_owner_terms_banned,is_owned_by_instructor_team,image_240x135,instructor_status,is_cpe_compliant,cpe_field_of_study,cpe_program_level,num_cpe_credits&fields[locale]=simple_english_title&fields[user]=url,title,job_title,image_200_H,description,display_name,image_50x50,initials,url_twitter,url_facebook,url_linkedin,url_youtube,url_personal_website&caching_intent=True");
        Map<String, Object> entries = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
        final String courseSlug = new Regex(param.getCryptedUrl(), TYPE_COURSE).getMatch(0);
        String courseTitle = (String) entries.get("title");
        if (StringUtils.isEmpty(courseTitle)) {
            /* Fallback */
            courseTitle = courseSlug;
        }
        this.br.getPage("/api-2.0/courses/" + course_id + "/subscriber-curriculum-items?fields%5Basset%5D=@min,title,filename,asset_type,external_url,length&fields%5Bchapter%5D=@min,description,object_index,title,sort_order&fields%5Blecture%5D=@min,object_index,asset,supplementary_assets,sort_order,is_published,is_free&fields%5Bquiz%5D=@min,object_index,title,sort_order,is_published&page_size=9999");
        if (this.br.getHttpConnection().getResponseCode() == 403) {
            logger.info("User tried to download content which he did not pay for --> Impossible");
            return decryptedLinks;
        } else if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        entries = JavaScriptEngineFactory.jsonToJavaMap(br.toString());
        final List<Object> ressourcelist = (List<Object>) entries.get("results");
        List<Object> ressourcelist_2 = null;
        int positionTOTAL = 1;
        int positionChapter = 0;
        final DecimalFormat chapterFormat = new DecimalFormat("000");
        String sectionTitle = null;
        for (final Object courseo : ressourcelist) {
            entries = (Map<String, Object>) courseo;
            final String lecture_id = Long.toString(JavaScriptEngineFactory.toLong(entries.get("id"), 0));
            final String _class = (String) entries.get("_class");
            final Object supplementary_assets = entries.get("supplementary_assets");
            if (_class.equals("chapter")) {
                positionChapter += 1;
                /* 2020-07-27: Single objects of courses and their "headlines" are all mixed into one array lol. */
                sectionTitle = (String) entries.get("title");
                if (StringUtils.isEmpty(sectionTitle)) {
                    /* This should never happen */
                    sectionTitle = "unknown_" + (positionTOTAL - 1);
                }
                sectionTitle = chapterFormat.format(positionChapter) + "_" + sectionTitle;
                continue;
            } else if (lecture_id.equals("0") || !_class.equalsIgnoreCase("lecture")) {
                /* Skip unsupported/dummy items such as "practice". */
                continue;
            }
            entries = (Map<String, Object>) entries.get("asset");
            if (entries == null) {
                continue;
            }
            decryptedLinks.add(processAsset(entries, courseSlug, courseTitle, sectionTitle, lecture_id, positionTOTAL));
            /* Add file content */
            if (supplementary_assets != null) {
                ressourcelist_2 = (List<Object>) supplementary_assets;
                for (final Object supplementary_asseto : ressourcelist_2) {
                    entries = (Map<String, Object>) supplementary_asseto;
                    decryptedLinks.add(processAsset(entries, courseSlug, courseTitle, sectionTitle, lecture_id, positionTOTAL));
                }
            }
            positionTOTAL++;
        }
        return decryptedLinks;
    }

    /** Crawls single object (video or document) */
    private DownloadLink processAsset(final Map<String, Object> entries, final String courseSlug, final String courseTitle, final String chapterTitle, final String lectureID, final int position) {
        final FilePackage fp = FilePackage.getInstance();
        fp.setName(courseTitle + " - " + chapterTitle);
        final DecimalFormat df = new DecimalFormat("000");
        final String position_formatted = df.format(position);
        final String asset_id = Long.toString(JavaScriptEngineFactory.toLong(entries.get("id"), 0));
        final String title = (String) entries.get("title");
        final String filename = (String) entries.get("filename");
        /* E.g. Video, Article, File */
        final String asset_type = (String) entries.get("asset_type");
        if (asset_id.equals("0") || StringUtils.isEmpty(asset_type)) {
            return null;
        }
        String filename_temp;
        if (!StringUtils.isEmpty(filename)) {
            filename_temp = course_id + "_" + position_formatted + "_" + lectureID + "_" + asset_id + "_" + filename;
        } else if (!StringUtils.isEmpty(title)) {
            filename_temp = title;
            filename_temp = course_id + "_" + position_formatted + "_" + lectureID + "_" + asset_id + "_" + filename_temp;
        } else {
            filename_temp = course_id + "_" + position_formatted + "_" + lectureID + "_" + asset_id;
            if ("Article".equalsIgnoreCase(asset_type)) {
                filename_temp += ".html";
            }
        }
        final DownloadLink dl;
        if (asset_type.equalsIgnoreCase("ExternalLink")) {
            /* Add external urls as our plugins might be able to parse some of them. */
            /* TODO: Check if normal (e.g. "Video") assets can also contain an "external_url" object. */
            final String external_url = (String) entries.get("external_url");
            if (StringUtils.isEmpty(external_url)) {
                return null;
            }
            dl = createDownloadlink(external_url);
        } else {
            dl = createDownloadlink("http://" + decrypter_domain + "/lecture_id/" + asset_id);
            dl.setName(filename_temp);
            /* URL for user to copy */
            dl.setContentUrl("https://www." + this.getHost() + "/course/" + courseSlug + "/learn/lecture/" + lectureID);
            dl.setAvailable(true);
            dl.setProperty("asset_type", asset_type);
            dl.setProperty("filename_decrypter", filename_temp);
            dl.setProperty("lecture_id", lectureID);
            dl.setProperty("course_id", course_id);
            dl.setProperty("position", position);
            /* Set relative download path */
            /* Remove potential "/" from inside titles to prevent wrong paths! */
            final String courseTitleSanitized = courseTitle.replace("/", "_");
            final String chapterTitleSanitized = chapterTitle.replace("/", "_");
            if (asset_type.equalsIgnoreCase("video")) {
                dl.setRelativeDownloadFolderPath(courseTitleSanitized + "/" + chapterTitleSanitized);
            } else {
                dl.setRelativeDownloadFolderPath(courseTitleSanitized + "/" + chapterTitleSanitized + "/ressources");
            }
        }
        dl._setFilePackage(fp);
        return dl;
    }
}
