package org.jdownloader.settings;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;

import org.appwork.storage.config.ConfigInterface;
import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.AbstractCustomValueGetter;
import org.appwork.storage.config.annotations.CustomValueGetter;
import org.appwork.storage.config.annotations.DefaultBooleanValue;
import org.appwork.storage.config.annotations.DefaultEnumValue;
import org.appwork.storage.config.annotations.DefaultFactory;
import org.appwork.storage.config.annotations.DefaultIntValue;
import org.appwork.storage.config.annotations.DefaultJsonObject;
import org.appwork.storage.config.annotations.DefaultLongValue;
import org.appwork.storage.config.annotations.DefaultOnNull;
import org.appwork.storage.config.annotations.DescriptionForConfigEntry;
import org.appwork.storage.config.annotations.LabelInterface;
import org.appwork.storage.config.annotations.RequiresRestart;
import org.appwork.storage.config.annotations.SpinnerValidator;
import org.appwork.storage.config.defaults.AbstractDefaultFactory;
import org.appwork.storage.config.handler.KeyHandler;
import org.appwork.utils.StringUtils;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.os.hardware.HardwareType;
import org.appwork.utils.os.hardware.HardwareTypeInterface;
import org.jdownloader.controlling.domainrules.DomainRule;
import org.jdownloader.gui.translate._GUI;

import com.sun.jna.platform.win32.KnownFolders;

import jd.controlling.downloadcontroller.DownloadLinkCandidateSelector;

