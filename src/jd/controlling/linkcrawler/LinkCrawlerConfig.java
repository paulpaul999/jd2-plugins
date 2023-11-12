package jd.controlling.linkcrawler;

import java.util.List;

import org.appwork.storage.config.ConfigInterface;
import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.DefaultBooleanValue;
import org.appwork.storage.config.annotations.DefaultEnumValue;
import org.appwork.storage.config.annotations.DefaultIntValue;
import org.appwork.storage.config.annotations.DefaultJsonObject;
import org.appwork.storage.config.annotations.DefaultStringArrayValue;
import org.appwork.storage.config.annotations.DescriptionForConfigEntry;
import org.appwork.storage.config.annotations.RequiresRestart;
import org.appwork.storage.config.annotations.SpinnerValidator;

public interface LinkCrawlerConfig extends ConfigInterface {
    @DefaultIntValue(12)
    @AboutConfig
    @RequiresRestart("A JDownloader Restart is Required")
    @DescriptionForConfigEntry("max. number of linkcrawler threads")
    @SpinnerValidator(min = 1, max = 128)
    int getMaxThreads();

    void setMaxThreads(int i);

    @DefaultIntValue(20000)
    @AboutConfig
    @RequiresRestart("A JDownloader Restart is Required")
    @DescriptionForConfigEntry("max. time in ms before killing an idle linkcrawler thread")
    int getThreadKeepAlive();

    void setThreadKeepAlive(int i);

    @DefaultIntValue(10 * 1024 * 1024)
    @AboutConfig
    @RequiresRestart("A JDownloader Restart is Required")
    @DescriptionForConfigEntry("max. bytes for page request during deep decrypt")
    @SpinnerValidator(min = 1 * 1024 * 1024, max = 100 * 1024 * 1024)
    int getDeepDecryptLoadLimit();

    void setDeepDecryptLoadLimit(int l);

    @DefaultIntValue(2 * 1024 * 1024)
    @AboutConfig
    @RequiresRestart("A JDownloader Restart is Required")
    @DescriptionForConfigEntry("max. file size in bytes during deep decrypt")
    @SpinnerValidator(min = -1, max = 100 * 1024 * 1024)
    int getDeepDecryptFileSizeLimit();

    void setDeepDecryptFileSizeLimit(int l);

    @DefaultStringArrayValue({ "CAPTCHA", "EMPTY_FOLDER", "EMPTY_PROFILE", "FILE_NOT_FOUND", "NO_ACCOUNT", "PLUGIN_DEFECT", "PLUGIN_SETTINGS", "PASSWORD", "GEO", "IP", "HOST_RATE_LIMIT", "UNSUPPORTED_LIVESTREAM", "BLOCKED_BY" })
    @AboutConfig
    @DescriptionForConfigEntry("Add a retry task for following crawling errors")
    String[] getAddRetryCrawlerTasks2();

    public void setAddRetryCrawlerTasks2(String[] origins);

    @DefaultBooleanValue(true)
    @AboutConfig
    boolean isLinkCrawlerRulesEnabled();

    @DefaultStringArrayValue({ "ADD_LINKS_DIALOG", "PASTE_LINKS_ACTION", "MYJD" })
    @AboutConfig
    String[] getAutoLearnExtensionOrigins();

    public void setAutoLearnExtensionOrigins(String[] origins);

    void setLinkCrawlerRulesEnabled(boolean b);

    @DefaultJsonObject("[]")
    @AboutConfig
    List<LinkCrawlerRuleStorable> getLinkCrawlerRules();

    void setLinkCrawlerRules(List<LinkCrawlerRuleStorable> linkCrawlerRules);

    public static enum DirectHTTPPermission {
        ALWAYS,
        RULES_ONLY,
        FORBIDDEN
    }

    @AboutConfig
    @DefaultEnumValue("ALWAYS")
    @DescriptionForConfigEntry("When to accept direct downloadable URLs? ALWAYS = Accept all, no matter how they were added RULES_ONLY = Only accept direct URLs added via LinkCrawler DIRECTHTTP rule, FORBIDDEN = Never accept direct URLs")
    DirectHTTPPermission getDirectHTTPPermission();

    void setDirectHTTPPermission(DirectHTTPPermission e);

    @AboutConfig
    @DefaultBooleanValue(true)
    boolean isAutoImportContainer();

    public void setAutoImportContainer(boolean b);
}
