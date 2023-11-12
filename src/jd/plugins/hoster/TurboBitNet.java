//    jDownloader - Downloadmanager
//    Copyright (C) 2012  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
package jd.plugins.hoster;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.plugins.components.TurbobitCore;
import org.jdownloader.plugins.components.config.TurbobitCoreConfigTurbobitNet;
import org.jdownloader.plugins.components.config.TurbobitCoreConfigTurbobitNet.PreferredDomain;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginJsonConfig;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.HostPlugin;

@HostPlugin(revision = "$Revision: 48338 $", interfaceVersion = 2, names = {}, urls = {})
public class TurboBitNet extends TurbobitCore {
    public TurboBitNet(PluginWrapper wrapper) {
        super(wrapper);
    }

    /* Keep this up2date! */
    public static String[] domains      = new String[] { "turbobit.net", "ifolder.com.ua", "turo-bit.net", "depositfiles.com.ua", "dlbit.net", "hotshare.biz", "sibit.net", "turbobit.ru", "xrfiles.ru", "turbabit.net", "filedeluxe.com", "filemaster.ru", "файлообменник.рф", "turboot.ru", "kilofile.com", "twobit.ru", "forum.flacmania.ru", "filhost.ru", "fayloobmennik.com", "rapidfile.tk", "turbo.to", "cccy.su", "turbo-bit.net", "turbobit.cc", "turbobit.pw", "turbo.to", "turb.to", "turbo-bit.pw", "turbobit.cloud", "turbobit.online", "turbobit.live", "wayupload.com", "turb.cc", "turbobit5.net", "turbobith.net", "turbobi.pw", "turbobbit.com", "turbobyt.com", "trubobit.com", "turboget.net", "turbobiyt.net", "turbobif.com", "turbobite.net", "tourbobit.com", "turbobitn.com", "turbobeet.net" };
    public static String[] domains_dead = new String[] { "turbobbit.com", "ifolder.com.ua" };

    /* Setting domains */
    // protected final String[] user_domains = new String[] { "turbo.to", "turb.to", "turbobit.net", "turbobit.pw" };
    @Override
    protected String getConfiguredDomain() {
        /* Returns user-set value which can be used to circumvent government based GEO-block. */
        /**
         * 2021-02-03: They're adding new domains quite often so in order to provide a more permanent solution for the user, I've added a
         * setting to let the user define a custom preferred domain.
         */
        final TurbobitCoreConfigTurbobitNet cfg = PluginJsonConfig.get(TurbobitCoreConfigTurbobitNet.class);
        final String customDefinedPreferredDomain = cfg.getCustomDomain();
        final List<String> deadDomains = Arrays.asList(domains_dead);
        if (customDefinedPreferredDomain != null && deadDomains.contains(customDefinedPreferredDomain)) {
            logger.info("Ignoring custom domain " + customDefinedPreferredDomain + " because that one is in the list of dead domains");
        } else if (!StringUtils.isEmpty(customDefinedPreferredDomain)) {
            try {
                final URL url;
                if (customDefinedPreferredDomain.matches("(?i)https?://.+")) {
                    url = new URL(customDefinedPreferredDomain);
                } else {
                    url = new URL("https://" + customDefinedPreferredDomain);
                }
                org.appwork.utils.net.URLHelper.verifyURL(url);
                logger.info("Using user defined domain: " + customDefinedPreferredDomain);
                return url.getHost();
            } catch (final Throwable e) {
                logger.exception("User defined domain is invalid:" + customDefinedPreferredDomain, e);
            }
        }
        PreferredDomain cfgdomain = cfg.getPreferredDomain();
        if (cfgdomain == null) {
            cfgdomain = PreferredDomain.DEFAULT;
        }
        switch (cfgdomain) {
        case DOMAIN1:
            return "turbo-bit.pw";
        case DOMAIN2:
            return "turbobi.pw";
        case DEFAULT:
        default:
            return this.getHost();
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(Account account) throws Exception {
        final AccountInfo ai = super.fetchAccountInfo(account);
        if (Account.AccountType.PREMIUM.equals(account.getType()) && ai != null && !ai.isExpired()) {
            final Browser brc = br.cloneBrowser();
            brc.getPage("/premium/info");
            final String daily = brc.getRegex("\\(\\s*from\\s*([0-9,\\.]+\\s*[TGKM]B)\\s*/\\s*day").getMatch(0);
            final String dailyLeft = brc.getRegex("until\\s*the\\s*end\\s*of\\s*the\\s*day\\s*:\\s*<b>\\s*([0-9,\\.]+\\s*[TGKM]B)").getMatch(0);
            // final String monthly = brc.getRegex("\\(\\s*from\\s*([0-9,\\.]+\\s*[TGKM]B)\\s*/\\s*month").getMatch(0);
            // final String monthlyLeft =
            // brc.getRegex("of\\s*the\\s*monthly\\s*traffic\\s*:\\s*<b>\\s*([0-9,\\.]+\\s*[TGKM]B)").getMatch(0);
            final String expireDate = brc.getRegex("Date\\s*end\\s*:\\s*<b>\\s*(\\d{2}.\\d{2}\\.\\d{4}\\s*\\d{2}:\\d{2})\\s*<").getMatch(0);
            if (expireDate != null) {
                ai.setValidUntil(TimeFormatter.getMilliSeconds(expireDate.trim(), "dd.MM.yyyy' 'HH:mm", Locale.ENGLISH));
            }
            if (daily != null && dailyLeft != null) {
                ai.setTrafficMax(daily);
                ai.setTrafficLeft(dailyLeft);
            }
        }
        return ai;
    }

    public static String[] getAnnotationNames() {
        return new String[] { domains[0] };
    }

    @Override
    public int minimum_pre_download_waittime_seconds() {
        /* 2019-05-11 */
        return 60;
    }

    /**
     * returns the annotation pattern array
     *
     */
    public static String[] getAnnotationUrls() {
        // construct pattern
        final String host = getHostsPattern();
        return new String[] { host + "/([A-Za-z0-9]+(/[^<>\"/]*?)?\\.html|download/free/[a-z0-9]+|/?download/redirect/[A-Za-z0-9]+/[a-z0-9]+)" };
    }

    private static String getHostsPattern() {
        final StringBuilder pattern = new StringBuilder();
        for (final String name : domains) {
            pattern.append((pattern.length() > 0 ? "|" : "") + Pattern.quote(name));
        }
        final String hosts = "https?://(?:www\\.|new\\.|m\\.)?" + "(?:" + pattern.toString() + ")";
        return hosts;
    }

    @Override
    public String[] siteSupportedNames() {
        final List<String> ret = new ArrayList<String>(Arrays.asList(domains));
        ret.add("turbobit");
        return ret.toArray(new String[0]);
    }

    @Override
    protected boolean isFastLinkcheckEnabled() {
        return PluginJsonConfig.get(TurbobitCoreConfigTurbobitNet.class).isEnableFastLinkcheck();
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return TurbobitCoreConfigTurbobitNet.class;
    }
}