public interface GeneralSettings extends ConfigInterface {
    class DefaultDownloadFolder extends AbstractDefaultFactory<String> {
        @Override
        public String getDefaultValue(KeyHandler<String> keyHandler) {
            if (CrossSystem.isWindows()) {
                try {
                    final String ret = com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads);
                    if (CrossSystem.isAbsolutePath(ret) && new File(ret).isDirectory()) {
                        return ret;
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            } else if (CrossSystem.isLinux()) {
                try {
                    /**
                     * 2021-10-06: Comment by pspzockerscene </br>
                     * Try to find a nice default download folder for Synology. </br>
                     * Main issue here: The Synology DSM system does not have any kind of base download folder which is visible to the user.
                     * The folders below are only trail and error/assumptions. </br>
                     *
                     * \@download = old temp download directory of "DownloadStation" Synology application RE:
                     * https://www.synology-forum.de/threads/wo-ist-der-temporaere-speicherort.50969/ </br>
                     * DownloadStation never created any user visible download folders but instead upon first start it asks the user to
                     * choose a default download folder. </br>
                     * public = No idea what folder that should be. </br>
                     * Folders which are definitely always accessible/visible to the Synology users are e.g. paths to reemovable media such
                     * as: </br>
                     * /volumeUSB1/usbshare or /volumeUSB2/usbshare </br>
                     * --> Just keep in mind that using those might be a bad idea too because typically users use the Synology USB port for
                     * small USB stick or backup HDDs so they do not expect an application to just writefiles on them. </br>
                     * Ultimately the best solution would be to have a path selector built in myjdownloader which is simply limited to all
                     * the folders that the user can see -> Problem solved. </br>
                     * Conclusion: It is not possible to get a good default download folder for Synology users! The code below is not really
                     * helpful.
                     */
                    final HardwareTypeInterface hardwareType = HardwareType.getHardware();
                    if (hardwareType != null && HardwareTypeInterface.ID.SYNOLOGY.equals(hardwareType.getHardwareType())) {
                        for (int volume = 1; volume <= 4; volume++) {
                            File root = new File("/volume" + volume);
                            if (root.isDirectory()) {
                                final String defaultFolders[] = new String[] { "@download", "public" };
                                for (String defaultFolder : defaultFolders) {
                                    final File folder = new File(root, defaultFolder);
                                    if (folder.isDirectory()) {
                                        return defaultFolder.toString();
                                    }
                                }
                            }
                        }
                        for (int volume = 1; volume <= 4; volume++) {
                            File root = new File("/volumeUSB" + volume);
                            if (root.isDirectory()) {
                                final String defaultFolders[] = new String[] { "@download", "public" };
                                for (String defaultFolder : defaultFolders) {
                                    final File folder = new File(root, defaultFolder);
                                    if (folder.isDirectory()) {
                                        return defaultFolder.toString();
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            return CrossSystem.getDefaultDownloadDirectory();
        }
    }

    class CustomDownloadFolderGetter extends AbstractCustomValueGetter<String> {
        String defaultFolder = null;

        @Override
        public String getValue(KeyHandler<String> keyHandler, String value) {
            if (StringUtils.isEmpty(value)) {
                if (defaultFolder != null) {
                    return defaultFolder;
                }
                defaultFolder = new DefaultDownloadFolder().getDefaultValue(keyHandler);
                return defaultFolder;
            }
            return value;
        }
    };

    @DefaultIntValue(10)
    @AboutConfig
    @SpinnerValidator(min = 0, max = 120)
    @DescriptionForConfigEntry("AutoStart Downloads will show a Countdown Dialog after Startup. Set the countdown time to 0 to remove this dialog. @see showCountdownonAutoStartDownloads")
    int getAutoStartCountdownSeconds();

    AutoDownloadStartOption getAutoStartDownloadOption();

    String[] getBrowserCommandLine();

    @AboutConfig
    @DefaultEnumValue("AUTO")
    @DescriptionForConfigEntry("Delay writes to disk of background tasks")
    @RequiresRestart("A JDownloader Restart is Required")
    DelayWriteMode getDelayWriteMode();

    public void setDelayWriteMode(DelayWriteMode mode);

    @AboutConfig
    @DefaultEnumValue("NEVER")
    @DescriptionForConfigEntry("Configure when finished links/packages should be automatically removed from downloadlist.")
    CleanAfterDownloadAction getCleanupAfterDownloadAction();

    @AboutConfig
    @DefaultEnumValue("AUTO")
    MirrorDetectionDecision getMirrorDetectionDecision();

    @AboutConfig
    @DefaultBooleanValue(true)
    @DescriptionForConfigEntry("Mirrordetection works caseinsensitive on filename")
    @RequiresRestart("A JDownloader Restart is Required")
    boolean isForceMirrorDetectionCaseInsensitive();

    void setForceMirrorDetectionCaseInsensitive(boolean b);

    @AboutConfig
    @DefaultIntValue(10000)
    @SpinnerValidator(min = 1, max = 10000)
    @DescriptionForConfigEntry("Filesize must be x equal to be a mirror. 10000 = 100%")
    int getMirrorDetectionFileSizeEquality();

    public void setMirrorDetectionFileSizeEquality(int size);

    @AboutConfig
    @DefaultBooleanValue(false)
    @DescriptionForConfigEntry("Mirrordetection enforces verified filesizes!")
    boolean isForceMirrorDetectionFileSizeCheck();

    public void setForceMirrorDetectionFileSizeCheck(boolean b);

    public void setMirrorDetectionDecision(MirrorDetectionDecision decision);

    @AboutConfig
    @DefaultFactory(DefaultDownloadFolder.class)
    @CustomValueGetter(CustomDownloadFolderGetter.class)
    String getDefaultDownloadFolder();

    // ArrayList<String[]> getDownloadFolderHistory();
    @AboutConfig
    @DefaultLongValue(5 * 60 * 1000l)
    @DescriptionForConfigEntry("Waittime in ms if a Download HashCheck Failed")
    long getDownloadHashCheckFailedRetryWaittime();

    @AboutConfig
    @DescriptionForConfigEntry("Download Speed limit in bytes.")
    @DefaultIntValue(50 * 1024)
    @SpinnerValidator(min = 1, max = Integer.MAX_VALUE)
    int getDownloadSpeedLimit();

    @AboutConfig
    @DescriptionForConfigEntry("If >0, JD will start additional downloads when total speed is below this value. unit is byte/s")
    @DefaultIntValue(0)
    @SpinnerValidator(min = 0, max = Integer.MAX_VALUE)
    int getAutoMaxDownloadsSpeedLimit();

    void setAutoMaxDownloadsSpeedLimit(int speed);

    final static int SOFT_MAX_DOWNLOADS = 20;
    final static int HARD_MAX_DOWNLOADS = SOFT_MAX_DOWNLOADS * 2;

    @AboutConfig
    @DescriptionForConfigEntry("see AutoMaxDownloadsSpeedLimit, if >0, JD will auto start max x downloads.")
    @DefaultIntValue(5)
    @SpinnerValidator(min = 0, max = HARD_MAX_DOWNLOADS)
    int getAutoMaxDownloadsSpeedLimitMaxDownloads();

    void setAutoMaxDownloadsSpeedLimitMaxDownloads(int maxDownloads);

    @AboutConfig
    @DescriptionForConfigEntry("see AutoMaxDownloadsSpeedLimit, minimum delay to wait after last started download. Unit is milliseconds.")
    @DefaultIntValue(10000)
    @SpinnerValidator(min = 0, max = Integer.MAX_VALUE)
    int getAutoMaxDownloadsSpeedLimitMinDelay();

    void setAutoMaxDownloadsSpeedLimitMinDelay(int minDelay);

    @AboutConfig
    @DefaultLongValue(30 * 60 * 1000l)
    @DescriptionForConfigEntry("Waittime in ms if a download temp unavailable failed")
    long getDownloadTempUnavailableRetryWaittime();

    @AboutConfig
    @DefaultLongValue(60 * 60 * 1000l)
    @DescriptionForConfigEntry("Waittime in ms if a download host unavailable failed")
    long getDownloadHostUnavailableRetryWaittime();

    public void setDownloadHostUnavailableRetryWaittime(long r);

    @AboutConfig
    @DefaultLongValue(15 * 60 * 1000l)
    long getProxyHostBanTimeout();

    public void setProxyHostBanTimeout(long r);

    @AboutConfig
    @DescriptionForConfigEntry("flush download buffers after x ms")
    @DefaultIntValue(2 * 60 * 1000)
    int getFlushBufferTimeout();

    @AboutConfig
    @DescriptionForConfigEntry("flush download buffers when x % full")
    @DefaultIntValue(80)
    @SpinnerValidator(min = 1, max = 100)
    int getFlushBufferLevel();

    public void setFlushBufferLevel(int level);

    @AboutConfig
    @DescriptionForConfigEntry("Force Jdownloader to always keep a certain amount of MB Diskspace free")
    @DefaultIntValue(128)
    @SpinnerValidator(min = 0, max = Integer.MAX_VALUE)
    int getForcedFreeSpaceOnDisk();

    @AboutConfig
    @DefaultEnumValue("ASK_FOR_EACH_FILE")
    IfFileExistsAction getIfFileExistsAction();

    @AboutConfig
    @DefaultBooleanValue(false)
    @DescriptionForConfigEntry("Allow unsafe filenames for file exists check")
    boolean isAllowUnsafeFileNameForFileExistsCheck();

    void setAllowUnsafeFileNameForFileExistsCheck(boolean b);

    @AboutConfig
    @DefaultBooleanValue(true)
    @DescriptionForConfigEntry("Allow cleanup of existing files")
    boolean getCleanupFileExists();

    void setCleanupFileExists(boolean b);

    @AboutConfig
    @DescriptionForConfigEntry("max buffer size for write operations in kb")
    @SpinnerValidator(min = 100, max = 100480)
    @DefaultIntValue(500)
    int getMaxBufferSize();

    @AboutConfig
    @DescriptionForConfigEntry("http://jdownloader.org/knowledge/wiki/glossary/chunkload")
    @SpinnerValidator(min = 1, max = 20)
    @DefaultIntValue(1)
    int getMaxChunksPerFile();

    @AboutConfig
    @DescriptionForConfigEntry("How often a Plugin restarts a download if download failed")
    @DefaultIntValue(3)
    @RequiresRestart("A JDownloader Restart is Required")
    int getMaxPluginRetries();

    @AboutConfig
    @DescriptionForConfigEntry("How many downloads should Jdownloader download at once? Note that most hosters allow only one download at a time in freemode")
    @SpinnerValidator(min = 1, max = SOFT_MAX_DOWNLOADS)
    @DefaultIntValue(3)
    int getMaxSimultaneDownloads();

    @AboutConfig
    @DescriptionForConfigEntry("How many downloads more than getMaxSimultaneDownloads should JDownloader download at once when forced?")
    @SpinnerValidator(min = 1, max = HARD_MAX_DOWNLOADS)
    @DefaultIntValue(5)
    int getMaxForcedDownloads();

    public void setMaxForcedDownloads(int i);

    @AboutConfig
    @DefaultIntValue(1)
    @SpinnerValidator(min = 1, max = HARD_MAX_DOWNLOADS)
    int getMaxSimultaneDownloadsPerHost();

    @AboutConfig
    @DescriptionForConfigEntry("Pause Speed. in Pause Mode we limit speed to this value to keep connections open, but use hardly bandwidth")
    @DefaultIntValue(10240)
    @SpinnerValidator(min = 0, max = Integer.MAX_VALUE)
    int getPauseSpeed();

    @AboutConfig
    @DescriptionForConfigEntry("Penaltytime before a retry if JDownloader lost connection")
    @DefaultIntValue(5 * 60 * 1000)
    int getWaittimeOnConnectionLoss();

    @AboutConfig
    @DescriptionForConfigEntry("Keep max X old lists on disk (DownloadList,Linkgrabber)")
    @DefaultIntValue(5)
    @SpinnerValidator(min = 0, max = 20)
    int getKeepXOldLists();

    void setKeepXOldLists(int x);

    @AboutConfig
    @DescriptionForConfigEntry("Allows the user to copy single(!) 'protected' URLs which is not possible by default. This applies e.g. to URLs added via Click and Load or DLC containers.  See: https://support.jdownloader.org/Knowledgebase/Article/View/copy-view-added-urls")
    @DefaultBooleanValue(false)
    boolean isCopySingleRealURL();

    void setCopySingleRealURL(boolean b);

    @AboutConfig
    @DefaultBooleanValue(true)
    boolean isAutoOpenContainerAfterDownload();

    @AboutConfig
    @DescriptionForConfigEntry("If enabled, packagenames will be cleaned up of superfluous . and _ characters, and replaced with spaces. Plugins can override this setting.")
    @RequiresRestart("A JDownloader Restart is Required")
    @DefaultBooleanValue(true)
    boolean isCleanUpPackagenames();

    void setCleanUpPackagenames(boolean b);

    /**
     * Important: Developers: Keep default value the same for: getPackagenameCharacterRegexReplaceMap and
     * getFilenameCharacterRegexReplaceMap !!
     */
    @AboutConfig
    @DescriptionForConfigEntry("Returns mapping of common invalid characters as regular expression to be replaced inside filenames. This is used to try to avoid invalid download paths due to forbidden characters in filenames. You can change this map to your needs but you can't turn off the execution of the RegEx replacements done via this map. Any characters which are invalid for file paths and remain after execution of these replacements will be removed by a generic replacement handling with underscores.")
    @DefaultJsonObject("{\":\":\";\",\"\\\\|\":\"\u00A6\",\"<\":\"[\",\">\":\"]\",\"/\":\"\u2044\",\"\\\\\\\\\":\"\u2216\",\"\\\\*\":\"#\",\"\\\\?\":\"\u00BF\",\"\\\\!\":\"\u00A1\",\"\\\"\":\"'\"}")
    @DefaultOnNull
    Map<String, String> getFilenameCharacterRegexReplaceMap();

    public void setFilenameCharacterRegexReplaceMap(Map<String, String> map);

    /**
     * Important: Developers: Keep default value the same for: getPackagenameCharacterRegexReplaceMap and
     * getFilenameCharacterRegexReplaceMap !!
     */
    @AboutConfig
    @DescriptionForConfigEntry("Returns mapping of common invalid characters as regular expression to be replaced inside package names. This is used to try to avoid invalid download paths due to forbidden characters in package names. You can change this map to your needs and you can disable this by turning off the setting 'Clean Up Package Names'.")
    @DefaultJsonObject("{\":\":\";\",\"\\\\|\":\"\u00A6\",\"<\":\"[\",\">\":\"]\",\"/\":\"\u2044\",\"\\\\\\\\\":\"\u2216\",\"\\\\*\":\"#\",\"\\\\?\":\"\u00BF\",\"\\\\!\":\"\u00A1\",\"\\\"\":\"'\"}")
    @DefaultOnNull
    Map<String, String> getPackagenameCharacterRegexReplaceMap();

    public void setPackagenameCharacterRegexReplaceMap(Map<String, String> map);

    boolean isClosedWithRunningDownloads();

    boolean isConvertRelativePathsJDRoot();

    @AboutConfig
    boolean isDownloadSpeedLimitEnabled();

    @AboutConfig
    @DescriptionForConfigEntry("Remember Speed Limiter enabled/disabled setting after restart. Note: not associated with pause mode.")
    @DefaultBooleanValue(true)
    boolean isDownloadSpeedLimitRememberedEnabled();

    @AboutConfig
    @DescriptionForConfigEntry("Enables permanent RegEx search in bottom search bar in LinkGrabber and Downloadlist.")
    @DefaultBooleanValue(false)
    boolean isFilterRegex();

    @AboutConfig
    @DefaultBooleanValue(true)
    boolean isHashCheckEnabled();

    @AboutConfig
    @DefaultBooleanValue(false)
    boolean isHashRetryEnabled();

    @AboutConfig
    @DefaultBooleanValue(false)
    boolean isMaxDownloadsPerHostEnabled();

    boolean isShowCountdownonAutoStartDownloads();

    @AboutConfig
    @DefaultBooleanValue(true)
    @DescriptionForConfigEntry("Use available Accounts?")
    boolean isUseAvailableAccounts();

    @AboutConfig
    @DefaultBooleanValue(false)
    boolean isUseOriginalLastModified();

    @AboutConfig
    @DefaultBooleanValue(true)
    boolean isAutoSortChildrenEnabled();

    void setAutoSortChildrenEnabled(boolean b);

    void setAutoOpenContainerAfterDownload(boolean b);

    void setAutoStartCountdownSeconds(int seconds);

    @AboutConfig
    @DefaultEnumValue("ONLY_IF_EXIT_WITH_RUNNING_DOWNLOADS")
    void setAutoStartDownloadOption(AutoDownloadStartOption option);

    @AboutConfig
    @DescriptionForConfigEntry("CommandLine to open a link in a browser. Use %s as wildcard for the url")
    void setBrowserCommandLine(String[] b);

    void setCleanupAfterDownloadAction(CleanAfterDownloadAction action);

    @DescriptionForConfigEntry("Is true, if jdownloader got closed with running downloads.")
    @DefaultBooleanValue(false)
    void setClosedWithRunningDownloads(boolean b);

    @DefaultBooleanValue(true)
    @AboutConfig
    @RequiresRestart("A JDownloader Restart is Required")
    @DescriptionForConfigEntry("Correct paths relative to JDownloader root")
    void setConvertRelativePathsJDRoot(boolean b);

    void setDefaultDownloadFolder(String ddl);

    // void setDownloadFolderHistory(ArrayList<String[]> history);
    void setDownloadHashCheckFailedRetryWaittime(long ms);

    void setDownloadSpeedLimit(int bytes);

    void setDownloadSpeedLimitEnabled(boolean b);

    void setDownloadSpeedLimitRememberedEnabled(boolean b);

    void setDownloadTempUnavailableRetryWaittime(long ms);

    void setFilterRegex(boolean b);

    void setFlushBufferTimeout(int ms);

    void setForcedFreeSpaceOnDisk(int mb);

    void setHashCheckEnabled(boolean b);

    void setHashRetryEnabled(boolean b);

    void setIfFileExistsAction(IfFileExistsAction action);

    void setMaxBufferSize(int num);

    void setMaxChunksPerFile(int num);

    void setMaxDownloadsPerHostEnabled(boolean b);

    void setMaxPluginRetries(int nums);

    void setMaxSimultaneDownloads(int num);

    void setMaxSimultaneDownloadsPerHost(int num);

    void setPauseSpeed(int kb);

    @DefaultBooleanValue(true)
    @AboutConfig
    @DescriptionForConfigEntry("@see AutoStartCountdownSeconds")
    void setShowCountdownonAutoStartDownloads(boolean b);

    void setUseAvailableAccounts(boolean b);

    void setUseOriginalLastModified(boolean b);

    void setWaittimeOnConnectionLoss(int milliseconds);

    @AboutConfig
    @DescriptionForConfigEntry("Should JDownloader check free available diskspace before download starts?")
    @DefaultBooleanValue(true)
    boolean isFreeSpaceCheckEnabled();

    void setFreeSpaceCheckEnabled(boolean b);

    @AboutConfig
    @DefaultBooleanValue(true)
    @DescriptionForConfigEntry("Disable this option if you do not want to see the filename in a captchadialog")
    boolean isShowFileNameInCaptchaDialogEnabled();

    void setShowFileNameInCaptchaDialogEnabled(boolean b);

    //
    @AboutConfig
    @DefaultBooleanValue(true)
    @DescriptionForConfigEntry("If Enabled, JDownloader will save the linkgrabber list when you exit jd, and restore it on next startup")
    boolean isSaveLinkgrabberListEnabled();

    void setSaveLinkgrabberListEnabled(boolean b);

    public static enum DeleteContainerAction {
        ASK_FOR_DELETE,
        DELETE,
        DONT_DELETE
    }

    @AboutConfig
    @DefaultEnumValue("DONT_DELETE")
    @DescriptionForConfigEntry("What Action should be performed after adding a container (DLC RSDF,METALINK,CCF,...)")
    DeleteContainerAction getDeleteContainerFilesAfterAddingThemAction();

    void setDeleteContainerFilesAfterAddingThemAction(DeleteContainerAction action);

    /**
     * TODO: Rename ON_DOWNLOAD_START to ON_DOWNLOAD_ATTEMPT once the following ticket is being worked on:
     * https://svn.jdownloader.org/issues/90261
     */
    public static enum CreateFolderTrigger implements LabelInterface {
        ON_DOWNLOAD_START {
            @Override
            public String getLabel() {
                return "When the download is attempted to be started";
            }
        },
        ON_LINKS_ADDED {
            @Override
            public String getLabel() {
                return "When the links are added to the downloadlist";
            }
        };
    }

    @AboutConfig
    @DescriptionForConfigEntry("When should JD create the final download directory?")
    @DefaultEnumValue("ON_DOWNLOAD_START")
    CreateFolderTrigger getCreateFolderTrigger();

    void setCreateFolderTrigger(CreateFolderTrigger trigger);

    @AboutConfig
    @DefaultBooleanValue(true)
    boolean isDeleteEmptySubFoldersAfterDeletingDownloadedFilesEnabled();

    void setDeleteEmptySubFoldersAfterDeletingDownloadedFilesEnabled(boolean b);

    @AboutConfig
    @DefaultBooleanValue(true)
    boolean isSambaPrefetchEnabled();

    void setSambaPrefetchEnabled(boolean b);

    @AboutConfig
    @DescriptionForConfigEntry("Set a list of hostplugin names to ignore")
    @DefaultJsonObject(value = "[]")
    String[] getCrawlerHostPluginBlacklist();

    void setCrawlerHostPluginBlacklist(String[] blacklist);

    @AboutConfig
    @DescriptionForConfigEntry("Set a list of crawlerplugin names to ignore")
    @DefaultJsonObject(value = "[]")
    String[] getCrawlerCrawlerPluginBlacklist();

    void setCrawlerCrawlerPluginBlacklist(String[] blacklist);

    public static enum OnSkipDueToAlreadyExistsAction implements LabelInterface {
        SKIP_FILE() {
            public String getLabel() {
                return _GUI.T.OnSkipDueToAlreadyExistsAction_skip_file();
            }
        },
        SET_FILE_TO_SUCCESSFUL {
            public String getLabel() {
                return _GUI.T.OnSkipDueToAlreadyExistsAction_mark_successful();
            }
        },
        SET_FILE_TO_SUCCESSFUL_MIRROR {
            public String getLabel() {
                return _GUI.T.OnSkipDueToAlreadyExistsAction_mark_successful_mirror();
            }
        }
    }

    @AboutConfig
    @DefaultEnumValue("SKIP_FILE")
    OnSkipDueToAlreadyExistsAction getOnSkipDueToAlreadyExistsAction();

    void setOnSkipDueToAlreadyExistsAction(OnSkipDueToAlreadyExistsAction e);

    @AboutConfig
    @DescriptionForConfigEntry("If you experience tiny(betweeen 0 and 2 seconds) 'lags' when while working with JDownloader, try to disable this feature.")
    @DefaultBooleanValue(true)
    boolean isWindowsJNAIdleDetectorEnabled();

    void setWindowsJNAIdleDetectorEnabled(boolean b);

    @AboutConfig
    @DescriptionForConfigEntry("Load balance free downloads over all possible connections")
    @DefaultEnumValue("DISABLED")
    DownloadLinkCandidateSelector.ProxyBalanceMode getFreeProxyBalanceMode();

    void setFreeProxyBalanceMode(DownloadLinkCandidateSelector.ProxyBalanceMode mode);

    @AboutConfig
    @DescriptionForConfigEntry("Setup Rules by Domain. Let us know if you use this feature and require a nicer User Interface")
    @DefaultJsonObject("[{\"accountPattern\":\"myUsername\",\"domainPattern\":\".*jdownloader\\\\.org\",\"maxSimultanDownloads\":20,\"allowToExceedTheGlobalLimit\":false,\"filenamePattern\":\"\\\\.png$\",\"enabled\":false}]")
    ArrayList<DomainRule> getDomainRules();

    void setDomainRules(ArrayList<DomainRule> e);

    @AboutConfig
    @DescriptionForConfigEntry("Choose which type URLs will be used for 'Copy URL, Browser URL, Tables and Settings'. Valid fields: CONTAINER, CONTENT, CUSTOM, ORIGIN, REFERRER")
    UrlDisplayEntry[] getUrlOrder();

    void setUrlOrder(UrlDisplayEntry[] order);

    @AboutConfig
    @DefaultBooleanValue(false)
    boolean isUseUrlOrderForMyJD();

    void setUseUrlOrderForMyJD(boolean b);

    /**
     * remove on 1.december 2014. We just keep it now to convert to {@link #setUrlOrder(UrlDisplayEntry[])}
     *
     * @return
     */
    @Deprecated
    UrlDisplayType[] getUrlDisplayOrder();

    /**
     * remove on 1.december 2014. We just keep it now to convert to {@link #setUrlOrder(UrlDisplayEntry[])}
     *
     * @return
     */
    @Deprecated
    void setUrlDisplayOrder(UrlDisplayType[] order);

    @AboutConfig
    @DefaultLongValue(60 * 60 * 1000l)
    @DescriptionForConfigEntry("[ms] Define how long an account should stay disabled if a \"temporarily disabled event\" occures (Like Download Quota reached)")
    long getAccountTemporarilyDisabledDefaultTimeout();

    void setAccountTemporarilyDisabledDefaultTimeout(long ms);

    @AboutConfig
    @RequiresRestart("A JDownloader Restart is Required")
    @DefaultIntValue(25)
    @DescriptionForConfigEntry("How many entries will be in the download Destination quick selection")
    int getDownloadDestinationHistoryLength();

    void setDownloadDestinationHistoryLength(int i);

    @AboutConfig
    @DefaultBooleanValue(true)
    @DescriptionForConfigEntry("If the Download Destination of a packages changes (Merge packages, changed destination,...) JD will try to move or rename already downloaded files.")
    boolean isMoveFilesIfDownloadDestinationChangesEnabled();

    void setMoveFilesIfDownloadDestinationChangesEnabled(boolean b);

    @AboutConfig
    @DefaultBooleanValue(true)
    @DescriptionForConfigEntry("If the Download Destination of a packages changes (Merge packages, changed destination,...) JD will try to move or rename already downloaded files.")
    boolean isRenameFilesIfDownloadLinkNameChangesEnabled();

    void setRenameFilesIfDownloadLinkNameChangesEnabled(boolean b);

    @AboutConfig
    @DefaultBooleanValue(true)
    @DescriptionForConfigEntry("If Enabled, the linkgrabber will detect links that are already in the downloadlist")
    @RequiresRestart("A JDownloader Restart is Required")
    boolean isDupeManagerEnabled();

    void setDupeManagerEnabled(boolean b);

    @AboutConfig
    @DefaultIntValue(10)
    @DescriptionForConfigEntry("How many history entries will be kept in the download links")
    int getMaxDownloadLinkHistoryEntries();

    void setMaxDownloadLinkHistoryEntries(int size);

    @AboutConfig
    @DefaultBooleanValue(false)
    @RequiresRestart("A JDownloader Restart is Required")
    @DescriptionForConfigEntry("Enable shared memory state info.")
    boolean isSharedMemoryStateEnabled();

    void setSharedMemoryStateEnabled(boolean b);

    @AboutConfig
    @DefaultBooleanValue(false)
    @RequiresRestart("A JDownloader Restart is Required")
    @DescriptionForConfigEntry("Prefer BouncyCastle for TLS")
    boolean isPreferBouncyCastleForTLS();

    void setPreferBouncyCastleForTLS(boolean b);

    // @AboutConfig
    // @DefaultBooleanValue(true)
    // @DescriptionForConfigEntry("Enable/Disable JXBrowser usage. JXBrowser Plugin required!")
    // boolean isJxBrowserEnabled();
    //
    // void setJxBrowserEnabled(boolean b);
    @AboutConfig
    @DescriptionForConfigEntry("Timeout for network problems")
    @SpinnerValidator(min = 0, max = 1000000)
    @DefaultIntValue(15000)
    @RequiresRestart("A JDownloader Restart is Required")
    @Deprecated
    int getNetworkIssuesTimeout();

    void setNetworkIssuesTimeout(int timeout);

    @AboutConfig
    @DefaultLongValue(10 * 60 * 1000l)
    @DescriptionForConfigEntry("Waittime in ms if a Download had unknown IOException")
    @Deprecated
    long getDownloadUnknownIOExceptionWaittime();

    void setDownloadUnknownIOExceptionWaittime(long ms);
}
